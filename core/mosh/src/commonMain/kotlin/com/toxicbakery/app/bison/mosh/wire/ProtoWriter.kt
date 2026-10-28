package com.toxicbakery.app.bison.mosh.wire

/**
 * Writes the fields of one protobuf message, as the Protocol Buffers wire format page tells
 * (https://protobuf.dev/programming-guides/encoding/). It writes only varint fields and length-delimited fields.
 */
internal class ProtoWriter {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /** Writes [value] as an unsigned varint in [field]. */
    fun varint(field: Int, value: ULong): ProtoWriter {
        tag(field, ProtoWireType.VARINT)
        rawVarint(value)
        return this
    }

    /** Writes [value] as a length-delimited field. */
    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, ProtoWireType.LENGTH_DELIMITED)
        rawVarint(value.size.toULong())
        ensureCapacity(value.size)
        value.copyInto(buffer, size)
        size += value.size
        return this
    }

    /** Returns the bytes of the message. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun tag(field: Int, wireType: Int) {
        require(field in 1..ProtoWireType.MAX_FIELD) { "The field number $field is not valid." }
        rawVarint((field.toULong() shl ProtoWireType.TAG_TYPE_BITS) or wireType.toULong())
    }

    private fun rawVarint(value: ULong) {
        ensureCapacity(ProtoWireType.MAX_VARINT_SIZE)
        var rest = value
        while (rest >= ProtoWireType.CONTINUATION_BIT) {
            buffer[size++] = ((rest and ProtoWireType.PAYLOAD_MASK) or ProtoWireType.CONTINUATION_BIT).toByte()
            rest = rest shr ProtoWireType.PAYLOAD_BITS
        }
        buffer[size++] = rest.toByte()
    }

    private fun ensureCapacity(extra: Int) {
        if (size + extra <= buffer.size) return
        buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
