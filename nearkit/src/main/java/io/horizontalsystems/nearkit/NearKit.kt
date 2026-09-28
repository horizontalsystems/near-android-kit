package io.horizontalsystems.nearkit

import android.content.Context
import io.horizontalsystems.nearkit.crypto.AccountId
import io.horizontalsystems.nearkit.crypto.PublicKey
import io.horizontalsystems.nearkit.database.NearDatabaseManager
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.AccountState
import io.horizontalsystems.nearkit.models.FtBalance
import io.horizontalsystems.nearkit.models.FtMetadata
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.network.ApiClient
import io.horizontalsystems.nearkit.network.ConnectionManager
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.InvalidResponse
import io.horizontalsystems.nearkit.network.Network
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.network.optLong
import io.horizontalsystems.nearkit.network.optString
import io.horizontalsystems.nearkit.sync.SyncTimer
import io.horizontalsystems.nearkit.sync.Syncer
import io.horizontalsystems.nearkit.sync.TransactionSyncer
import io.horizontalsystems.nearkit.transaction.Action
import io.horizontalsystems.nearkit.transaction.FeeCalculator
import io.horizontalsystems.nearkit.transaction.FtActions
import io.horizontalsystems.nearkit.transaction.MessagePayload
import io.horizontalsystems.nearkit.transaction.SignedMessage
import io.horizontalsystems.nearkit.transaction.SignedTransaction
import io.horizontalsystems.nearkit.transaction.Signer
import io.horizontalsystems.nearkit.transaction.TransactionSender
import io.horizontalsystems.nearkit.transaction.TransactionSender.SendError
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.math.BigInteger
import java.net.URL
import java.util.Objects

