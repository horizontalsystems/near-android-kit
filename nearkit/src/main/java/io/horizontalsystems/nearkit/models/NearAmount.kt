package io.horizontalsystems.nearkit.models

import java.math.BigDecimal
import java.math.BigInteger

/** NEAR has 24 decimals; amounts travel as yoctoNEAR (u128) and never fit a Long. */
object NearAmount {
    const val DECIMALS = 24

    fun toNear(yocto: BigInteger): BigDecimal = BigDecimal(yocto).movePointLeft(DECIMALS).stripTrailingZerosSafe()

    /** Throws [ArithmeticException] when [near] has more than 24 decimal places. */
    fun toYocto(near: BigDecimal): BigInteger = near.movePointRight(DECIMALS).toBigIntegerExact()

    private fun BigDecimal.stripTrailingZerosSafe(): BigDecimal =
        if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()
}
