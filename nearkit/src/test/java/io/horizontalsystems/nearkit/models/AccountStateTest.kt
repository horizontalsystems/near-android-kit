package io.horizontalsystems.nearkit.models

import io.horizontalsystems.nearkit.transaction.Action
import io.horizontalsystems.nearkit.transaction.FeeCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class AccountStateTest {

    private fun state(amount: String, storage: Long, locked: String = "0") =
        AccountState(exists = true, amount = BigInteger(amount), locked = BigInteger(locked), storageUsage = storage, hasContract = false)

    @Test
    fun zeroBalanceAccountHasEverythingAvailable() {
        val s = state("1000", storage = 182)
        assertEquals(BigInteger.ZERO, s.storageStaked)
        assertEquals(BigInteger("1000"), s.available)
        assertEquals(BigInteger("1000"), state("1000", storage = 770).available)
    }

    @Test
    fun aboveTheLimitTheWholeUsageIsStaked() {
        // 771 bytes × 10^19 = 0.00771 NEAR
        val s = state("10000000000000000000000", storage = 771)
        assertEquals(BigInteger("7710000000000000000000"), s.storageStaked)
        assertEquals(BigInteger("2290000000000000000000"), s.available)
    }

    @Test
    fun lockedStakeCoversStorage() {
        val s = state("5", storage = 1000, locked = "20000000000000000000000")
        assertEquals(BigInteger("5"), s.available)
    }

    @Test
    fun availableNeverNegative() {
        assertEquals(BigInteger.ZERO, state("1", storage = 5000).available)
    }

    @Test
    fun nearAmountConversions() {
        assertEquals(BigDecimal("1.5"), NearAmount.toNear(BigInteger("1500000000000000000000000")))
        assertEquals(BigInteger("1"), NearAmount.toYocto(BigDecimal("0.000000000000000000000001")))
        assertEquals(BigDecimal.ZERO, NearAmount.toNear(BigInteger.ZERO))
    }

    @Test
    fun transferToNamedAccount() {
        // 0.446 Tgas: burnt at the 10^8 gas price, bought at the 10^9 minimum purchase price
        val e = FeeCalculator().estimate(listOf(Action.Transfer(BigInteger.ONE)), receiverIsImplicit = false, createsAccount = false, gasPrice = BigInteger.valueOf(100_000_000))
        assertEquals(446_365_125_000L, e.gas)
        assertEquals(BigInteger("44636512500000000000"), e.fee)
        assertEquals(BigInteger("446365125000000000000"), e.requiredBalance)
    }

    @Test
    fun transferToImplicitAccountPaysCreationGasEvenWhenItExists() {
        // mainnet tx BiuXnScW…: 0.8249 Tgas to convert + 7.5249 Tgas to execute
        val e = FeeCalculator().estimate(listOf(Action.Transfer(BigInteger.ONE)), receiverIsImplicit = true, createsAccount = false, gasPrice = BigInteger.valueOf(100_000_000))
        assertEquals(824_947_687_500L + 7_524_947_687_500L, e.gas)
    }

    @Test
    fun creatingAnAccountAddsTheCharge() {
        val e = FeeCalculator().estimate(listOf(Action.Transfer(BigInteger.ONE)), receiverIsImplicit = true, createsAccount = true, gasPrice = BigInteger.valueOf(100_000_000))
        assertEquals(BigInteger("7000000000000000000000") + BigInteger.valueOf(8_349_895_375_000L) * BigInteger.valueOf(100_000_000), e.fee)
    }
}
