package com.toxicbakery.app.bison.mosh.wire

/**
 * One part of the zlib stream of an instruction (wire fact 5).
 *
 * The wire form is the 64-bit [instructionId], a 16-bit word, and the [content]. Bit 15 of the word is the final
 * flag, and bits 0 to 14 are the fragment [number]. The numbers are big-endian. The content of all fragments of one
 * ID, in the order of their numbers, is the zlib stream of one instruction. Each resend of an instruction has a new
 * ID.
 */
class Fragment(
    val instructionId: ULong,
    val number: Int,
    val isFinal: Boolean,
    val content: ByteArray,
) {
    init {
        require(number in 0..MAX_NUMBER) { "The fragment number $number is not in 0..$MAX_NUMBER." }
    }

    /** The size of the wire form. */
    val encodedSize: Int get() = HEADER_SIZE + content.size

    /** Returns the wire form. */
    fun encode(): ByteArray = ByteArray(encodedSize).also { bytes -> writeTo(bytes, 0) }

    /** Writes the wire form into [destination] at [offset]. */
    internal fun writeTo(destination: ByteArray, offset: Int) {
        destination.writeLongAt(offset, instructionId.toLong())
        destination.writeShortAt(offset + Long.SIZE_BYTES, if (isFinal) number or FINAL_FLAG else number)
        content.copyInto(destination, offset + HEADER_SIZE)
    }

    override fun equals(other: Any?): Boolean =
        other is Fragment &&
            instructionId == other.instructionId &&
            number == other.number &&
            isFinal == other.isFinal &&
            content.contentEquals(other.content)

    override fun hashCode(): Int {
        var result = instructionId.hashCode()
        result = 31 * result + number
        result = 31 * result + isFinal.hashCode()
        return 31 * result + content.contentHashCode()
    }

    override fun toString(): String =
        "Fragment(instructionId=$instructionId, number=$number, isFinal=$isFinal, content=${content.size} bytes)"

    companion object {
        /** The size of the instruction ID and the number word. */
        const val HEADER_SIZE: Int = 10

        /** The largest fragment number that the 15 bits can hold. */
        const val MAX_NUMBER: Int = 0x7FFF

        /**
         * The largest content of a fragment that this client sends. A datagram is then at most
         * [Datagram.MAX_SEND_SIZE] bytes.
         */
        const val MAX_CONTENT_SIZE: Int = Datagram.MAX_SEND_SIZE - Datagram.OVERHEAD - HEADER_SIZE

        private const val FINAL_FLAG = 0x8000

        /** Reads a fragment from [length] bytes of [bytes] at [offset]. Returns null when it has no full header. */
        fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Fragment? {
            if (length < HEADER_SIZE) return null
            val word = bytes.readShortAt(offset + Long.SIZE_BYTES)
            return Fragment(
                instructionId = bytes.readLongAt(offset).toULong(),
                number = word and MAX_NUMBER,
                isFinal = word and FINAL_FLAG != 0,
                content = bytes.copyOfRange(offset + HEADER_SIZE, offset + length),
            )
        }

        /**
         * Cuts [stream], the zlib stream of one instruction, into fragments of the ID [instructionId]. Each fragment
         * has at most [maxContentSize] bytes of content. An empty stream gives one empty final fragment.
         */
        fun split(
            instructionId: ULong,
            stream: ByteArray,
            maxContentSize: Int = MAX_CONTENT_SIZE,
        ): List<Fragment> {
            require(maxContentSize > 0) { "The content size must be more than 0, not $maxContentSize." }
            val count = maxOf(1, (stream.size + maxContentSize - 1) / maxContentSize)
            require(count <= FragmentAssembler.MAX_FRAGMENTS) {
                "The stream needs $count fragments. The limit is ${FragmentAssembler.MAX_FRAGMENTS}."
            }
            return List(count) { number ->
                val start = number * maxContentSize
                Fragment(
                    instructionId = instructionId,
                    number = number,
                    isFinal = number == count - 1,
                    content = stream.copyOfRange(start, minOf(stream.size, start + maxContentSize)),
                )
            }
        }
    }
}
