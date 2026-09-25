package io.horizontalsystems.nearkit.crypto

import java.security.MessageDigest

internal object Hashes {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
