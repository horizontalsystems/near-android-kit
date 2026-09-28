package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.hdwalletkit.Curve
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.nearkit.crypto.Base58
import io.horizontalsystems.nearkit.crypto.PublicKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger

/**
 * Ed25519 signer. [privateKey] is the 32-byte Ed25519 seed; NEAR's "secret key" string is that
 * seed followed by the public key, base58-encoded with an `ed25519:` prefix.
 */
class Signer(privateKey: ByteArray) {

    private val keyParameters: Ed25519PrivateKeyParameters

    init {
        require(privateKey.size == 32) { "Ed25519 private key must be 32 bytes" }
        keyParameters = Ed25519PrivateKeyParameters(privateKey, 0)
    }

    val publicKey: PublicKey = PublicKey(keyParameters.generatePublicKey().encoded)

    /** `ed25519:<base58(seed ‖ public key)>`, the format near-cli and every NEAR wallet import. */
    val secretKeyString: String
        get() = PublicKey.PREFIX + Base58.encode(keyParameters.encoded + publicKey.data)

    /**
     * Private on purpose: signing caller-chosen bytes would sign a transaction for anyone who
     * passes its hash. Everything signed goes through a typed, domain-separated payload.
     */
    private fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, keyParameters)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Signs SHA-256 of the transaction's Borsh encoding, as nearcore verifies it. */
    fun sign(transaction: Transaction): SignedTransaction {
        require(transaction.publicKey == publicKey) { "Transaction public key does not match the signer" }
        return SignedTransaction(transaction, sign(transaction.hash()))
    }

    /** Signs a NEP-413 message (`near_signMessage`). */
    fun sign(payload: MessagePayload): ByteArray = sign(payload.hash())

    companion object {
        const val COIN_TYPE = 397

        /**
         * SLIP-10 Ed25519 at m/44'/397'/0', the path near-seed-phrase (MyNearWallet, near-cli)
         * and Trust Wallet use, so the same words give the same account everywhere. Ledger uses
         * 44'/397'/0'/0'/1' and is a different key.
         */
        fun getInstance(seed: ByteArray, account: Int = 0): Signer = Signer(privateKey(seed, account))

        fun privateKey(seed: ByteArray, account: Int = 0): ByteArray {
            val hdWallet = HDWallet(seed, COIN_TYPE, HDWallet.Purpose.BIP44, Curve.Ed25519)
            // hdwalletkit keeps the key as a BigInteger; its byte form drops leading zero bytes
            // or adds a sign byte, so rebuild the fixed 32-byte seed from the number
            return toFixed32(hdWallet.privateKey(account).privKey)
        }

        /** Parses `ed25519:<base58>` holding either the 64-byte secret key or the 32-byte seed. */
        fun fromSecretKey(secretKey: String): Signer {
            val body = secretKey.trim().removePrefix(PublicKey.PREFIX)
            require(!body.contains(':')) { "Only ed25519 keys are supported" }
            val bytes = Base58.decode(body)
            return when (bytes.size) {
                32 -> Signer(bytes)
                64 -> {
                    val signer = Signer(bytes.copyOfRange(0, 32))
                    require(signer.publicKey.data.contentEquals(bytes.copyOfRange(32, 64))) { "Secret key does not match its public key" }
                    signer
                }
                else -> throw IllegalArgumentException("Invalid secret key length ${bytes.size}")
            }
        }

        internal fun toFixed32(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            require(value.signum() >= 0 && value.bitLength() <= 256) { "Key out of range" }
            val result = ByteArray(32)
            val copy = minOf(bytes.size, 32)
            System.arraycopy(bytes, bytes.size - copy, result, 32 - copy, copy)
            return result
        }
    }
}
