package com.toxicbakery.app.bison.mosh.wire

/** One event of a host diff that the server sends (wire fact 8). */
sealed interface HostEvent {

    /** Terminal bytes that change the old client screen into the new screen. */
    class HostBytes(val bytes: ByteArray) : HostEvent {
        override fun equals(other: Any?): Boolean = other is HostBytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "HostBytes(${bytes.size} bytes)"
    }

    /** The size of the screen of the server in cells. */
    data class Resize(val columns: Int, val rows: Int) : HostEvent {
        init {
            require(columns >= 0 && rows >= 0) { "The size ${columns}x$rows is not valid." }
        }
    }

    /** The echo ack. [state] is the number of a user state. */
    data class EchoAck(val state: ULong) : HostEvent
}

/**
 * The diff of a server [Instruction] (wire fact 8).
 *
 * The wire form is a protobuf message with field 1 repeated. Each entry has field 2, field 3, or field 7. Field 2
 * is [HostEvent.HostBytes], with the bytes in field 4. Field 3 is [HostEvent.Resize], with the columns in field 5
 * and the rows in field 6. Field 7 is [HostEvent.EchoAck], with the state in field 8.
 */
data class HostDiff(val events: List<HostEvent>) {

    /** Returns the wire form. The client does not send it. Tests use it. */
    fun encode(): ByteArray {
        val writer = ProtoWriter()
        for (event in events) {
            val entry = when (event) {
                is HostEvent.HostBytes -> messageOf(FIELD_HOST_BYTES, messageOf(FIELD_BYTES, event.bytes))
                is HostEvent.Resize -> messageOf(FIELD_RESIZE, encodeSize(event.columns, event.rows))
                is HostEvent.EchoAck ->
                    messageOf(FIELD_ECHO_ACK, ProtoWriter().varint(FIELD_ECHO_STATE, event.state).toByteArray())
            }
            writer.bytes(FIELD_ENTRY, entry)
        }
        return writer.toByteArray()
    }

    companion object {
        private const val FIELD_ENTRY = 1
        private const val FIELD_HOST_BYTES = 2
        private const val FIELD_RESIZE = 3
        private const val FIELD_BYTES = 4
        private const val FIELD_ECHO_ACK = 7
        private const val FIELD_ECHO_STATE = 8

        /**
         * Reads the wire form. The reader skips unknown fields and entries with no known event. Returns null when
         * [bytes] is not a valid message.
         */
        fun decode(bytes: ByteArray): HostDiff? = try {
            val events = ArrayList<HostEvent>()
            forEachEntry(bytes, FIELD_ENTRY) { field, value ->
                when (field) {
                    FIELD_HOST_BYTES -> events += HostEvent.HostBytes(readBytesField(value, FIELD_BYTES))
                    FIELD_RESIZE -> {
                        val (columns, rows) = decodeSize(value)
                        events += HostEvent.Resize(columns, rows)
                    }
                    FIELD_ECHO_ACK -> events += HostEvent.EchoAck(readVarintField(value, FIELD_ECHO_STATE))
                }
            }
            HostDiff(events)
        } catch (_: ProtoException) {
            null
        }
    }
}
