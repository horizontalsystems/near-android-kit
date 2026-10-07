package io.horizontalsystems.nearkit.sync

import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.FakeNear
import io.horizontalsystems.nearkit.NearKit.SyncState
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.models.TransactionTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger

/**
 * How the FastNEAR history index differs from a finished-transaction feed: it serves transactions
 * while their receipts still execute, can repeat a hash within one response, adds an account's
 * row late, and rate limits keyless clients.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransactionSyncerIndexTest {

    private val near = FakeNear()
    private lateinit var storage: Storage
    private lateinit var syncer: TransactionSyncer
    private var now = 1_000_000L

    private val account = "alice.near"
    private val hash = "WithdrawTx111111111111111111111111111111111"

    @Before
    fun setUp() {
        storage = FakeNear.storage()
        syncer = TransactionSyncer(account, near.rpcProvider, near.fastNearProvider, storage) { now }
        storage.saveTransactionSyncState(TransactionSyncState(lastSyncedBlockHeight = 1_000, initialSyncDone = true, backfillResumeToken = null))
    }

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun sync() = runBlocking { syncer.sync(blockHeight = 2_000) }

    private val stored get() = storage.getTransaction(hash)!!

    private fun accountPage(vararg hashes: String) =
        """{"account_txs":[${hashes.joinToString(",") { """{"transaction_hash":"$it","tx_block_height":1001,"tx_block_timestamp":"1700000000000000000"}""" }}]}"""

    @Test
    fun transactionStillExecutingIsKeptPendingUntilTheIndexHasAllReceipts() {
        near.answer("/v0/account", accountPage(hash))
        near.answer("/v0/transactions", """{"transactions":[${withdraw(complete = false)}]}""")
        sync()
        // wrap.near returned a value, but the NEAR payout receipt has not run yet
        assertTrue(stored.isPending)
        assertEquals(emptyList<Any>(), stored.nearTransfers.filter { it.to == account })

        // the node lost it; the index has finished it
        near.answer("EXPERIMENTAL_tx_status", FakeNear.error("UNKNOWN_TRANSACTION"))
        near.answer("/v0/transactions", """{"transactions":[${withdraw(complete = true)}]}""")
        near.answer("/v0/account", accountPage(hash))
        sync()
        assertTrue(stored.isSuccess)
        assertEquals(BigInteger("5000"), stored.nearTransfers.single { it.to == account }.amount)
        // unwrapping pays NEAR out, so it shows in both histories
        assertEquals(setOf(TransactionTag.TOKEN_NATIVE, "wrap.near"), TransactionConverter.tags(stored, account))
    }

    @Test
    fun repeatedHashInOneResponseKeepsTheMostCompleteSnapshot() {
        near.answer("/v0/account", accountPage(hash))
        near.answer("/v0/transactions", """{"transactions":[${withdraw(complete = true)},${withdraw(complete = false)}]}""")
        sync()
        assertTrue(stored.isSuccess)
        assertEquals(1, stored.nearTransfers.count { it.to == account })
    }

    @Test
    fun incrementalSyncLooksBackForRowsAddedLate() {
        near.answer("/v0/account", accountPage())
        sync()
        val body = JsonParser.parseString(near.requests.single { it.first == "/v0/account" }.second).asJsonObject
        assertEquals(1_000 - TransactionSyncer.REINDEX_WINDOW_BLOCKS, body["from_tx_block_height"].asLong)
    }

    @Test
    fun rateLimitPausesTheIndexAndKeepsHistorySynced() {
        near.answer("/v0/account", accountPage())
        sync()
        assertTrue(syncer.syncState is SyncState.Synced)

        near.answer("/v0/account", FakeNear.Http(429, headers = mapOf("Retry-After" to "60")))
        sync()
        assertTrue(syncer.syncState is SyncState.Synced)

        // inside the pause no request reaches the index
        now += 30_000
        val before = near.requests.size
        sync()
        assertEquals(before, near.requests.size)

        now += 31_000
        near.answer("/v0/account", accountPage())
        sync()
        assertEquals(before + 1, near.requests.size)
        assertTrue(syncer.syncState is SyncState.Synced)
    }

    @Test
    fun repeatedRateLimitIsReported() {
        near.answer("/v0/account", accountPage())
        sync()
        repeat(3) {
            near.answer("/v0/account", FakeNear.Http(429))
            sync()
            now += 10 * 60_000
        }
        assertTrue(syncer.syncState is SyncState.NotSynced)
    }

    /** alice.near unwraps 5000 yocto: wrap.near logs the withdrawal, then a receipt pays the NEAR out. */
    private fun withdraw(complete: Boolean): String {
        val call = receipt(
            id = "R1", predecessor = account, receiver = "wrap.near",
            actions = """[{"FunctionCall":{"method_name":"near_withdraw","args":"e30=","gas":10000000000000,"deposit":"1"}}]""",
            createdReceipts = listOf("R2"), logs = listOf("Withdraw 5000 NEAR from $account"),
        )
        val payout = receipt(id = "R2", predecessor = "wrap.near", receiver = account, actions = """[{"Transfer":{"deposit":"5000"}}]""")
        return """
            {"transaction":{"hash":"$hash","signer_id":"$account","receiver_id":"wrap.near",
              "actions":[{"FunctionCall":{"method_name":"near_withdraw","args":"e30=","gas":10000000000000,"deposit":"1"}}]},
             "execution_outcome":{"block_height":1001,"block_timestamp":1700000000000000000,"id":"$hash",
              "outcome":{"logs":[],"receipt_ids":["R1"],"tokens_burnt":"100","executor_id":"$account","status":{"SuccessReceiptId":"R1"}}},
             "receipts":[${listOfNotNull(call, payout.takeIf { complete }).joinToString(",")}]}
        """.trimIndent()
    }

    private fun receipt(id: String, predecessor: String, receiver: String, actions: String, createdReceipts: List<String> = emptyList(), logs: List<String> = emptyList()) = """
        {"receipt":{"receipt_id":"$id","predecessor_id":"$predecessor","receiver_id":"$receiver","receipt":{"Action":{"actions":$actions}}},
         "execution_outcome":{"id":"$id","block_height":1002,"outcome":{"logs":[${logs.joinToString(",") { "\"$it\"" }}],
          "receipt_ids":[${createdReceipts.joinToString(",") { "\"$it\"" }}],"tokens_burnt":"10","executor_id":"$receiver","status":{"SuccessValue":""}}}}
    """.trimIndent()
}
