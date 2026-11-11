package com.toxicbakery.app.bison.mosh.wire

/** The protobuf wire types and limits that [ProtoReader] and [ProtoWriter] use. */
internal object ProtoWireType {
    const val VARINT: Int = 0
    const val FIXED64: Int = 1
    const val LENGTH_DELIMITED: Int = 2
    const val FIXED32: Int = 5

    /** The largest field number of the protobuf format. */
    const val MAX_FIELD: Int = (1 shl 29) - 1

    /** The bits of a tag that hold the wire type. */
    const val TAG_TYPE_BITS: Int = 3

    /** The largest size of a varint of 64 bits. */
    const val MAX_VARINT_SIZE: Int = 10

    const val PAYLOAD_BITS: Int = 7
    const val PAYLOAD_MASK: ULong = 0x7FuL
    const val CONTINUATION_BIT: ULong = 0x80uL
}

/** Tells that the bytes are not a valid protobuf message. */
internal class ProtoException(message: String) : Exception(message)

/**
 * Reads the fields of one protobuf message, as the Protocol Buffers wire format page tells
 * (https://protobuf.dev/programming-guides/encoding/).
 *
 * Call [next] to read each tag. Then read the value with [varint] or [bytes], or [skip] it. The reader skips the
 * fixed 32-bit and 64-bit types. It throws [ProtoException] for a group (wire types 3 and 4) and for a bad wire
 * type. It also throws [ProtoException] for a value that goes past the end of the data.
 */
internal class ProtoReader(private val data: ByteArray) {
    private var position = 0

    /** The field number of the tag that [next] read last. */
    var field: Int = 0
        private set

    /** The wire type of the tag that [next] read last. */
    var wireType: Int = -1
        private set

    /** True when the last tag is a varint field. */
    val isVarint: Boolean get() = wireType == ProtoWireType.VARINT

    /** True when the last tag is a length-delimited field. */
    val isLengthDelimited: Boolean get() = wireType == ProtoWireType.LENGTH_DELIMITED

    /** Reads the next tag. Returns false at the end of the data. */
    fun next(): Boolean {
        if (position == data.size) return false
        val tag = rawVarint()
        if (tag > MAX_TAG) throw ProtoException("The tag $tag is too large.")
        field = (tag shr ProtoWireType.TAG_TYPE_BITS).toInt()
        wireType = (tag and TYPE_MASK).toInt()
        if (field == 0) throw ProtoException("The field number is 0.")
        return true
    }

    /** Reads the value of a varint field. */
    fun varint(): ULong {
        if (!isVarint) throw ProtoException("Field $field has wire type $wireType, not a varint.")
        return rawVarint()
    }

    /** Reads the value of a length-delimited field. */
    fun bytes(): ByteArray {
        if (!isLengthDelimited) throw ProtoException("Field $field has wire type $wireType, not a length.")
        val length = length()
        return data.copyOfRange(position, position + length).also { position += length }
    }

    /** Skips the value of the last tag. */
    fun skip() {
        when (wireType) {
            ProtoWireType.VARINT -> rawVarint()
            ProtoWireType.FIXED64 -> advance(Long.SIZE_BYTES)
            ProtoWireType.LENGTH_DELIMITED -> advance(length())
            ProtoWireType.FIXED32 -> advance(Int.SIZE_BYTES)
            else -> throw ProtoException("Field $field has the wire type $wireType, which the reader cannot skip.")
        }
    }

    private fun length(): Int {
        val length = rawVarint()
        if (length > (data.size - position).toULong()) throw ProtoException("Field $field goes past the end.")
        return length.toInt()
    }

    private fun advance(count: Int) {
        if (count > data.size - position) throw ProtoException("Field $field goes past the end.")
        position += count
    }

    private fun rawVarint(): ULong {
        var value = 0uL
        var shift = 0
        while (true) {
            if (position == data.size) throw ProtoException("A varint goes past the end.")
            val byte = data[position++].toULong() and BYTE_MASK
            // The tenth byte holds only bit 63.
            if (shift == LAST_SHIFT && byte > 1uL) throw ProtoException("A varint is more than 64 bits.")
            value = value or ((byte and ProtoWireType.PAYLOAD_MASK) shl shift)
            if (byte and ProtoWireType.CONTINUATION_BIT == 0uL) return value
            shift += ProtoWireType.PAYLOAD_BITS
        }
    }

    private companion object {
        const val MAX_TAG: ULong = 0xFFFFFFFFuL
        const val TYPE_MASK: ULong = 0x7uL
        const val BYTE_MASK: ULong = 0xFFuL
        const val LAST_SHIFT = 63
    }
}
