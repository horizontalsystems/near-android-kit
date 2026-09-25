package io.horizontalsystems.nearkit

/**
 * How the kit is keyed. [accountId] selects a named account (`alice.near`) the key controls;
 * without it the kit uses the key's implicit account (64 hex characters).
 */
sealed interface NearWallet {
    data class Seed(val seed: ByteArray, val accountId: String? = null) : NearWallet {
        override fun equals(other: Any?) = other is Seed && other.seed.contentEquals(seed) && other.accountId == accountId
        override fun hashCode() = 31 * seed.contentHashCode() + (accountId?.hashCode() ?: 0)
    }

    /** A 32-byte Ed25519 private key, e.g. from an imported `ed25519:…` secret key. */
    data class PrivateKey(val privateKey: ByteArray, val accountId: String? = null) : NearWallet {
        override fun equals(other: Any?) = other is PrivateKey && other.privateKey.contentEquals(privateKey) && other.accountId == accountId
        override fun hashCode() = 31 * privateKey.contentHashCode() + (accountId?.hashCode() ?: 0)
    }

    data class WatchOnly(val accountId: String) : NearWallet
}
