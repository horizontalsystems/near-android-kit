package io.horizontalsystems.nearkit.transaction

import io.horizontalsystems.nearkit.borsh.BorshWriter
import io.horizontalsystems.nearkit.crypto.Hashes
import io.horizontalsystems.nearkit.crypto.PublicKey
import java.util.Base64

/**
 * A NEP-413 off-chain message (https://github.com/near/NEPs/blob/master/neps/nep-0413.md), the
 * payload of WalletConnect `near_signMessage`. The signed value is SHA-256 of the Borsh encoding
 * behind a 2^31 + 413 tag, which no transaction encoding can start with, so a signed message can
 * never be replayed as a signed transaction.
 */
class MessagePayload(
    val message: String,
    /** 32 bytes chosen by the dApp, so the signature cannot be replayed. */
    val nonce: ByteArray,
    /** Who the message is for, e.g. the dApp's domain or contract id. */
    val recipient: String,
    val callbackUrl: String? = null,
) {
    init {
        require(nonce.size == 32) { "NEP-413 nonce must be 32 bytes" }
    }

    fun encode(): ByteArray = BorshWriter()
        .u32(TAG)
        .string(message)
        .fixedBytes(nonce)
        .string(recipient)
        .option(callbackUrl) { string(it) }
        .toByteArray()

    fun hash(): ByteArray = Hashes.sha256(encode())

    companion object {
        /** 2^31 + 413: too large for the u32 length of a transaction's leading signer id. */
        const val TAG = 2_147_484_061L
    }
}

/** A NEP-413 signature, in the shape `near_signMessage` returns. */
class SignedMessage(val accountId: String, val publicKey: PublicKey, val signature: ByteArray) {
    val signatureBase64: String get() = Base64.getEncoder().encodeToString(signature)
}
