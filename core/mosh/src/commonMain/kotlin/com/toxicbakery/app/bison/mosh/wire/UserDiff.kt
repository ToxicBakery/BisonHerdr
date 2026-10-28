package com.toxicbakery.app.bison.mosh.wire

/** One event of the user stream that the client sends (wire fact 7). */
sealed interface UserEvent {

    /** The bytes of keys that the user typed or pasted. */
    class Keys(val bytes: ByteArray) : UserEvent {
        override fun equals(other: Any?): Boolean = other is Keys && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Keys(${bytes.size} bytes)"
    }

    /** The size of the terminal in cells. */
    data class Resize(val columns: Int, val rows: Int) : UserEvent {
        init {
            require(columns >= 0 && rows >= 0) { "The size ${columns}x$rows is not valid." }
        }
    }
}

/**
 * The diff of a client [Instruction]: the user events after its `fromState` (wire fact 7).
 *
 * The wire form is a protobuf message with field 1 repeated. Each entry has field 2 or field 3. Field 2 is
 * [UserEvent.Keys], with the bytes in field 4. Field 3 is [UserEvent.Resize], with the columns in field 5 and the
 * rows in field 6.
 */
data class UserDiff(val events: List<UserEvent>) {

    /** Returns the wire form. */
    fun encode(): ByteArray {
        val writer = ProtoWriter()
        for (event in events) {
            val entry = when (event) {
                is UserEvent.Keys -> messageOf(FIELD_KEYS, messageOf(FIELD_KEY_BYTES, event.bytes))
                is UserEvent.Resize -> messageOf(FIELD_RESIZE, encodeSize(event.columns, event.rows))
            }
            writer.bytes(FIELD_ENTRY, entry)
        }
        return writer.toByteArray()
    }

    companion object {
        private const val FIELD_ENTRY = 1
        private const val FIELD_KEYS = 2
        private const val FIELD_RESIZE = 3
        private const val FIELD_KEY_BYTES = 4

        /**
         * Reads the wire form. The reader skips unknown fields and entries with no known event. Returns null when
         * [bytes] is not a valid message.
         */
        fun decode(bytes: ByteArray): UserDiff? = try {
            val events = ArrayList<UserEvent>()
            forEachEntry(bytes, FIELD_ENTRY) { field, value ->
                when (field) {
                    FIELD_KEYS -> events += UserEvent.Keys(readBytesField(value, FIELD_KEY_BYTES))
                    FIELD_RESIZE -> {
                        val (columns, rows) = decodeSize(value)
                        events += UserEvent.Resize(columns, rows)
                    }
                }
            }
            UserDiff(events)
        } catch (_: ProtoException) {
            null
        }
    }
}