class NearKit private constructor(
    /** The account the kit syncs and signs for: implicit (64 hex) or named. */
    val accountId: String,
    val network: Network,
    private val signer: Signer?,
    private val syncer: Syncer,
    private val transactionSyncer: TransactionSyncer,
    private val transactionSender: TransactionSender,
    private val rpcProvider: RpcProvider,
    private val storage: Storage,
) {

    private var started = false
    private var scope: CoroutineScope? = null

    @Volatile
    private var feeCalculator = FeeCalculator()
    private var feeConfigLoaded = false

    val receiveAddress: String get() = accountId
    val publicKey: PublicKey? get() = signer?.publicKey
    val isMainNet: Boolean get() = network.isMainNet
    val isWatchOnly: Boolean get() = signer == null

    val syncState: SyncState get() = syncer.syncState
    val syncStateFlow: StateFlow<SyncState> get() = syncer.syncStateFlow

    val transactionsSyncState: SyncState get() = transactionSyncer.syncState
    val transactionsSyncStateFlow: StateFlow<SyncState> get() = transactionSyncer.syncStateFlow

    val lastBlockHeight: Long get() = syncer.chainStateFlow.value?.blockHeight ?: 0

    val accountState: AccountState get() = syncer.accountStateFlow.value
    val accountStateFlow: StateFlow<AccountState> get() = syncer.accountStateFlow

    /** False until the account first receives NEAR (implicit accounts) or is created (named). */
    val isAccountActive: Boolean get() = accountState.exists

    /** Liquid balance in yoctoNEAR, including what storage staking holds. */
    val balance: BigInteger get() = accountState.amount
    val balanceFlow: Flow<BigInteger> get() = accountStateFlow.map { it.amount }.distinctUntilChanged()

    /** Spendable yoctoNEAR: [balance] minus the storage stake. See [AccountState.available]. */
    val availableBalance: BigInteger get() = accountState.available
    val availableBalanceFlow: Flow<BigInteger> get() = accountStateFlow.map { it.available }.distinctUntilChanged()

    val ftBalances: List<FtBalance> get() = syncer.ftBalancesFlow.value
    val ftBalancesFlow: StateFlow<List<FtBalance>> get() = syncer.ftBalancesFlow

    fun getFtBalance(contractId: String): BigInteger? = ftBalances.firstOrNull { it.contractId == contractId }?.balance

    fun getFtBalanceFlow(contractId: String): Flow<BigInteger?> =
        ftBalancesFlow.map { list -> list.firstOrNull { it.contractId == contractId }?.balance }.distinctUntilChanged()

    /**
     * Tokens the wallet shows. Their balances are read from the contracts every sync, in
     * addition to the indexer's token list, so a new token shows up without index lag.
     */
    fun watchTokens(contractIds: Set<String>) {
        syncer.watchedTokens = contractIds.filter { AccountId.isValid(it) }.toSet()
    }

    /** Emits every batch of new or updated transactions found by a sync or created by a send. */
    val transactionsFlow: SharedFlow<List<Transaction>> get() = transactionSyncer.transactionsFlow

    /**
     * Newest first. [token] is null for all transactions, [TOKEN_NATIVE] for those that move NEAR
     * (including every transaction the account signed, for the fee) or a token contract id. Page
     * with the timestamp and hash of the last item of the previous page.
     */
    fun getTransactions(token: String? = null, beforeTimestamp: Long? = null, beforeHash: String? = null, limit: Int = 50): List<Transaction> =
        storage.getTransactions(token, beforeTimestamp, beforeHash, limit)

    fun getTransaction(hash: String): Transaction? = storage.getTransaction(hash)

    fun getPendingTransactions(): List<Transaction> = storage.getPendingTransactions()

    fun start() {
        if (started) return
        started = true
        scope = CoroutineScope(Dispatchers.IO).also { syncer.start(it) }
    }

    fun stop() {
        started = false
        syncer.stop()
        scope?.cancel()
        scope = null
    }

    fun pause() = syncer.pause()

    fun resume() = syncer.resume()

    fun refresh() = syncer.refresh()

    // ---- network queries ----

    suspend fun doesAccountExist(accountId: String): Boolean = rpcProvider.viewAccount(accountId) != null

    /** Token name, symbol and decimals from `ft_metadata`, cached after the first read. */
    suspend fun ftMetadata(contractId: String): FtMetadata {
        storage.getFtMetadata(contractId)?.let { return it }
        val json = rpcProvider.callFunctionJson(contractId, "ft_metadata") as? JsonObject
            ?: throw InvalidResponse("ft_metadata: not an object")
        val decimals = json.optLong("decimals")?.toInt()?.takeIf { it in 0..MAX_TOKEN_DECIMALS }
            ?: throw InvalidResponse("ft_metadata: invalid decimals")
        val metadata = FtMetadata(
            contractId = contractId,
            name = json.optString("name")?.take(MAX_TOKEN_TEXT) ?: "",
            symbol = json.optString("symbol")?.take(MAX_TOKEN_TEXT) ?: throw InvalidResponse("ft_metadata: missing symbol"),
            decimals = decimals,
            spec = json.optString("spec"),
        )
        storage.saveFtMetadata(metadata)
        return metadata
    }

    /** NEAR [accountId] still has to deposit with the token contract to hold it, or null when registered. */
    suspend fun ftStorageDepositRequired(contractId: String, accountId: String): BigInteger? =
        FtActions.storageDepositRequired(rpcProvider, contractId, accountId)

    // ---- fees ----

    /**
     * Fee and required balance for sending [actions] to [receiverId]. A transfer to an implicit
     * account that does not exist yet also pays the account creation charge (0.007 NEAR).
     */
    suspend fun estimateFee(receiverId: String, actions: List<Action>): FeeCalculator.Estimate {
        loadFeeConfig()
        val gasPrice = syncer.chainStateFlow.value?.gasPrice ?: rpcProvider.latestBlock().gasPrice
        val implicit = AccountId.isImplicit(receiverId)
        val createsAccount = implicit && actions.any { it is Action.Transfer } && !doesAccountExist(receiverId)
        return feeCalculator.estimate(actions, implicit, createsAccount, gasPrice)
    }

    suspend fun estimateNearTransfer(receiverId: String): FeeCalculator.Estimate =
        estimateFee(receiverId, listOf(Action.Transfer(BigInteger.ONE)))

    /** The most NEAR a transfer to [receiverId] can send: what is available minus the balance the fee needs. */
    suspend fun maxSendableNear(receiverId: String): BigInteger =
        (availableBalance - estimateNearTransfer(receiverId).requiredBalance).max(BigInteger.ZERO)

    /** Fee for a token transfer, plus the storage deposit the receiver still needs, if any. */
    suspend fun estimateFtTransfer(contractId: String, receiverId: String, amount: BigInteger, memo: String? = null): FtTransferEstimate {
        val storageDeposit = ftStorageDepositRequired(contractId, receiverId)
        val actions = FtActions.transfer(receiverId, amount, memo, storageDeposit)
        return FtTransferEstimate(fee = estimateFee(contractId, actions), storageDeposit = storageDeposit)
    }

    private suspend fun loadFeeConfig() {
        if (feeConfigLoaded) return
        try {
            FeeCalculator.Config.fromProtocolConfig(rpcProvider.protocolConfig())?.let { feeCalculator = FeeCalculator(it) }
            feeConfigLoaded = true
        } catch (e: Exception) {
            // the built-in config holds current mainnet values; try again on the next estimate
        }
    }

    // ---- sending ----

    /** Sends NEAR. A named receiver must exist; implicit receivers are created by the transfer. */
    suspend fun sendNear(receiverId: String, amount: BigInteger): Transaction {
        val signer = requireSigner()
        requireReceiver(receiverId)
        require(amount.signum() > 0) { "Amount must be positive" }
        return send(signer, receiverId, listOf(Action.Transfer(amount)))
    }

    /**
     * Sends a NEP-141 token. When the receiver is not registered with the token contract, the
     * same transaction first pays its storage deposit (usually 0.00125 NEAR, at most
     * [FtActions.MAX_STORAGE_DEPOSIT]) from this account.
     *
     * Pass the [estimate] the user confirmed: the send then fails with
     * [SendError.StorageDepositChanged] if the contract now asks for a different deposit.
     */
    suspend fun sendFt(
        contractId: String,
        receiverId: String,
        amount: BigInteger,
        memo: String? = null,
        estimate: FtTransferEstimate? = null,
    ): Transaction {
        val signer = requireSigner()
        AccountId.validate(contractId)
        AccountId.validate(receiverId)
        require(amount.signum() > 0) { "Amount must be positive" }
        val storageDeposit = ftStorageDepositRequired(contractId, receiverId)
        if (estimate != null && estimate.storageDeposit != storageDeposit) {
            throw SendError.StorageDepositChanged(estimate.storageDeposit, storageDeposit)
        }
        return send(signer, contractId, FtActions.transfer(receiverId, amount, memo, storageDeposit))
    }

    /** Signs and submits arbitrary actions, e.g. a swap deposit or a WalletConnect request. */
    suspend fun sendTransaction(receiverId: String, actions: List<Action>): Transaction {
        val signer = requireSigner()
        AccountId.validate(receiverId)
        require(actions.isNotEmpty()) { "No actions" }
        return send(signer, receiverId, actions)
    }

    /** Signs without submitting, with a fresh nonce and block hash. */
    suspend fun signTransaction(receiverId: String, actions: List<Action>): SignedTransaction {
        val signer = requireSigner()
        AccountId.validate(receiverId)
        return transactionSender.sign(signer, receiverId, actions)
    }

    /**
     * Signs a transaction someone else built (WalletConnect `near_signTransaction`). The signer id
     * and public key must be this kit's; the nonce and block hash are taken as given.
     */
    fun signTransaction(transaction: io.horizontalsystems.nearkit.transaction.Transaction): SignedTransaction {
        val signer = requireSigner()
        require(transaction.signerId == accountId) { "Transaction is for signer ${transaction.signerId}" }
        return signer.sign(transaction)
    }

    /** Signs a NEP-413 message for WalletConnect `near_signMessage`. */
    fun signMessage(payload: MessagePayload): SignedMessage {
        val signer = requireSigner()
        return SignedMessage(accountId, signer.publicKey, signer.sign(payload))
    }

    /**
     * Submits a transaction signed elsewhere and records it as pending. It must be signed by this
     * account: anything else would show up in the account's history as its own send. The node
     * checks the signature and the key.
     */
    suspend fun submit(signed: SignedTransaction): Transaction {
        require(signed.transaction.signerId == accountId) { "Transaction is for signer ${signed.transaction.signerId}" }
        val tx = transactionSender.submit(signed, referenceHeight = null)
        syncAfterSend()
        return tx
    }

    private suspend fun send(signer: Signer, receiverId: String, actions: List<Action>): Transaction {
        val tx = transactionSender.send(signer, receiverId, actions)
        syncAfterSend()
        return tx
    }

    private suspend fun requireReceiver(receiverId: String) {
        AccountId.validate(receiverId)
        if (!AccountId.isImplicit(receiverId) && !doesAccountExist(receiverId)) {
            throw SendError.ReceiverNotFound(receiverId)
        }
    }

    private fun requireSigner(): Signer = signer ?: throw WalletError.WatchOnly()

    private suspend fun syncAfterSend() {
        try {
            syncer.syncNow()
        } catch (e: Exception) {
            // the send already succeeded; the next timer tick will pick the state up
        }
    }

    fun statusInfo(): Map<String, Any> = linkedMapOf(
        "Started" to started,
        "Account" to accountId,
        "Public Key" to (publicKey?.toString() ?: "watch-only"),
        "Network" to network.name,
        "Last Block" to lastBlockHeight,
        "Sync State" to syncState.toString(),
        "Transactions Sync State" to transactionsSyncState.toString(),
        "Account Active" to isAccountActive,
        "Balance" to balance.toString(),
        "Storage Usage" to accountState.storageUsage,
        "Tokens" to ftBalances.size,
    )

    class FtTransferEstimate(
        /** Assumes all attached gas is used; the unused part is refunded. */
        val fee: FeeCalculator.Estimate,
        /** yoctoNEAR paid to register the receiver with the token contract, or null. */
        val storageDeposit: BigInteger?,
    )

    sealed class SyncState {
        class Synced : SyncState()
        class NotSynced(val error: Throwable) : SyncState()
        class Syncing(val progress: Double? = null) : SyncState()

        override fun toString(): String = when (this) {
            is Syncing -> "Syncing ${progress?.let { "${it * 100}" } ?: ""}"
            is NotSynced -> "NotSynced ${error.javaClass.simpleName} - message: ${error.message}"
            else -> this.javaClass.simpleName
        }

        override fun equals(other: Any?): Boolean {
            if (other !is SyncState) return false
            if (other.javaClass != this.javaClass) return false
            if (other is Syncing && this is Syncing) return other.progress == this.progress
            return true
        }

        override fun hashCode(): Int {
            if (this is Syncing) return Objects.hashCode(this.progress)
            return Objects.hashCode(this.javaClass.name)
        }
    }

    sealed class SyncError : Throwable() {
        class NotStarted : SyncError()
        class NoNetworkConnection : SyncError()
    }

    sealed class WalletError(message: String) : Exception(message) {
        class WatchOnly : WalletError("Watch-only wallet cannot sign")
    }

    companion object {
        const val TOKEN_NATIVE = io.horizontalsystems.nearkit.models.TransactionTag.TOKEN_NATIVE

        private const val MAX_TOKEN_DECIMALS = 64
        private const val MAX_TOKEN_TEXT = 64

        fun signer(wallet: NearWallet): Signer? = when (wallet) {
            is NearWallet.Seed -> Signer.getInstance(wallet.seed)
            is NearWallet.PrivateKey -> Signer(wallet.privateKey)
            is NearWallet.WatchOnly -> null
        }

        /** The account the kit will use for [wallet]: the chosen named account, else the implicit one. */
        fun accountId(wallet: NearWallet): String {
            val id = when (wallet) {
                is NearWallet.Seed -> wallet.accountId ?: Signer.getInstance(wallet.seed).publicKey.implicitAccountId
                is NearWallet.PrivateKey -> wallet.accountId ?: Signer(wallet.privateKey).publicKey.implicitAccountId
                is NearWallet.WatchOnly -> wallet.accountId
            }
            AccountId.validate(id)
            return id
        }

        fun publicKey(seed: ByteArray): PublicKey = Signer.getInstance(seed).publicKey

        fun getInstance(
            context: Context,
            wallet: NearWallet,
            network: Network,
            walletId: String,
            syncInterval: Long = 15,
            rpcUrls: List<URL> = network.rpcUrls,
            fastNearApiKey: String? = null,
        ): NearKit {
            val signer = signer(wallet)
            val accountId = accountId(wallet)

            val client = ApiClient.build()
            val rpcProvider = RpcProvider.create(rpcUrls, client)
            val fastNearProvider = FastNearProvider.create(network.apiUrl, network.txApiUrl, fastNearApiKey, client)
            val storage = Storage(NearDatabaseManager.getDatabase(context, network, walletId))
            val transactionSyncer = TransactionSyncer(accountId, rpcProvider, fastNearProvider, storage)
            val syncTimer = SyncTimer(syncInterval, ConnectionManager(context))
            val syncer = Syncer(accountId, syncTimer, rpcProvider, fastNearProvider, transactionSyncer, storage)
            val transactionSender = TransactionSender(accountId, rpcProvider, storage) {
                transactionSyncer.notifySaved(listOf(it))
            }

            return NearKit(accountId, network, signer, syncer, transactionSyncer, transactionSender, rpcProvider, storage)
        }

        fun clear(context: Context, network: Network, walletId: String) {
            NearDatabaseManager.clear(context, network, walletId)
        }

        /**
         * Accounts [publicKey] controls with full access: its implicit account plus named accounts
         * the index knows about, each confirmed on chain. The implicit account is always first,
         * even before it exists, because that is where funds sent to the key arrive.
         */
        suspend fun findAccounts(publicKey: PublicKey, network: Network, fastNearApiKey: String? = null): List<String> {
            val client = ApiClient.build()
            val rpcProvider = RpcProvider.create(network.rpcUrls, client)
            val fastNearProvider = FastNearProvider.create(network.apiUrl, network.txApiUrl, fastNearApiKey, client)
            val implicit = publicKey.implicitAccountId
            val named = fastNearProvider.accountIds(publicKey.toString())
                .filter { it != implicit && AccountId.isValid(it) }
                .filter { id -> rpcProvider.viewAccessKey(id, publicKey.toString())?.isFullAccess == true }
            return listOf(implicit) + named
        }

        fun isValidAccountId(accountId: String): Boolean = AccountId.isValid(accountId)

        /** Throws [IllegalArgumentException] for anything that is not a NEAR account id. */
        fun validateAccountId(accountId: String) = AccountId.validate(accountId)

        fun accountIdType(accountId: String): AccountId.Type? = AccountId.type(accountId)
    }
}
