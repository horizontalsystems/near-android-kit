package io.horizontalsystems.nearkit.crypto

/** An Ed25519 public key, shown by NEAR as `ed25519:<base58>`. The kit does not handle secp256k1 keys. */
class PublicKey(val data: ByteArray) {
    init {
        require(data.size == 32) { "Ed25519 public key must be 32 bytes" }
    }

    /** The NEAR-implicit account id this key controls: the 64-character lowercase hex of the key. */
    val implicitAccountId: String get() = data.toHex()

    override fun toString(): String = "$PREFIX${Base58.encode(data)}"

    override fun equals(other: Any?) = other is PublicKey && other.data.contentEquals(data)
    override fun hashCode() = data.contentHashCode()

    companion object {
        const val PREFIX = "ed25519:"

        /** Accepts `ed25519:<base58>` or bare base58. */
        fun fromString(value: String): PublicKey {
            val body = value.removePrefix(PREFIX)
            require(!body.contains(':')) { "Only ed25519 keys are supported" }
            return PublicKey(Base58.decode(body))
        }
    }
}
