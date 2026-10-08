package io.horizontalsystems.nearkit.sync

import io.horizontalsystems.nearkit.models.FtTransfer
import io.horizontalsystems.nearkit.models.NearTransfer
import io.horizontalsystems.nearkit.models.NearTransfer.Kind.FunctionCallDeposit
import io.horizontalsystems.nearkit.models.NearTransfer.Kind.Transfer
import io.horizontalsystems.nearkit.models.Transaction
import io.horizontalsystems.nearkit.models.TransactionTag.Companion.TOKEN_NATIVE
import io.horizontalsystems.nearkit.models.TxAction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * Shapes of mainnet transactions from the wallet's history test vectors (hash prefixes in the
 * test names), reduced to the movements of [ME].
 */
class NearMovedTagsTest {

    @Test
    fun storageDepositRefund_CXahHFrS() {
        // registering itself on wrap.near: 0.01 NEAR attached, 0.00875 sent back
        val tx = tx(
            signer = ME, receiver = WRAP,
            actions = listOf(call("storage_deposit", near("0.01"))),
            near = listOf(
                NearTransfer(ME, WRAP, near("0.01"), FunctionCallDeposit, true, "storage_deposit"),
                NearTransfer(WRAP, ME, near("0.00875"), Transfer, true),
            ),
        )
        // a registration on its own is a NEAR payment, and its refund is netted
        assertEquals(near("0.00125").negate(), tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun registrationBundledWithTokenSend_F2vmbn2C() {
        val tx = tx(
            signer = ME, receiver = USDT,
            actions = listOf(call("storage_deposit", near("0.00125")), call("ft_transfer", BigInteger.ONE)),
            near = listOf(
                NearTransfer(ME, USDT, near("0.00125"), FunctionCallDeposit, true, "storage_deposit"),
                NearTransfer(ME, USDT, BigInteger.ONE, FunctionCallDeposit, true, "ft_transfer"),
            ),
            ft = listOf(FtTransfer(USDT, ME, OTHER, BigInteger("2001000000"), null)),
        )
        assertEquals(BigInteger.ZERO, tx.nearMoved(ME))
        assertEquals(setOf(USDT), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun bundledRegistrationFullyRefunded_btg9XFgo() {
        // the receiver was already registered, so the whole deposit comes back
        val tx = tx(
            signer = ME, receiver = TOKEN,
            actions = listOf(call("storage_deposit", near("0.01")), call("ft_transfer", BigInteger.ONE)),
            near = listOf(
                NearTransfer(ME, TOKEN, near("0.01"), FunctionCallDeposit, true, "storage_deposit"),
                NearTransfer(ME, TOKEN, BigInteger.ONE, FunctionCallDeposit, true, "ft_transfer"),
                NearTransfer(TOKEN, ME, near("0.01"), Transfer, true),
            ),
            ft = listOf(FtTransfer(TOKEN, ME, OTHER, BigInteger.TEN, null)),
        )
        assertEquals(BigInteger.ZERO, tx.nearMoved(ME))
        assertEquals(setOf(TOKEN), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun failedIncomingTransfer_5vg9o62B() {
        // someone else's transaction whose transfer to ME failed and was refunded to them
        val tx = tx(
            signer = OTHER, receiver = ME,
            actions = listOf(TxAction("Transfer", deposit = near("0.001"))),
            near = listOf(NearTransfer(OTHER, ME, near("0.001"), Transfer, false)),
            status = Transaction.Status.Failed,
        )
        assertEquals(BigInteger.ZERO, tx.nearMoved(ME))
        assertEquals(emptySet<String>(), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun failedOwnSendStaysInNearHistory() {
        val tx = tx(
            signer = ME, receiver = OTHER,
            actions = listOf(TxAction("Transfer", deposit = near("1"))),
            near = listOf(NearTransfer(ME, OTHER, near("1"), Transfer, false)),
            status = Transaction.Status.Failed,
        )
        assertEquals(BigInteger.ZERO, tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun wrapDeposit_FdAexqEm() {
        val tx = tx(
            signer = ME, receiver = WRAP,
            actions = listOf(call("near_deposit", near("60"))),
            near = listOf(NearTransfer(ME, WRAP, near("60"), FunctionCallDeposit, true, "near_deposit")),
            ft = listOf(FtTransfer(WRAP, null, ME, near("60"), null)),
        )
        assertEquals(near("60").negate(), tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE, WRAP), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun wrapWithdraw_CYSanB9J() {
        val tx = tx(
            signer = ME, receiver = WRAP,
            actions = listOf(call("near_withdraw", BigInteger.ONE)),
            near = listOf(
                NearTransfer(ME, WRAP, BigInteger.ONE, FunctionCallDeposit, true, "near_withdraw"),
                NearTransfer(WRAP, ME, near("5.11"), Transfer, true),
            ),
            ft = listOf(FtTransfer(WRAP, ME, null, near("5.11"), null)),
        )
        assertEquals(near("5.11"), tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE, WRAP), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun oneYoctoProofOnNonTokenCall_CuWAXw5d() {
        // NEP-245 mt_transfer on intents.near: no NEAR is paid, but the call stays in NEAR's history
        val tx = tx(
            signer = ME, receiver = INTENTS,
            actions = listOf(call("mt_transfer", BigInteger.ONE)),
            near = listOf(NearTransfer(ME, INTENTS, BigInteger.ONE, FunctionCallDeposit, true, "mt_transfer")),
        )
        assertEquals(BigInteger.ZERO, tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun storageDepositOnNonTokenContract_rd4hWHps() {
        val tx = tx(
            signer = ME, receiver = DEX,
            actions = listOf(call("storage_deposit", near("0.01")), call("register_tokens", BigInteger.ONE)),
            near = listOf(
                NearTransfer(ME, DEX, near("0.01"), FunctionCallDeposit, true, "storage_deposit"),
                NearTransfer(ME, DEX, BigInteger.ONE, FunctionCallDeposit, true, "register_tokens"),
            ),
        )
        assertEquals(near("0.01").negate(), tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE), TransactionConverter.tags(tx, ME))
    }

    @Test
    fun withdrawNextToTokenSendIsNotTakenForARefund() {
        // a wrap.near payout larger than the registration deposit is a real movement
        val tx = tx(
            signer = ME, receiver = WRAP,
            actions = listOf(call("storage_deposit", near("0.00125")), call("ft_transfer", BigInteger.ONE), call("near_withdraw", BigInteger.ONE)),
            near = listOf(
                NearTransfer(ME, WRAP, near("0.00125"), FunctionCallDeposit, true, "storage_deposit"),
                NearTransfer(ME, WRAP, BigInteger.ONE, FunctionCallDeposit, true, "ft_transfer"),
                NearTransfer(ME, WRAP, BigInteger.ONE, FunctionCallDeposit, true, "near_withdraw"),
                NearTransfer(WRAP, ME, near("2"), Transfer, true),
            ),
            ft = listOf(FtTransfer(WRAP, ME, null, near("2"), null)),
        )
        assertEquals(near("2"), tx.nearMoved(ME))
        assertEquals(setOf(TOKEN_NATIVE, WRAP), TransactionConverter.tags(tx, ME))
    }

    private fun tx(
        signer: String,
        receiver: String,
        actions: List<TxAction>,
        near: List<NearTransfer> = emptyList(),
        ft: List<FtTransfer> = emptyList(),
        status: Transaction.Status = Transaction.Status.Success,
    ) = Transaction(
        hash = "hash", blockHeight = 1, timestamp = 1, signerId = signer, receiverId = receiver,
        actions = actions, nearTransfers = near, ftTransfers = ft, fee = BigInteger.ONE,
        status = status, failure = null, expiresAfterHeight = null,
    )

    private fun call(method: String, deposit: BigInteger) = TxAction("FunctionCall", deposit = deposit, methodName = method)

    private fun near(value: String) = java.math.BigDecimal(value).movePointRight(24).toBigIntegerExact()

    private companion object {
        const val ME = "me.near"
        const val OTHER = "other.near"
        const val WRAP = "wrap.near"
        const val USDT = "usdt.tether-token.near"
        const val TOKEN = "token.near"
        const val INTENTS = "intents.near"
        const val DEX = "v2.ref-finance.near"
    }
}
