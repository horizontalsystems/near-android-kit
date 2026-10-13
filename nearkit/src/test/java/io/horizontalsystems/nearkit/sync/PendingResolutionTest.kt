package io.horizontalsystems.nearkit.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.FakeNear
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionSyncState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A node's outcome replaces the kit's own pending record only when it describes the transaction signed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingResolutionTest {

    private val near = FakeNear()
    private lateinit var storage: Storage
    private lateinit var syncer: TransactionSyncer
    private var resource = "rpc-tx-status.json"

    private fun outcome() = JsonParser.parseString(javaClass.classLoader!!.getResource(resource)!!.readText()).asJsonObject
    private val hash get() = outcome().getAsJsonObject("transaction")["hash"].asString
    private val accountId get() = outcome().getAsJsonObject("transaction")["signer_id"].asString
    private val lookAlike = "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee999"

    @Before
    fun setUp() {
        near.answer("/v0/account", """{"account_txs":[]}""")
    }

    /** Saves the record the kit saved at send time for [resource]: what it signed, before any outcome. */
    private fun sent(resource: String = "rpc-tx-status.json") {
        this.resource = resource
        storage = FakeNear.storage()
        syncer = TransactionSyncer(accountId, near.rpcProvider, near.fastNearProvider, storage)
        storage.saveTransactionSyncState(TransactionSyncState(lastSyncedBlockHeight = 1, initialSyncDone = true, backfillResumeToken = null))
        val (signed, tags) = TransactionConverter.convert(outcome(), accountId, 1)
        val pending = signed.copy(
            blockHeight = null, status = Transaction.Status.Pending, fee = null, failure = null,
            expiresAfterHeight = 100_000, nearTransfers = signed.nearTransfers.filter { it.from == accountId },
        )
        storage.saveTransactions(listOf(pending to tags))
    }

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun sync(outcome: JsonObject) {
        near.answer("EXPERIMENTAL_tx_status", FakeNear.result(outcome.toString()))
        runBlocking { syncer.sync(blockHeight = 1_000) }
    }

    private val stored get() = storage.getTransaction(hash)!!

    @Test
    fun outcomeOfTheSignedTransactionResolvesIt() {
        sent()
        sync(outcome())
        assertTrue(stored.isSuccess)
    }

    @Test
    fun outcomeWithAnotherReceiverIsIgnored() {
        sent()
        sync(outcome().apply { getAsJsonObject("transaction").addProperty("receiver_id", lookAlike) })
        assertTrue(stored.isPending)
    }

    @Test
    fun outcomePayingSomeoneElseIsIgnored() {
        sent()
        sync(outcome().apply {
            getAsJsonArray("receipts").map { it.asJsonObject }
                .filter { it["predecessor_id"].asString == accountId }
                .forEach { it.addProperty("receiver_id", lookAlike) }
        })
        assertTrue(stored.isPending)
    }

    @Test
    fun outcomeSendingMoreThanSignedIsIgnored() {
        sent()
        sync(outcome().apply {
            getAsJsonArray("receipts").map { it.asJsonObject }
                .filter { it["predecessor_id"].asString == accountId }
                .flatMap { it.getAsJsonObject("receipt").getAsJsonObject("Action").getAsJsonArray("actions") }
                .forEach { it.asJsonObject.getAsJsonObject("Transfer")?.addProperty("deposit", "947680490000000000000000") }
        })
        assertTrue(stored.isPending)
    }

    @Test
    fun outcomeRepeatingATokenTransferIsIgnored() {
        sent("rpc-legacy-ft-transfer.json")
        sync(outcome().apply {
            val logs = getAsJsonArray("receipts_outcome")[0].asJsonObject.getAsJsonObject("outcome").getAsJsonArray("logs")
            logs.add(logs[0].deepCopy())
        })
        assertTrue(stored.isPending)
    }

    @Test
    fun outcomeOfTheSignedTokenTransferResolvesIt() {
        sent("rpc-legacy-ft-transfer.json")
        sync(outcome())
        assertTrue(stored.isSuccess)
    }
}
