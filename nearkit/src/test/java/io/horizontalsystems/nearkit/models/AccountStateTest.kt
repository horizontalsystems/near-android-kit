package io.horizontalsystems.nearkit.models

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
}
