package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.crypto.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * nearcore `calculate_tx_cost`: the send part is burnt at the block gas price (10^8), the
 * execution part and attached gas are bought at the 10^9 minimum purchase price; the account
 * creation charge is not part of it. The required balance takes the send part at twice the block
 * price, the fee is what the node burns. The values match the iOS kit's to the yoctoNEAR.
 */
class FeeCalculatorTest {

    private val gasPrice = BigInteger.valueOf(100_000_000)
    private val transfer = listOf(Action.Transfer(BigInteger.ONE))

    private fun estimate(receiver: AccountId.Type, createsAccount: Boolean = false, price: BigInteger = gasPrice) =
        FeeCalculator().estimate(transfer, receiver, createsAccount, price)

    @Test
    fun transferToNamedAccount() {
        // 0.2232 Tgas send + 0.2232 Tgas execution
        val e = estimate(AccountId.Type.Named)
        assertEquals(446_365_125_000L, e.gas)
        assertEquals(BigInteger("44636512500000000000"), e.fee)
        // 0.2232 Tgas × 2 × 10^8 + 0.2232 Tgas × 10^9; the node's minimum is 0.0002455008 NEAR
        assertEquals(BigInteger("267819075000000000000"), e.requiredBalance)
    }

    @Test
    fun transferToImplicitAccountPaysCreationGasEvenWhenItExists() {
        // mainnet tx BiuXnScW…: 0.8249 Tgas to convert + 7.5249 Tgas to execute
        val e = estimate(AccountId.Type.NearImplicit)
        assertEquals(824_947_687_500L + 7_524_947_687_500L, e.gas)
        assertEquals(BigInteger("834989537500000000000"), e.fee)
        // the node's minimum 0.0076074425 NEAR, plus 0.8249 Tgas × 10^8
        assertEquals(BigInteger("7689937225000000000000"), e.requiredBalance)
    }

    // NEP-642: the 0.007 NEAR charge comes out of the refund for the 7.2 Tgas create_account
    // execution gas, so the required balance does not change and the fee grows by the charge
    // less that gas burnt at the block price
    @Test
    fun creatingAnAccountChargesOnlyWhatTheCreationGasDoesNotCover() {
        val e = estimate(AccountId.Type.NearImplicit, createsAccount = true)
        assertEquals(BigInteger("7114989537500000000000"), e.fee)
        assertEquals(BigInteger("7689937225000000000000"), e.requiredBalance)
    }

    // an ETH-implicit or deterministic receiver is created without a key
    @Test
    fun transferToEthImplicitOrDeterministicAccountCreatesItWithoutAKey() {
        for (receiver in listOf(AccountId.Type.EthImplicit, AccountId.Type.Deterministic)) {
            val existing = estimate(receiver)
            assertEquals(723_182_562_500L + 7_423_182_562_500L, existing.gas)
            assertEquals(BigInteger("814636512500000000000"), existing.fee)
            assertEquals(BigInteger("7567819075000000000000"), existing.requiredBalance)
            assertEquals(BigInteger("7094636512500000000000"), estimate(receiver, createsAccount = true).fee)
        }
    }

    @Test
    fun creationGasAboveTheChargeAddsNothing() {
        val price = BigInteger.valueOf(1_000_000_000)
        val created = estimate(AccountId.Type.NearImplicit, createsAccount = true, price = price)
        val existing = estimate(AccountId.Type.NearImplicit, price = price)
        assertEquals(existing.fee, created.fee)
        assertEquals(BigInteger.valueOf(824_947_687_500L * 2 + 7_524_947_687_500L) * price, created.requiredBalance)
    }

    @Test
    fun ftTransferRequiredBalance() {
        fun required(storageDeposit: BigInteger?) = FeeCalculator().estimate(
            FtActions.transfer("bob.near", BigInteger.valueOf(1_000_000), null, storageDeposit),
            AccountId.Type.Named, createsAccount = false, gasPrice = gasPrice, expectedCallGas = FtActions.EXPECTED_GAS,
        ).requiredBalance
        // ft_transfer with 30 Tgas attached: the node's minimum 0.0309192577 NEAR, plus 0.3107 Tgas send × 10^8
        assertEquals(BigInteger("30950330669912000000000"), required(null))
        // storage_deposit + ft_transfer, 30 Tgas each
        assertEquals(BigInteger("61771095893917000000000"), required(BigInteger("1250000000000000000000")))
    }

    @Test
    fun hostileAttachedGasDoesNotOverflow() {
        val u64Max = BigInteger.TWO.pow(64) - BigInteger.ONE
        val call = Action.FunctionCall("m", ByteArray(0), u64Max, BigInteger.ZERO)
        val e = FeeCalculator().estimate(listOf(call, call), AccountId.Type.Named, createsAccount = false, gasPrice = gasPrice)
        assertEquals(Long.MAX_VALUE, e.gas)
        assertTrue(e.requiredBalance > u64Max * BigInteger.TWO * BigInteger.valueOf(1_000_000_000))
    }
}
