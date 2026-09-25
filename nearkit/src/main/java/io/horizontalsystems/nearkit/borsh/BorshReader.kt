package io.horizontalsystems.nearkit.borsh

import java.math.BigInteger

/** Borsh decoder, used to read transactions a dApp hands the wallet for signing. */
internal class BorshReader(private val data: ByteArray) {
    private var position = 0

    val remaining: Int get() = data.size - position

    fun u8(): Int {
        require(remaining >= 1) { "Unexpected end of data" }
        return data[position++].toInt() and 0xff
    }

    fun u32(): Long = littleEndian(4).toLong()

    fun u64(): BigInteger = littleEndian(8)

    fun u128(): BigInteger = littleEndian(16)

    fun fixedBytes(size: Int): ByteArray {
        require(size >= 0 && remaining >= size) { "Unexpected end of data" }
        return data.copyOfRange(position, position + size).also { position += size }
    }

    fun bytes(): ByteArray {
        val size = u32()
        require(size <= remaining) { "Length prefix exceeds data" }
        return fixedBytes(size.toInt())
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun <T> vec(read: BorshReader.() -> T): List<T> {
        val size = u32()
        // every element takes at least one byte, which bounds a hostile length prefix
        require(size <= remaining) { "Vector length exceeds data" }
        return List(size.toInt()) { read() }
    }

    fun <T> option(read: BorshReader.() -> T): T? = when (val tag = u8()) {
        0 -> null
        1 -> read()
        else -> throw IllegalArgumentException("Invalid option tag $tag")
    }

    private fun littleEndian(size: Int): BigInteger {
        val bytes = fixedBytes(size)
        bytes.reverse()
        return BigInteger(1, bytes)
    }
}
