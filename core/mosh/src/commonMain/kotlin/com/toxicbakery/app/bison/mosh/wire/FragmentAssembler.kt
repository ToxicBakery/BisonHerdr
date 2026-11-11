package com.toxicbakery.app.bison.mosh.wire

/**
 * Joins the fragments of each instruction ID into the zlib stream of one instruction (wire fact 5).
 *
 * Give it only the fragments of authentic datagrams. The fragments of one ID can come in any order. A second copy
 * of a part has no effect, but a second copy of a complete instruction gives its stream again. These limits keep
 * the memory small when the peer sends bad fragments:
 * - The assembler does not keep a fragment with a number of [maxFragments] or more.
 * - It removes an ID that has more than [maxStreamSize] bytes of content.
 * - It keeps the parts of the [maxInstructions] newest IDs. The ID of a server instruction is never lower than the
 *   ID before it, so the newest ID is the highest ID. A second copy with the same ID has no effect on the parts.
 * - It removes an ID with parts that do not agree, for example two final fragments with different numbers.
 *
 * Use one assembler for each session. It is not safe for use by two threads at the same time.
 */
class FragmentAssembler(
    private val maxInstructions: Int = MAX_INSTRUCTIONS,
    private val maxFragments: Int = MAX_FRAGMENTS,
    private val maxStreamSize: Int = Instruction.MAX_SIZE,
) {
    private val partials = HashMap<ULong, Partial>()

    /** The count of IDs that have parts and are not complete. */
    val pendingCount: Int get() = partials.size

    /** Adds [fragment]. Returns the joined stream when [fragment] completes its instruction, else null. */
    fun add(fragment: Fragment): ByteArray? {
        if (fragment.number >= maxFragments) return null
        // A single fragment needs no slot, so it completes also when all slots hold newer IDs.
        if (fragment.number == 0 && fragment.isFinal && fragment.instructionId !in partials) {
            return fragment.content.takeIf { content -> content.size <= maxStreamSize }
        }
        val id = fragment.instructionId
        val partial = partials[id] ?: newPartial(id) ?: return null
        return when (partial.add(fragment, maxStreamSize)) {
            Result.Waiting -> null
            Result.Rejected -> {
                partials.remove(id)
                null
            }
            Result.Complete -> {
                partials.remove(id)
                partial.join()
            }
        }
    }

    private fun newPartial(id: ULong): Partial? {
        if (partials.size >= maxInstructions) {
            val oldest = partials.keys.min()
            if (id < oldest) return null
            partials.remove(oldest)
        }
        return Partial().also { partial -> partials[id] = partial }
    }

    private enum class Result { Waiting, Rejected, Complete }

    private class Partial {
        private val parts = HashMap<Int, ByteArray>()
        private var finalNumber = NO_FINAL
        private var size = 0

        fun add(fragment: Fragment, maxStreamSize: Int): Result {
            val number = fragment.number
            if (number in parts) {
                // A duplicate must agree on the final flag.
                return if (fragment.isFinal == (number == finalNumber)) Result.Waiting else Result.Rejected
            }
            if (finalNumber != NO_FINAL && (fragment.isFinal || number > finalNumber)) return Result.Rejected
            if (fragment.isFinal) {
                if (parts.keys.any { other -> other > number }) return Result.Rejected
                finalNumber = number
            }
            size += fragment.content.size
            if (size > maxStreamSize) return Result.Rejected
            parts[number] = fragment.content
            return if (finalNumber != NO_FINAL && parts.size == finalNumber + 1) Result.Complete else Result.Waiting
        }

        fun join(): ByteArray {
            val stream = ByteArray(size)
            var offset = 0
            for (number in 0..finalNumber) {
                val content = parts.getValue(number)
                content.copyInto(stream, offset)
                offset += content.size
            }
            return stream
        }
    }

    companion object {
        /** The count of IDs that the assembler keeps parts for. */
        const val MAX_INSTRUCTIONS: Int = 4

        /**
         * The count of fragments that one instruction can have. A 300x150 truecolor full paint used 474 fragments.
         */
        const val MAX_FRAGMENTS: Int = 1024

        private const val NO_FINAL = -1
    }
}
