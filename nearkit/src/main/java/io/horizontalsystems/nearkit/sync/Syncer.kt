package io.horizontalsystems.nearkit.sync

import io.horizontalsystems.nearkit.NearKit.SyncError
import io.horizontalsystems.nearkit.NearKit.SyncState
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.AccountState
import io.horizontalsystems.nearkit.models.ChainState
import io.horizontalsystems.nearkit.models.FtBalance
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.NetworkErrors
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.transaction.FtActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Drives one sync cycle per timer tick: latest block, account state, token balances, then
 * transaction history. [syncState] covers the balances; history has its own state.
 */
internal class Syncer(
    private val accountId: String,
    private val syncTimer: SyncTimer,
    private val rpcProvider: RpcProvider,
    private val fastNearProvider: FastNearProvider,
    private val transactionSyncer: TransactionSyncer,
    private val storage: Storage,
) : SyncTimer.Listener {

    private val logger = Logger.getLogger("NearKit")
    private val syncing = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    // A connectivity blip right after the app resumes fails one cycle and clears itself within
    // seconds. Once the kit has synced successfully, the first transient failure keeps the
    // current state and retries shortly; only a repeated failure is reported.
    private var hasSyncedOnce = false
    private var consecutiveFailures = 0

    /** Token contracts whose balance is read from the RPC even when the index does not list them. */
    @Volatile
    var watchedTokens: Set<String> = emptySet()

    var syncState: SyncState = SyncState.NotSynced(SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                _syncStateFlow.update { value }
            }
        }

    private val _syncStateFlow = MutableStateFlow(syncState)
    val syncStateFlow: StateFlow<SyncState> = _syncStateFlow

    private val _chainStateFlow = MutableStateFlow(storage.getChainState())
    val chainStateFlow: StateFlow<ChainState?> = _chainStateFlow

    private val _accountStateFlow = MutableStateFlow(storage.getAccountState() ?: AccountState.EMPTY)
    val accountStateFlow: StateFlow<AccountState> = _accountStateFlow

    private val _ftBalancesFlow = MutableStateFlow(storage.getFtBalances())
    val ftBalancesFlow: StateFlow<List<FtBalance>> = _ftBalancesFlow

    fun start(scope: CoroutineScope) {
        this.scope = scope
        syncTimer.start(this, scope)
    }

    fun stop() {
        syncState = SyncState.NotSynced(SyncError.NotStarted())
        transactionSyncer.setNotStarted()
        syncTimer.stop()
    }

    fun pause() = syncTimer.pause()

    fun resume() = syncTimer.resume()

    fun refresh() {
        when (syncTimer.state) {
            SyncTimer.State.Ready -> sync()
            is SyncTimer.State.NotReady -> scope?.let { syncTimer.start(this, it) }
        }
    }

    override fun onUpdateSyncTimerState(state: SyncTimer.State) {
        syncState = when (state) {
            is SyncTimer.State.NotReady -> {
                transactionSyncer.setNotSynced(state.error)
                SyncState.NotSynced(state.error)
            }
            SyncTimer.State.Ready -> SyncState.Syncing()
        }
    }

    override fun sync() {
        val scope = this.scope ?: return
        if (!syncing.compareAndSet(false, true)) return

        scope.launch {
            try {
                performSync()
            } finally {
                syncing.set(false)
            }
        }
    }

    /** Runs a full cycle now, outside the timer (used after a send). */
    suspend fun syncNow() {
        if (!syncing.compareAndSet(false, true)) return
        try {
            performSync()
        } finally {
            syncing.set(false)
        }
    }

    private suspend fun performSync() {
        try {
            val block = rpcProvider.latestBlock()
            val chainState = ChainState(blockHeight = block.height, gasPrice = block.gasPrice)
            storage.saveChainState(chainState)
            _chainStateFlow.update { chainState }

            val view = rpcProvider.viewAccount(accountId)
            val accountState = if (view == null) {
                AccountState.EMPTY
            } else {
                AccountState(
                    exists = true,
                    amount = view.amount,
                    locked = view.locked,
                    storageUsage = view.storageUsage,
                    hasContract = view.hasContract,
                )
            }
            if (accountState != _accountStateFlow.value) {
                storage.saveAccountState(accountState)
                _accountStateFlow.update { accountState }
            }

            if (accountState.exists) {
                syncFtBalances()
            } else if (_ftBalancesFlow.value.isNotEmpty()) {
                storage.replaceFtBalances(emptyList())
                _ftBalancesFlow.update { emptyList() }
            }

            hasSyncedOnce = true
            consecutiveFailures = 0
            syncState = SyncState.Synced()

            // reports its own failures, so an index outage or rate limit leaves balances synced
            transactionSyncer.sync(block.height)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            consecutiveFailures++
            val tolerate = hasSyncedOnce && consecutiveFailures < MAX_TOLERATED_FAILURES && NetworkErrors.isTransient(error)
            if (tolerate) {
                scope?.launch {
                    delay(TRANSIENT_RETRY_DELAY_MS)
                    sync()
                }
            } else {
                syncState = SyncState.NotSynced(error)
                transactionSyncer.setNotSynced(error)
            }
        }
    }

    /**
     * The index lists every token the account ever touched; watched tokens are read from the
     * contracts themselves, which is authoritative and not subject to index lag. An index outage
     * keeps the previous list instead of failing the whole sync.
     */
    private suspend fun syncFtBalances() {
        val balances = linkedMapOf<String, BigInteger>()
        try {
            fastNearProvider.ftBalances(accountId).forEach { balances[it.contractId] = it.balance }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.info("FastNEAR token list failed: ${e.message}")
            _ftBalancesFlow.value.forEach { balances[it.contractId] = it.balance }
        }

        for (contractId in watchedTokens) {
            try {
                val result = rpcProvider.callFunctionJson(contractId, "ft_balance_of", FtActions.accountIdArgs(accountId))
                result.takeIf { it.isJsonPrimitive }?.asString?.toBigIntegerOrNull()?.let { balances[contractId] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.info("ft_balance_of failed for $contractId: ${e.message}")
            }
        }

        val list = balances.map { (contractId, balance) -> FtBalance(contractId, balance) }
        if (list != _ftBalancesFlow.value) {
            storage.replaceFtBalances(list)
            _ftBalancesFlow.update { list }
        }
    }

    companion object {
        private const val MAX_TOLERATED_FAILURES = 2
        private const val TRANSIENT_RETRY_DELAY_MS = 3000L
    }
}
