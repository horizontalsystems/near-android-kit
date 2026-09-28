package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.network.InvalidResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
