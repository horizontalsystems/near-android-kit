package io.horizontalsystems.nearkit.borsh

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Borsh encoder for the subset of types NEAR transactions use. Integers are little-endian. */
internal class BorshWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply { out.write(value and 0xff) }

    fun u32(value: Long) = apply {
        require(value in 0..0xffffffffL) { "u32 out of range: $value" }
        writeLittleEndian(value, 4)
    }

    fun u64(value: Long) = apply { writeLittleEndian(value, 8) }

    /** Unsigned 64-bit given as [BigInteger], for nonces and gas that may exceed Long.MAX_VALUE. */
    fun u64(value: BigInteger) = apply { fixedUnsigned(value, 8, "u64") }

    fun u128(value: BigInteger) = apply { fixedUnsigned(value, 16, "u128") }

    fun fixedBytes(value: ByteArray) = apply { out.write(value) }

    /** Length-prefixed byte vector (`Vec<u8>`). */
    fun bytes(value: ByteArray) = apply {
        u32(value.size.toLong())
        out.write(value)
    }

    fun string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    fun <T> vec(items: List<T>, write: BorshWriter.(T) -> Unit) = apply {
        u32(items.size.toLong())
        items.forEach { write(it) }
    }

    fun <T> option(value: T?, write: BorshWriter.(T) -> Unit) = apply {
        if (value == null) {
            u8(0)
        } else {
            u8(1)
            write(value)
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeLittleEndian(value: Long, size: Int) {
        var v = value
        repeat(size) {
            out.write((v and 0xff).toInt())
            v = v ushr 8
        }
    }

    private fun fixedUnsigned(value: BigInteger, size: Int, name: String) {
        require(value.signum() >= 0 && value.bitLength() <= size * 8) { "$name out of range: $value" }
        val bigEndian = value.toByteArray()
        val result = ByteArray(size)
        // copy the low [size] bytes, reversed; toByteArray may carry a leading sign byte
        for (i in 0 until size) {
            val src = bigEndian.size - 1 - i
            if (src >= 0) result[i] = bigEndian[src]
        }
        out.write(result)
    }
}
