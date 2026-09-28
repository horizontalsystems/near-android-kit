package io.horizontalsystems.nearkit.transaction

import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.FakeNear
import io.horizontalsystems.nearkit.database.Storage
import io.horizontalsystems.nearkit.network.NoEndpointAvailable
import io.horizontalsystems.nearkit.network.RpcError
import io.horizontalsystems.nearkit.transaction.TransactionSender.SendError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigInteger

/**
 * A send is only reported as failed when it certainly did not reach the chain; anything
 * uncertain stays recorded as pending, so the wallet does not invite a second send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransactionSenderTest {

    private val near = FakeNear()
    private lateinit var storage: Storage
    private lateinit var sender: TransactionSender

    private val signer = Signer.fromSecretKey(
        JsonParser.parseString(javaClass.classLoader!!.getResource("near-js-vectors.json")!!.readText())
            .asJsonObject.getAsJsonArray("keys")[0].asJsonObject["secretKey"].asString
    )
    private val accountId = signer.publicKey.implicitAccountId
    private val signed = signer.sign(
        Transaction(accountId, signer.publicKey, BigInteger.ONE, "bob.near", ByteArray(32), listOf(Action.Transfer(BigInteger.TEN)))
    )

    private val included = FakeNear.result("""{"final_execution_status":"INCLUDED"}""")
    private val invalidNonce = FakeNear.error("INVALID_TRANSACTION", """{"TxExecutionError":{"InvalidTxError":{"InvalidNonce":{"tx_nonce":1,"ak_nonce":1}}}}""")
    private val notEnoughBalance = FakeNear.error("INVALID_TRANSACTION", """{"TxExecutionError":{"InvalidTxError":{"NotEnoughBalance":{}}}}""")

    @Before
    fun setUp() {
        storage = FakeNear.storage()
        sender = TransactionSender(accountId, near.rpcProvider, storage)
    }

    @After
    fun noUnexpectedRequests() {
        assertEquals(emptyList<String>(), near.unexpected)
    }

    private fun submit() = runBlocking { sender.submit(signed, referenceHeight = 100) }

    private val stored get() = storage.getTransaction(signed.hash)

    @Test
    fun recordsAcceptedSend() {
        near.answer("send_tx", included)
        assertEquals(signed.hash, submit().hash)
        assertTrue(stored!!.isPending)
    }

    @Test
    fun duplicateRefusedAfterDroppedConnectionIsNotAFailure() {
        // the first node included it but the connection dropped; the second refuses the used nonce
        near.answer("send_tx", IOException("connection reset"), invalidNonce)
        near.answer("EXPERIMENTAL_tx_status", included)
        assertEquals(signed.hash, submit().hash)
        assertTrue(stored!!.isPending)
    }

    @Test
    fun rejectionOfUnknownTransactionFails() {
        near.answer("send_tx", notEnoughBalance)
        near.answer("EXPERIMENTAL_tx_status", FakeNear.error("UNKNOWN_TRANSACTION"))
        assertThrows(SendError.Rejected::class.java) { submit() }
        assertNull(stored)
    }

    @Test
    fun rejectionThatCannotBeCheckedKeepsRecord() {
        near.answer("send_tx", notEnoughBalance)
        near.answer("EXPERIMENTAL_tx_status", IOException("down"), IOException("down"))
        assertThrows(SendError.Rejected::class.java) { submit() }
        assertTrue(stored!!.isPending)
    }

    @Test
    fun unexpectedNodeErrorKeepsRecord() {
        near.answer("send_tx", FakeNear.error("SOMETHING_NEW"))
        assertThrows(RpcError::class.java) { submit() }
        assertTrue(stored!!.isPending)
    }

    @Test
    fun unreachableNodesKeepRecord() {
        near.answer("send_tx", IOException("down"), IOException("down"))
        assertThrows(NoEndpointAvailable::class.java) { submit() }
        assertTrue(stored!!.isPending)
    }
}
