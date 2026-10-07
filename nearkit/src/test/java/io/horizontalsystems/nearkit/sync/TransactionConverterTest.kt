package io.horizontalsystems.nearkit.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.nearkit.crypto.PublicKey
import io.horizontalsystems.nearkit.models.FtTransfer
import io.horizontalsystems.nearkit.models.NearTransfer
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionTag
import io.horizontalsystems.nearkit.transaction.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import io.horizontalsystems.nearkit.transaction.Transaction as UnsignedTransaction

/** Fixtures are mainnet transactions as FastNEAR `/v0/transactions` and RPC `EXPERIMENTAL_tx_status` returned them. */
class TransactionConverterTest {

    private val fastNear: List<JsonObject> = JsonParser.parseString(resource("fastnear-transactions.json"))
        .asJsonObject.getAsJsonArray("transactions").map { it.asJsonObject }

    private fun fastNearTx(hashPrefix: String) =
        fastNear.first { it.getAsJsonObject("transaction")["hash"].asString.startsWith(hashPrefix) }

    @Test
    fun incomingNearTransfer() {
        val (tx, tags) = TransactionConverter.convert(fastNearTx("BiuX"), RECEIVER, 0)

        assertEquals(Transaction.Status.Success, tx.status)
        assertEquals(217084526L, tx.blockHeight)
        assertEquals(1790273822L, tx.timestamp)
        assertEquals(listOf(NearTransfer(SENDER, RECEIVER, BigInteger("847680490000000000000000"), NearTransfer.Kind.Transfer, true)), tx.nearTransfers)
        assertEquals(BigInteger("847680490000000000000000"), tx.nearNetChange(RECEIVER))
        // the gas refund from `system` to the sender is not a transfer
        assertTrue(tx.nearTransfers.none { it.from == "system" })
        assertEquals(setOf(TransactionTag.TOKEN_NATIVE), tags)
    }

    @Test
    fun rpcShapeGivesTheSameRecordAsFastNear() {
        val rpc = JsonParser.parseString(resource("rpc-tx-status.json")).asJsonObject
        val (fromRpc, rpcTags) = TransactionConverter.convert(rpc, RECEIVER, 42)
        val (fromIndex, indexTags) = TransactionConverter.convert(fastNearTx("BiuX"), RECEIVER, 0)

        assertEquals(fromIndex.status, fromRpc.status)
        assertEquals(fromIndex.nearTransfers, fromRpc.nearTransfers)
        assertEquals(fromIndex.fee, fromRpc.fee)
        assertEquals(fromIndex.actions, fromRpc.actions)
        assertEquals(indexTags, rpcTags)
        // RPC outcomes carry no block time, so the fallback is used
        assertEquals(42L, fromRpc.timestamp)
    }

    @Test
    fun failedTransactionKeepsFailureAndFee() {
        val (tx, tags) = TransactionConverter.convert(fastNearTx("Hdg"), RECEIVER, 0)

        assertEquals(Transaction.Status.Failed, tx.status)
        assertTrue(tx.failure!!.contains("LackBalanceForState"))
        assertEquals("AddKey", tx.actions.single().type)
        assertTrue(tx.fee!!.signum() > 0)
        assertEquals(setOf(TransactionTag.TOKEN_NATIVE), tags)
    }

    @Test
    fun outgoingTokenTransferFromEventLog() {
        val (tx, tags) = TransactionConverter.convert(fastNearTx("JDtz"), FT_SENDER, 0)

        assertEquals(Transaction.Status.Success, tx.status)
        assertEquals(listOf(FtTransfer(USDT, FT_SENDER, FT_RECEIVER, BigInteger("5000000000"), null)), tx.ftTransfers)
        // the 1 yocto proof deposit is a NEAR movement to the token contract
        assertEquals(listOf(NearTransfer(FT_SENDER, USDT, BigInteger.ONE, NearTransfer.Kind.FunctionCallDeposit, true, "ft_transfer")), tx.nearTransfers)
        assertEquals(BigInteger("31368617837000000000").add(BigInteger("208972821341200000000")), tx.fee)
        assertEquals("ft_transfer", tx.actions.single().methodName)
        assertTrue(tx.actions.single().args!!.contains(FT_RECEIVER))
        // a token send belongs to the token's history only, despite the gas and the proof deposit
        assertEquals(setOf(USDT), tags)
    }

