package io.horizontalsystems.nearkit

import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.nearkit.network.ConnectionManager
import io.horizontalsystems.nearkit.network.Network
import io.horizontalsystems.nearkit.network.NoEndpointAvailable
import io.horizontalsystems.nearkit.sync.SyncTimer
import io.horizontalsystems.nearkit.sync.Syncer
import io.horizontalsystems.nearkit.sync.TransactionSyncer
import io.horizontalsystems.nearkit.transaction.TransactionSender
import io.horizontalsystems.nearkit.transaction.TransactionSender.SendError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigInteger

/** A token contract credits any well-formed id, so the kit checks the receiver before a token send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FtReceiverTest {

    private val near = FakeNear()
    private val account = "alice.near"
    private val usdt = "usdt.tether-token.near"

    private val kit = FakeNear.storage().let { storage ->
        val transactionSyncer = TransactionSyncer(account, near.rpcProvider, near.fastNearProvider, storage)
        val syncer = Syncer(account, SyncTimer(15, ConnectionManager(ApplicationProvider.getApplicationContext())),
            near.rpcProvider, near.fastNearProvider, transactionSyncer, storage)
        val sender = TransactionSender(account, near.rpcProvider, storage)
        NearKit(account, Network.MainNet, null, syncer, transactionSyncer, sender, near.rpcProvider, storage)
    }

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun estimate(receiverId: String) = runBlocking { kit.estimateFtTransfer(usdt, receiverId, BigInteger.TEN) }

    @Test
    fun tokenContractItselfIsRefused() {
        assertThrows(SendError.ReceiverIsTokenContract::class.java) { estimate(usdt) }
        assertEquals(emptyList<Any>(), near.requests)
    }

    @Test
    fun missingNamedReceiverIsRefused() {
        near.answer("query", FakeNear.error("UNKNOWN_ACCOUNT"))
        assertThrows(SendError.ReceiverNotFound::class.java) { estimate("alise.near") }
    }

    @Test
    fun receiverThatCannotBeCheckedIsRefused() {
        near.answer("query", IOException("down"), IOException("down"))
        assertThrows(NoEndpointAvailable::class.java) { estimate("alise.near") }
    }
}
