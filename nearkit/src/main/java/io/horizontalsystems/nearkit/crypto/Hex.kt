package io.horizontalsystems.nearkit.crypto

private const val HEX_CHARS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0f])
    }
    return sb.toString()
}

/** Throws [IllegalArgumentException] for odd length or non-hex characters. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string has odd length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex string" }
        ((hi shl 4) or lo).toByte()
    }
}
