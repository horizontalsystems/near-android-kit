package io.horizontalsystems.nearkit.sync

import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.nearkit.FakeNear
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.models.FtBalance
import io.horizontalsystems.nearkit.models.TransactionSyncState
import io.horizontalsystems.nearkit.network.ConnectionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FtBalanceSyncTest {

    private val near = FakeNear()
    private lateinit var storage: Storage
    private lateinit var syncer: Syncer

    private val account = "c".repeat(64)
    private val usdt = "usdt.tether-token.near"
    private val block = FakeNear.result("""{"header":{"height":1000,"hash":"h","timestamp_nanosec":"1","gas_price":"100000000"}}""")

    @Before
    fun setUp() {
        storage = FakeNear.storage()
        storage.saveTransactionSyncState(TransactionSyncState(lastSyncedBlockHeight = 1, initialSyncDone = true, backfillResumeToken = null))
        val transactionSyncer = TransactionSyncer(account, near.rpcProvider, near.fastNearProvider, storage)
        val timer = SyncTimer(15, ConnectionManager(ApplicationProvider.getApplicationContext()))
        syncer = Syncer(account, timer, near.rpcProvider, near.fastNearProvider, transactionSyncer, storage)
        near.answer("/v0/account", """{"account_txs":[]}""")
    }

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun sync() = runBlocking { syncer.syncNow() }

    @Test
    fun tokensShowBeforeTheAccountHasAnyNear() {
        // an exchange paid the storage deposit and sent USDT to a new implicit address
        near.answer("block", block)
        near.answer("query", FakeNear.error("UNKNOWN_ACCOUNT"))
        near.answer("/v1/account/$account/ft", """{"tokens":[{"contract_id":"$usdt","balance":"100000000"}]}""")
        sync()
        assertEquals(listOf(FtBalance(usdt, BigInteger("100000000"))), syncer.ftBalancesFlow.value)
    }

    @Test
    fun failedReadKeepsAWatchedTokenTheIndexDoesNotListYet() {
        storage.replaceFtBalances(listOf(FtBalance(usdt, BigInteger.TEN)))
        syncer = Syncer(account, SyncTimer(15, ConnectionManager(ApplicationProvider.getApplicationContext())),
            near.rpcProvider, near.fastNearProvider, TransactionSyncer(account, near.rpcProvider, near.fastNearProvider, storage), storage)
        syncer.watchedTokens = setOf(usdt)

        near.answer("block", block)
        near.answer("query", FakeNear.error("UNKNOWN_ACCOUNT"), IOException("down"), IOException("down"))
        near.answer("/v1/account/$account/ft", """{"tokens":[]}""")
        sync()
        assertEquals(listOf(FtBalance(usdt, BigInteger.TEN)), syncer.ftBalancesFlow.value)
    }
}
