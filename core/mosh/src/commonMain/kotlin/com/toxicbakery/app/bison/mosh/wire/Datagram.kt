package com.toxicbakery.app.bison.mosh.wire

/** The direction of a [Datagram]. Bit 63 of the header holds it (wire fact 2). */
enum class Direction {
    /** From the client to `mosh-server`. Bit 63 is 0. */
    ToServer,

    /** From `mosh-server` to the client. Bit 63 is 1. */
    ToClient,
}

/**
 * One Mosh datagram, before [seal] or after [open] (wire facts 2, 3, and 4).
 *
 * The wire form is an 8-byte big-endian header, the OCB ciphertext, and the 16-byte tag. Bit 63 of the header is the
 * [direction], and bits 0 to 62 are the [sequence] number. The nonce is 4 zero bytes and then the header. The
 * plaintext is the 16-bit [timestamp], the 16-bit [timestampReply], and one [fragment].
 */
class Datagram(
    val direction: Direction,
    val sequence: Long,
    val timestamp: Int,
    val timestampReply: Int,
    val fragment: Fragment,
) {
    init {
        require(sequence >= 0) { "The sequence number $sequence is not in 0..2^63 - 1." }
        require(timestamp in 0..TIMESTAMP_MASK) { "The timestamp $timestamp is not a 16-bit value." }
        require(timestampReply in 0..TIMESTAMP_MASK) { "The timestamp reply $timestampReply is not a 16-bit value." }
    }

    /** Returns the wire form, sealed with [cipher]. */
    fun seal(cipher: MoshCipher): ByteArray {
        val header = header(direction, sequence)
        val plaintext = ByteArray(TIMESTAMPS_SIZE + fragment.encodedSize)
        plaintext.writeShortAt(0, timestamp)
        plaintext.writeShortAt(Short.SIZE_BYTES, timestampReply)
        fragment.writeTo(plaintext, TIMESTAMPS_SIZE)
        val sealed = cipher.seal(nonce(header), plaintext)
        return ByteArray(HEADER_SIZE + sealed.size).also { packet ->
            packet.writeLongAt(0, header)
            sealed.copyInto(packet, HEADER_SIZE)
        }
    }

    override fun toString(): String =
        "Datagram(direction=$direction, sequence=$sequence, timestamp=$timestamp, " +
            "timestampReply=$timestampReply, fragment=$fragment)"

    companion object {
        /** The size of the header. */
        const val HEADER_SIZE: Int = 8

        /** The size of the timestamp and the timestamp reply. */
        const val TIMESTAMPS_SIZE: Int = 4

        /** The bytes of a datagram that are not fragment bytes: the header, the timestamps, and the tag. */
        const val OVERHEAD: Int = HEADER_SIZE + TIMESTAMPS_SIZE + MoshCipher.TAG_SIZE

        /** The value of [timestampReply] when the sender has no timestamp to reply to. */
        const val NO_TIMESTAMP: Int = 0xFFFF

        /**
         * The largest datagram that this client sends. This value is a choice of this client, not a Mosh value. It
         * is the UDP payload of the minimum IPv6 MTU (1280 bytes, RFC 8200) after the 40-byte IPv6 header and the
         * 8-byte UDP header. Such a datagram also passes an IPv4 path with no fragmentation.
         */
        const val MAX_SEND_SIZE: Int = 1232

        private const val TIMESTAMP_MASK = 0xFFFF
        private const val DIRECTION_BIT = Long.MIN_VALUE
        private const val NONCE_ZERO_BYTES = MoshCipher.NONCE_SIZE - HEADER_SIZE
        private const val MIN_SIZE = OVERHEAD + Fragment.HEADER_SIZE

        /**
         * Reads the first [length] bytes of [packet] as a datagram of the [expected] direction. Returns null when
         * the packet is too short, has a different direction, or does not open with [cipher]. A datagram of the
         * other direction can be an echo of a datagram of this side, so it never opens.
         */
        fun open(packet: ByteArray, cipher: MoshCipher, expected: Direction, length: Int = packet.size): Datagram? {
            if (length < MIN_SIZE || length > packet.size) return null
            val header = packet.readLongAt(0)
            val direction = if (header and DIRECTION_BIT == 0L) Direction.ToServer else Direction.ToClient
            if (direction != expected) return null
            val plaintext = cipher.open(nonce(header), packet.copyOfRange(HEADER_SIZE, length)) ?: return null
            val fragment = Fragment.decode(
                bytes = plaintext,
                offset = TIMESTAMPS_SIZE,
                length = plaintext.size - TIMESTAMPS_SIZE,
            ) ?: return null
            return Datagram(
                direction = direction,
                sequence = header and DIRECTION_BIT.inv(),
                timestamp = plaintext.readShortAt(0),
                timestampReply = plaintext.readShortAt(Short.SIZE_BYTES),
                fragment = fragment,
            )
        }

        private fun header(direction: Direction, sequence: Long): Long =
            if (direction == Direction.ToClient) sequence or DIRECTION_BIT else sequence

        private fun nonce(header: Long): ByteArray =
            ByteArray(MoshCipher.NONCE_SIZE).also { nonce -> nonce.writeLongAt(NONCE_ZERO_BYTES, header) }
    }
}
