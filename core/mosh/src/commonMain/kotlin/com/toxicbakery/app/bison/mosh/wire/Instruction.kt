package com.toxicbakery.app.bison.mosh.wire

/**
 * One instruction of the state synchronization protocol (wire fact 6). The wire form is a protobuf message, and
 * each number is an unsigned varint. The names are the names of this app, not the names of Mosh.
 *
 * | Field | Name | Role |
 * |-------|------|------|
 * | 1 | [version] | Always [VERSION] |
 * | 2 | [fromState] | The state that the diff starts from |
 * | 3 | [toState] | The state that the diff makes |
 * | 4 | [ackState] | The newest state of the peer that the sender has |
 * | 5 | [keepState] | The oldest state that the sender can still use as [fromState] |
 * | 6 | [diff] | The diff, a [UserDiff] from the client or a [HostDiff] from the server |
 * | 7 | [noise] | Random bytes with a random length |
 */
class Instruction(
    val version: ULong = VERSION,
    val fromState: ULong,
    val toState: ULong,
    val ackState: ULong,
    val keepState: ULong,
    val diff: ByteArray,
    val noise: ByteArray = ByteArray(0),
) {

    /** Returns the wire form. It always has fields 1 to 6. It has field 7 only when [noise] is not empty. */
    fun encode(): ByteArray {
        val writer = ProtoWriter()
            .varint(FIELD_VERSION, version)
            .varint(FIELD_FROM_STATE, fromState)
            .varint(FIELD_TO_STATE, toState)
            .varint(FIELD_ACK_STATE, ackState)
            .varint(FIELD_KEEP_STATE, keepState)
            .bytes(FIELD_DIFF, diff)
        if (noise.isNotEmpty()) writer.bytes(FIELD_NOISE, noise)
        return writer.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is Instruction &&
            version == other.version &&
            fromState == other.fromState &&
            toState == other.toState &&
            ackState == other.ackState &&
            keepState == other.keepState &&
            diff.contentEquals(other.diff) &&
            noise.contentEquals(other.noise)

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + fromState.hashCode()
        result = 31 * result + toState.hashCode()
        result = 31 * result + ackState.hashCode()
        result = 31 * result + keepState.hashCode()
        result = 31 * result + diff.contentHashCode()
        return 31 * result + noise.contentHashCode()
    }

    override fun toString(): String =
        "Instruction(version=$version, fromState=$fromState, toState=$toState, ackState=$ackState, " +
            "keepState=$keepState, diff=${diff.size} bytes, noise=${noise.size} bytes)"

    companion object {
        /** The protocol version. All instructions in the black-box tests have this value. */
        const val VERSION: ULong = 2uL

        /** The largest wire form after zlib that the client accepts: 8 MiB. */
        const val MAX_SIZE: Int = 8 * 1024 * 1024

        private const val FIELD_VERSION = 1
        private const val FIELD_FROM_STATE = 2
        private const val FIELD_TO_STATE = 3
        private const val FIELD_ACK_STATE = 4
        private const val FIELD_KEEP_STATE = 5
        private const val FIELD_DIFF = 6
        private const val FIELD_NOISE = 7

        /**
         * Reads the wire form. A field that is not there has the value 0 or no bytes. The reader skips unknown
         * fields. Returns null when [bytes] is not a valid protobuf message.
         */
        fun decode(bytes: ByteArray): Instruction? {
            var version = 0uL
            var fromState = 0uL
            var toState = 0uL
            var ackState = 0uL
            var keepState = 0uL
            var diff = ByteArray(0)
            var noise = ByteArray(0)
            val reader = ProtoReader(bytes)
            try {
                while (reader.next()) {
                    when {
                        reader.field == FIELD_VERSION && reader.isVarint -> version = reader.varint()
                        reader.field == FIELD_FROM_STATE && reader.isVarint -> fromState = reader.varint()
                        reader.field == FIELD_TO_STATE && reader.isVarint -> toState = reader.varint()
                        reader.field == FIELD_ACK_STATE && reader.isVarint -> ackState = reader.varint()
                        reader.field == FIELD_KEEP_STATE && reader.isVarint -> keepState = reader.varint()
                        reader.field == FIELD_DIFF && reader.isLengthDelimited -> diff = reader.bytes()
                        reader.field == FIELD_NOISE && reader.isLengthDelimited -> noise = reader.bytes()
                        else -> reader.skip()
                    }
                }
            } catch (_: ProtoException) {
                return null
            }
            return Instruction(version, fromState, toState, ackState, keepState, diff, noise)
        }
    }
}
