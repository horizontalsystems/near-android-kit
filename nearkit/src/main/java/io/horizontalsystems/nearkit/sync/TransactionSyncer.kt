package io.horizontalsystems.nearkit.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.horizontalsystems.nearkit.NearKit.SyncError
import io.horizontalsystems.nearkit.NearKit.SyncState
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.network.AccountTxRow
import io.horizontalsystems.nearkit.network.FastNearProvider
import io.horizontalsystems.nearkit.network.NetworkErrors
import io.horizontalsystems.nearkit.network.RpcProvider
import io.horizontalsystems.nearkit.network.getAsJsonObjectOrNull
import io.horizontalsystems.nearkit.network.optString
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
 * account's first transaction. Pending transactions are resolved from the RPC, because the index
 * lags a few seconds behind the chain and serves transactions before they finish executing.
 *
 * History is optional to the rest of the kit: a failure here sets only this syncer's state, and
 * a rate limit from the index pauses history syncs instead of retrying into it.
 */
internal class TransactionSyncer(
    private val accountId: String,
    private val rpcProvider: RpcProvider,
    private val fastNearProvider: FastNearProvider,
    private val storage: Storage,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
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

    /** Reports transactions saved outside a sync, such as a send's pending record. */
    fun notifySaved(transactions: List<Transaction>) {
        _transactionsFlow.tryEmit(transactions)
    }

    fun setNotStarted() {
        syncState = SyncState.NotSynced(SyncError.NotStarted())
    }

    fun setNotSynced(error: Throwable) {
        syncState = SyncState.NotSynced(error)
    }

    private var consecutiveFailures = 0
    private var backoffUntilMillis = 0L
    private var backoffSeconds = 0L

    /** Never throws except on cancellation; failures are reported through [syncState]. */
    suspend fun sync(blockHeight: Long) {
        val state = storage.getTransactionSyncState()
        val initialSyncDone = state != null && state.initialSyncDone
        if (!initialSyncDone) {
            syncState = SyncState.Syncing()
        }

        try {
            // the node still resolves sends while the index is rate limited
            val indexPaused = currentTimeMillis() < backoffUntilMillis
            // emitted on their own: a send resolved over the RPC must show even when the index fails next
            emit(resolvePending(blockHeight, useIndex = !indexPaused))
            if (indexPaused) return
            emit(if (state == null || !initialSyncDone) initialSync() else incrementalSync(state))

            consecutiveFailures = 0
            backoffSeconds = 0
            syncState = SyncState.Synced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            consecutiveFailures++
            if (NetworkErrors.isRateLimited(e)) {
                backoffSeconds = NetworkErrors.retryAfterSeconds(e)
                    ?: (backoffSeconds * 2).coerceIn(MIN_BACKOFF_SECONDS, MAX_BACKOFF_SECONDS)
                backoffUntilMillis = currentTimeMillis() + backoffSeconds * 1000
            }
            logger.info("Transaction sync failed: ${e.message}")
            // after a successful sync a blip keeps the synced state; anything else is reported
            val tolerate = syncState is SyncState.Synced && NetworkErrors.isTransient(e) && consecutiveFailures < MAX_TOLERATED_FAILURES
            if (!tolerate) {
                syncState = SyncState.NotSynced(e)
            }
        }
    }

    private fun emit(transactions: List<Transaction>) {
        if (transactions.isNotEmpty()) {
            _transactionsFlow.tryEmit(transactions)
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
        // The index files a transaction under the block it was submitted in, but adds the row for
        // an account only when a receipt touching that account executes, which can be many blocks
        // later. Rows below the last stored height still arrive, so each sync looks back a window;
        // stored hashes are not fetched again.
        val fromHeight = (state.lastSyncedBlockHeight - REINDEX_WINDOW_BLOCKS).coerceAtLeast(0)
        do {
            val page = fastNearProvider.accountTransactions(
                accountId,
                fromBlockHeight = fromHeight,
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

        val converted = fetch(wanted.map { it.hash }) { timestamps[it] ?: 0 }
        storage.saveTransactions(converted)
        return converted.map { it.first }
    }

    /** Transactions for [hashes] from the index; hashes the index does not have are left out. */
    private suspend fun fetch(hashes: List<String>, fallbackTimestamp: (String) -> Long): List<Pair<Transaction, Set<String>>> {
        val result = mutableListOf<Pair<Transaction, Set<String>>>()
        for (chunk in hashes.chunked(FastNearProvider.MAX_HASHES_PER_REQUEST)) {
            result += mostComplete(fastNearProvider.transactions(chunk)).mapNotNull { (hash, item) ->
                try {
                    TransactionConverter.convert(item, accountId, fallbackTimestamp(hash))
                } catch (e: Exception) {
                    logger.warning("Skipping unreadable transaction: ${e.message}")
                    null
                }
            }
        }
        return result
    }

    /**
     * One item per hash. While a transaction is executing the index can return several snapshots
     * of it in one response, in no particular order; the one with the most receipts is the latest.
     */
    private fun mostComplete(items: List<JsonObject>): List<Pair<String, JsonObject>> =
        items.mapNotNull { item -> item.getAsJsonObjectOrNull("transaction")?.optString("hash")?.let { it to item } }
            .groupBy({ it.first }, { it.second })
            .map { (hash, snapshots) -> hash to snapshots.maxBy { (it.get("receipts") as? JsonArray)?.size() ?: 0 } }

    /**
     * Resolves transactions the kit submitted: executed ones are replaced by their outcome. One
     * that neither the node nor the index knows once the chain has passed its validity window is
     * marked failed. The node alone is not enough: it answers UNKNOWN_TRANSACTION for transactions
     * older than its garbage collection window, and a "failed" send invites sending again.
     */
    private suspend fun resolvePending(blockHeight: Long, useIndex: Boolean): List<Transaction> {
        val resolved = mutableListOf<Transaction>()
        val unresolvedFromIndex = mutableListOf<Transaction>()
        for (pending in storage.getPendingTransactions()) {
            if (pending.blockHeight != null) unresolvedFromIndex += pending
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
                status == null && pending.expiresAfterHeight != null && blockHeight > pending.expiresAfterHeight -> {
                    if (!useIndex) continue
                    val indexed = try {
                        fastNearProvider.transactions(listOf(pending.hash))
                            .firstOrNull { it.getAsJsonObjectOrNull("transaction")?.optString("hash") == pending.hash }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        continue
                    }
                    if (indexed != null) {
                        TransactionConverter.convert(indexed, accountId, pending.timestamp)
                    } else {
                        pending.copy(status = Transaction.Status.Failed, failure = EXPIRED) to TransactionConverter.tags(pending, accountId)
                    }
                }
                else -> null
            } ?: continue
            if (updated.first.isPending) continue
            storage.saveTransactions(listOf(updated))
            resolved += updated.first
            unresolvedFromIndex.remove(pending)
        }
        if (useIndex) resolved += refetchFromIndex(unresolvedFromIndex)
        return resolved
    }

    /**
     * Records the index served while they were still executing, that the node could not resolve:
     * it may have pruned them, or a failover node may not know them. The index has them by now.
     */
    private suspend fun refetchFromIndex(pending: List<Transaction>): List<Transaction> {
        if (pending.isEmpty()) return emptyList()
        val timestamps = pending.associate { it.hash to it.timestamp }
        val finished = try {
            fetch(pending.map { it.hash }) { timestamps[it] ?: 0 }.filter { !it.first.isPending }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return emptyList()
        }
        storage.saveTransactions(finished)
        return finished.map { it.first }
    }

    companion object {
        private const val PAGE_LIMIT = 100
        private const val MAX_INCREMENTAL_PAGES = 10

        /** About three minutes at 0.6 s blocks; covers the 200-block yield/resume timeout and long cross-contract flows. */
        const val REINDEX_WINDOW_BLOCKS = 300L
        private const val MAX_TOLERATED_FAILURES = 3
        private const val MIN_BACKOFF_SECONDS = 30L
        private const val MAX_BACKOFF_SECONDS = 300L
        const val EXPIRED = "expired"
    }
}