    @Test
    fun incomingTokenTransferIsTaggedWithTheTokenOnly() {
        val (tx, tags) = TransactionConverter.convert(fastNearTx("JDtz"), FT_RECEIVER, 0)

        assertEquals(FT_RECEIVER, tx.ftTransfers.single().to)
        assertEquals(BigInteger.ZERO, tx.nearNetChange(FT_RECEIVER))
        assertEquals(setOf(USDT), tags)
    }

    @Test
    fun unrelatedAccountSeesNoMovements() {
        val (tx, tags) = TransactionConverter.convert(fastNearTx("JDtz"), "someone.near", 0)
        assertTrue(tx.ftTransfers.isEmpty())
        assertTrue(tx.nearTransfers.isEmpty())
        assertTrue(tags.isEmpty())
    }

    @Test
    fun missingOutcomeIsPending() {
        val item = fastNearTx("BiuX").deepCopy()
        item.remove("receipts")
        val (tx, _) = TransactionConverter.convert(item, RECEIVER, 7)
        assertEquals(Transaction.Status.Pending, tx.status)
        assertNull(tx.fee)
    }

    @Test
    fun pendingTokenSendPredictsTheTransfer() {
        val key = PublicKey(ByteArray(32))
        val unsigned = UnsignedTransaction(
            signerId = FT_SENDER,
            publicKey = key,
            nonce = BigInteger.ONE,
            receiverId = USDT,
            blockHash = ByteArray(32),
            actions = listOf(
                Action.FunctionCall("storage_deposit", """{"account_id":"bob.near","registration_only":true}""".toByteArray(), BigInteger.TEN, BigInteger("1250000000000000000000")),
                Action.FunctionCall("ft_transfer", """{"receiver_id":"bob.near","amount":"7","memo":"hi"}""".toByteArray(), BigInteger.TEN, BigInteger.ONE),
            ),
        )
        val (tx, tags) = TransactionConverter.pending(unsigned, "hash", expiresAfterHeight = 100, nowSeconds = 5)

        assertEquals(Transaction.Status.Pending, tx.status)
        assertEquals(listOf(FtTransfer(USDT, FT_SENDER, "bob.near", BigInteger("7"), "hi")), tx.ftTransfers)
        assertEquals(BigInteger("1250000000000000000001").negate(), tx.nearNetChange(FT_SENDER))
        assertEquals(100L, tx.expiresAfterHeight)
        // the receiver's storage deposit is part of the token send
        assertEquals(setOf(USDT), tags)
    }

    @Test
    fun legacyTransferLogWithMemo() {
        // wrap.testnet predates NEP-297 events and logs plain text, like wrap.near on mainnet
        val rpc = JsonParser.parseString(resource("rpc-legacy-ft-transfer.json")).asJsonObject
        val (tx, tags) = TransactionConverter.convert(rpc, TESTNET_SENDER, 0)

        assertEquals(listOf(FtTransfer(WRAP, TESTNET_SENDER, TESTNET_RECEIVER, BigInteger("100000000000000000000000"), "nearkit")), tx.ftTransfers)
        assertEquals(setOf(WRAP), tags)
    }

    @Test
    fun legacyWrapDepositIsAMint() {
        val rpc = JsonParser.parseString(resource("rpc-legacy-wrap.json")).asJsonObject
        val (tx, tags) = TransactionConverter.convert(rpc, TESTNET_SENDER, 0)

        assertEquals(listOf(FtTransfer(WRAP, null, TESTNET_SENDER, BigInteger("500000000000000000000000"), null)), tx.ftTransfers)
        // wrapping spends NEAR, so it shows in both histories
        assertEquals(setOf(TransactionTag.TOKEN_NATIVE, WRAP), tags)
        // 0.5 NEAR wrapped plus the 0.00125 NEAR storage registration, both attached as deposits
        assertEquals(BigInteger("501250000000000000000000").negate(), tx.nearNetChange(TESTNET_SENDER))
        assertEquals(listOf("storage_deposit", "near_deposit"), tx.actions.map { it.methodName })
    }

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    companion object {
        const val SENDER = "9c484fa5d2d069569ba063fc555c34e621ccd88fdbb0295fc79bad232621c5c1"
        const val RECEIVER = "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412"
        const val USDT = "usdt.tether-token.near"
        const val FT_SENDER = "closemeat6237.near"
        const val FT_RECEIVER = "28f6283e2f11a3364e374e944bbc1fd497e765dbd7fec35835501d9a592d9197"
        const val WRAP = "wrap.testnet"
        const val TESTNET_SENDER = "nearkit-1790327773094.testnet"
        const val TESTNET_RECEIVER = "9d0aa40809e3438c41c6af127b3711c18ae2698f8f4371f172c436bdd608cd1a"
    }
}
