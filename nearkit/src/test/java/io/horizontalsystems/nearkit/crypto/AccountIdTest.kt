package io.horizontalsystems.nearkit.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdTest {

    @Test
    fun acceptsValidIds() {
        listOf(
            "near", "alice.near", "a-b_c.tg", "usdt.tether-token.near", "0x1234567890abcdef1234567890abcdef12345678",
            "5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412", "ab",
        ).forEach { assertTrue(it, AccountId.isValid(it)) }
    }

    @Test
    fun rejectsInvalidIds() {
        listOf(
            "a", "Alice.near", "alice..near", ".near", "near.", "a--b", "a_-b", "alice near", "alice@near",
            "a".repeat(65), "", "ed25519:6j4b6zUaty6fD1awqcGCCU9JYGCWYUgdJhQrzfZhqE25",
        ).forEach { assertFalse(it, AccountId.isValid(it)) }
    }

    @Test
    fun classifiesIds() {
        assertEquals(AccountId.Type.Named, AccountId.type("alice.near"))
        assertEquals(AccountId.Type.NearImplicit, AccountId.type("5510e2b44cae6eb807e3e0e45d579dda058c274abcba15e5cb84636f5d1ee412"))
        assertEquals(AccountId.Type.EthImplicit, AccountId.type("0x1234567890abcdef1234567890abcdef12345678"))
        assertEquals(AccountId.Type.Deterministic, AccountId.type("0s1234567890abcdef1234567890abcdef12345678"))
        assertNull(AccountId.type("0X1234"))
        // 64 hex characters but upper case is not a valid account id at all
        assertNull(AccountId.type("5510E2B44CAE6EB807E3E0E45D579DDA058C274ABCBA15E5CB84636F5D1EE412"))
    }

    @Test
    fun base58RoundTripsLeadingZeros() {
        val data = byteArrayOf(0, 0, 1, 2, 3, -1)
        assertArrayEquals(data, Base58.decode(Base58.encode(data)))
        assertEquals("11", Base58.encode(byteArrayOf(0, 0)))
        assertArrayEquals(ByteArray(32), Base58.decode("11111111111111111111111111111111"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun base58RejectsZeroLetter() {
        Base58.decode("0OIl")
    }
}
