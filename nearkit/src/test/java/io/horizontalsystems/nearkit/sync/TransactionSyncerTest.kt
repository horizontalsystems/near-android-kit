package io.horizontalsystems.nearkit.sync

import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.FakeNear
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.models.TransactionTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** A pending send past its validity window is only marked failed when the index does not have it either. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransactionSyncerTest {

    private val near = FakeNear()
    private lateinit var storage: Storage
    private lateinit var syncer: TransactionSyncer

    private val indexedItem = JsonParser.parseString(javaClass.classLoader!!.getResource("fastnear-transactions.json")!!.readText())
        .asJsonObject.getAsJsonArray("transactions")[0].asJsonObject
    private val hash = indexedItem.getAsJsonObject("transaction")["hash"].asString
    private val accountId = indexedItem.getAsJsonObject("transaction")["signer_id"].asString

    @Before
    fun setUp() {
        storage = FakeNear.storage()
        syncer = TransactionSyncer(accountId, near.rpcProvider, near.fastNearProvider, storage)
        storage.saveTransactionSyncState(TransactionSyncState(lastSyncedBlockHeight = 1, initialSyncDone = true, backfillResumeToken = null))
        val pending = Transaction(
            hash = hash, blockHeight = null, timestamp = 1, signerId = accountId, receiverId = "usdt.tether-token.near",
            actions = emptyList(), nearTransfers = emptyList(), ftTransfers = emptyList(), fee = null,
            status = Transaction.Status.Pending, failure = null, expiresAfterHeight = 100,
        )
        storage.saveTransactions(listOf(pending to setOf(TransactionTag.TOKEN_NATIVE)))

        // the node has forgotten it, and the history index has nothing new
        near.answer("EXPERIMENTAL_tx_status", FakeNear.error("UNKNOWN_TRANSACTION"))
        near.answer("/v0/account", """{"account_txs":[]}""")
    }

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun sync() = runBlocking { syncer.sync(blockHeight = 1_000) }

    private val stored get() = storage.getTransaction(hash)!!

    @Test
    fun indexedTransactionIsNotMarkedExpired() {
        near.answer("/v0/transactions", """{"transactions":[$indexedItem]}""")
        sync()
        assertNull(stored.failure)
        assertNotNull(stored.blockHeight)
        assertTrue(stored.isSuccess)
    }

    @Test
    fun transactionUnknownEverywhereIsMarkedExpired() {
        near.answer("/v0/transactions", """{"transactions":[]}""")
        sync()
        assertTrue(stored.isFailed)
        assertEquals(TransactionSyncer.EXPIRED, stored.failure)
    }

    @Test
    fun staysPendingWhileIndexIsUnreachable() {
        near.answer("/v0/transactions", IOException("down"))
        sync()
        assertTrue(stored.isPending)
    }
}
