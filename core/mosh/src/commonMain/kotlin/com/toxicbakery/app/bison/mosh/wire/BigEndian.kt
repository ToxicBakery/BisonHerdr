package com.toxicbakery.app.bison.mosh.wire

/** Writes the 8 bytes of [value] at [offset], most significant byte first. */
internal fun ByteArray.writeLongAt(offset: Int, value: Long) {
    for (index in 0 until Long.SIZE_BYTES) {
        this[offset + index] = (value ushr (Long.SIZE_BITS - Byte.SIZE_BITS * (index + 1))).toByte()
    }
}

/** Reads 8 bytes at [offset], most significant byte first. */
internal fun ByteArray.readLongAt(offset: Int): Long {
    var value = 0L
    for (index in 0 until Long.SIZE_BYTES) {
        value = (value shl Byte.SIZE_BITS) or (this[offset + index].toLong() and BYTE_MASK.toLong())
    }
    return value
}

/** Writes the low 16 bits of [value] at [offset], most significant byte first. */
internal fun ByteArray.writeShortAt(offset: Int, value: Int) {
    this[offset] = (value ushr Byte.SIZE_BITS).toByte()
    this[offset + 1] = value.toByte()
}

/** Reads 2 bytes at [offset] as an unsigned value, most significant byte first. */
internal fun ByteArray.readShortAt(offset: Int): Int =
    ((this[offset].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (this[offset + 1].toInt() and BYTE_MASK)

private const val BYTE_MASK = 0xFF
