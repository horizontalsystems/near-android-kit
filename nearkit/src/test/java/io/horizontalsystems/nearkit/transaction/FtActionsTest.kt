package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.network.InvalidResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class FtActionsTest {

    @Test
    fun acceptsUsualStorageDeposits() {
        val usual = BigInteger("1250000000000000000000") // 0.00125 NEAR
        assertEquals(usual, FtActions.checkStorageDeposit(usual))
        assertEquals(BigInteger.ZERO, FtActions.checkStorageDeposit(BigInteger.ZERO))
        assertEquals(FtActions.MAX_STORAGE_DEPOSIT, FtActions.checkStorageDeposit(FtActions.MAX_STORAGE_DEPOSIT))
    }

    @Test
    fun rejectsStorageDepositAboveLimit() {
        val error = assertThrows(TransactionSender.SendError.StorageDepositTooHigh::class.java) {
            FtActions.checkStorageDeposit(FtActions.MAX_STORAGE_DEPOSIT + BigInteger.ONE)
        }
        assertEquals(FtActions.MAX_STORAGE_DEPOSIT + BigInteger.ONE, error.deposit)

        // a scam token asking for 500 NEAR
        assertThrows(TransactionSender.SendError.StorageDepositTooHigh::class.java) {
            FtActions.checkStorageDeposit(BigInteger.TEN.pow(24).multiply(BigInteger.valueOf(500)))
        }
    }

    @Test
    fun rejectsNegativeStorageDeposit() {
        assertThrows(InvalidResponse::class.java) { FtActions.checkStorageDeposit(BigInteger.ONE.negate()) }
    }

    @Test
    fun feeCountsExpectedGasButBalanceCoversAttachedGas() {
        val gasPrice = BigInteger.valueOf(100_000_000)
        val calculator = FeeCalculator()
        fun estimate(storageDeposit: BigInteger?, expectedCallGas: Long?) = calculator.estimate(
            FtActions.transfer("bob.near", BigInteger.TEN, null, storageDeposit),
            receiverIsImplicit = false,
            createsAccount = false,
            gasPrice = gasPrice,
            expectedCallGas = expectedCallGas,
        )
        val near = BigInteger.TEN.pow(24)

        for ((storageDeposit, charged) in listOf(
            // yoctoNEAR mainnet USDC actually charged, 2026-09-28
            null to BigInteger("270000000000000000000"),
            BigInteger("1250000000000000000000") to BigInteger("477000000000000000000"),
        )) {
            val full = estimate(storageDeposit, expectedCallGas = null)
            val expected = estimate(storageDeposit, expectedCallGas = FtActions.EXPECTED_GAS)

            assertEquals(full.requiredBalance, expected.requiredBalance)
            assertEquals(full.gas, expected.gas)
            // above what was charged, and within a few times of it instead of over ten
            assertTrue(expected.fee > charged)
            assertTrue(expected.fee < charged.multiply(BigInteger.valueOf(3)))
            assertTrue(full.fee > charged.multiply(BigInteger.TEN))
            assertTrue(expected.fee < near)
        }
    }
}
