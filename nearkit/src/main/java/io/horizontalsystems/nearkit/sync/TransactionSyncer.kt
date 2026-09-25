package io.horizontalsystems.nearkit.sync

import io.horizontalsystems.nearkit.NearKit.SyncError
import io.horizontalsystems.nearkit.NearKit.SyncState
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.network.AccountTxRow
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.RpcProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.logging.Logger

/**
 * Keeps the local transaction table in step with the FastNEAR history index.
 *
 * The first sync stores the newest page; each later sync fetches what is newer than the last
 * stored height and then walks one more page back through older history until it reaches the
 * account's first transaction. Pending transactions the kit submitted are resolved from the RPC,
 * because the index lags a few seconds behind the chain.
 */
internal class TransactionSyncer(
    private val accountId: String,
    private val rpcProvider: RpcProvider,
    private val fastNearProvider: FastNearProvider,
    private val storage: Storage,
) {
    private val logger = Logger.getLogger("NearKit")

    var syncState: SyncState = SyncState.NotSynced(SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                _syncStateFlow.update { value }
            }
        }

    private val _syncStateFlow = MutableStateFlow(syncState)
    val syncStateFlow: StateFlow<SyncState> = _syncStateFlow

    private val _transactionsFlow = MutableSharedFlow<List<Transaction>>(extraBufferCapacity = 16)
    val transactionsFlow: SharedFlow<List<Transaction>> = _transactionsFlow

    fun setNotStarted() {
        syncState = SyncState.NotSynced(SyncError.NotStarted())
    }

    fun setNotSynced(error: Throwable) {
        syncState = SyncState.NotSynced(error)
    }

    suspend fun sync(blockHeight: Long) {
        val state = storage.getTransactionSyncState()
        if (state == null || !state.initialSyncDone) {
            syncState = SyncState.Syncing()
        }

        try {
            val changed = mutableListOf<Transaction>()
            changed += resolvePending(blockHeight)
            changed += if (state == null || !state.initialSyncDone) initialSync() else incrementalSync(state)

            if (changed.isNotEmpty()) {
                _transactionsFlow.tryEmit(changed)
            }
            syncState = SyncState.Synced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The account syncer owns the failure policy and calls setNotSynced when the failure
            // is real. Only a first sync that never completed is reported here.
            if (state == null || !state.initialSyncDone) {
                syncState = SyncState.NotSynced(e)
            }
            throw e
        }
    }

    private suspend fun initialSync(): List<Transaction> {
        val page = fastNearProvider.accountTransactions(accountId, newestFirst = true, limit = PAGE_LIMIT)
        val stored = store(page.rows)
        storage.saveTransactionSyncState(
            TransactionSyncState(
                lastSyncedBlockHeight = page.rows.maxOfOrNull { it.blockHeight } ?: 0,
                initialSyncDone = true,
                backfillResumeToken = page.resumeToken.takeIf { page.rows.size >= PAGE_LIMIT },
            )
        )
        return stored
    }

    private suspend fun incrementalSync(state: TransactionSyncState): List<Transaction> {
        val stored = mutableListOf<Transaction>()
        var lastHeight = state.lastSyncedBlockHeight
        var resumeToken: String? = null
        var pages = 0
        do {
            // inclusive bound: a block can hold several of the account's transactions, and the
            // index may have stored only some of them when the previous sync ran
            val page = fastNearProvider.accountTransactions(
                accountId,
                fromBlockHeight = state.lastSyncedBlockHeight,
                newestFirst = false,
                resumeToken = resumeToken,
                limit = PAGE_LIMIT,
            )
            stored += store(page.rows)
            lastHeight = maxOf(lastHeight, page.rows.maxOfOrNull { it.blockHeight } ?: lastHeight)
            resumeToken = page.resumeToken.takeIf { page.rows.size >= PAGE_LIMIT }
            pages++
        } while (resumeToken != null && pages < MAX_INCREMENTAL_PAGES)

        var backfillToken = state.backfillResumeToken
        if (backfillToken != null) {
            val page = fastNearProvider.accountTransactions(accountId, newestFirst = true, resumeToken = backfillToken, limit = PAGE_LIMIT)
            stored += store(page.rows)
            backfillToken = page.resumeToken.takeIf { page.rows.size >= PAGE_LIMIT }
        }

        storage.saveTransactionSyncState(
            TransactionSyncState(lastSyncedBlockHeight = lastHeight, initialSyncDone = true, backfillResumeToken = backfillToken)
        )
        return stored
    }

    /** Fetches and stores the rows not already stored from the index. Returns the new or updated records. */
    private suspend fun store(rows: List<AccountTxRow>): List<Transaction> {
        if (rows.isEmpty()) return emptyList()
        val indexed = storage.indexedHashes(rows.map { it.hash })
        val wanted = rows.filter { it.hash !in indexed }.distinctBy { it.hash }
        val timestamps = wanted.associate { it.hash to it.blockTimestampNanos / 1_000_000_000 }

        val result = mutableListOf<Transaction>()
        for (chunk in wanted.chunked(FastNearProvider.MAX_HASHES_PER_REQUEST)) {
            val items = fastNearProvider.transactions(chunk.map { it.hash })
            val converted = items.mapNotNull { item ->
                try {
                    val hash = item.getAsJsonObject("transaction")?.get("hash")?.asString
                    TransactionConverter.convert(item, accountId, timestamps[hash] ?: 0)
                } catch (e: Exception) {
                    logger.warning("Skipping unreadable transaction: ${e.message}")
                    null
                }
            }
            storage.saveTransactions(converted)
            result += converted.map { it.first }
        }
        return result
    }

    /**
     * Resolves transactions the kit submitted: executed ones are replaced by their outcome; one
     * that no node knows once the chain has passed its validity window is marked failed.
     */
    private suspend fun resolvePending(blockHeight: Long): List<Transaction> {
        val resolved = mutableListOf<Transaction>()
        for (pending in storage.getPendingTransactions()) {
            val status = try {
                rpcProvider.transactionStatus(pending.hash, pending.signerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                continue
            }
            val raw = status?.raw
            val updated = when {
                status != null && status.isExecuted && raw != null -> {
                    val (tx, tags) = TransactionConverter.convert(raw, accountId, pending.timestamp)
                    // RPC outcomes carry no block time; keep the submission time until the index has it
                    tx.copy(timestamp = pending.timestamp) to tags
                }
                status == null && pending.expiresAfterHeight != null && blockHeight > pending.expiresAfterHeight ->
                    pending.copy(status = Transaction.Status.Failed, failure = EXPIRED) to TransactionConverter.tags(pending, accountId)
                else -> null
            } ?: continue
            if (updated.first.isPending) continue
            storage.saveTransactions(listOf(updated))
            resolved += updated.first
        }
        return resolved
    }

    companion object {
        private const val PAGE_LIMIT = 100
        private const val MAX_INCREMENTAL_PAGES = 10
        const val EXPIRED = "expired"
    }
}
