package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The instruction message with the field names of this app (wire fact 6). */
class InstructionTest {

    @Test
    fun anInstructionEncodesFieldsOneToSixInOrder() {
        val instruction = Instruction(
            fromState = 1uL,
            toState = 2uL,
            ackState = 3uL,
            keepState = 4uL,
            diff = byteArrayOf(0x41),
        )
        assertContentEquals("0802100118022003280432 0141".replace(" ", "").hexToByteArray(), instruction.encode())
    }

    @Test
    fun anInstructionWithZeroStatesAndNoDiffStillHasFieldsOneToSix() {
        val instruction =
            Instruction(fromState = 0uL, toState = 0uL, ackState = 0uL, keepState = 0uL, diff = ByteArray(0))
        assertContentEquals("080210001800200028003200".hexToByteArray(), instruction.encode())
    }

    @Test
    fun noiseIsFieldSeven() {
        val instruction = Instruction(
            fromState = 0uL,
            toState = 0uL,
            ackState = 0uL,
            keepState = 0uL,
            diff = ByteArray(0),
            noise = byteArrayOf(1, 2),
        )
        assertContentEquals("0802100018002000280032003A020102".hexToByteArray(), instruction.encode())
    }

    @Test
    fun anInstructionDecodesToTheSameValues() {
        val instruction = Instruction(
            fromState = 7uL,
            toState = ULong.MAX_VALUE,
            ackState = 300uL,
            keepState = 6uL,
            diff = "diff".encodeToByteArray(),
            noise = ByteArray(17) { index -> index.toByte() },
        )
        assertEquals(instruction, Instruction.decode(instruction.encode()))
    }

    @Test
    fun theDecoderSkipsUnknownFieldsAndUsesZeroForAMissingField() {
        // Field 3 is 5, field 15 is an unknown varint, field 16 is unknown bytes, and field 6 is "x".
        val decoded = assertNotNull(Instruction.decode("1805 7801 82010161 320178".replace(" ", "").hexToByteArray()))
        assertEquals(0uL, decoded.version)
        assertEquals(0uL, decoded.fromState)
        assertEquals(5uL, decoded.toState)
        assertContentEquals("x".encodeToByteArray(), decoded.diff)
        assertEquals(0, decoded.noise.size)
    }

    @Test
    fun badBytesDoNotDecode() {
        assertNull(Instruction.decode("0896".hexToByteArray()))
        assertNull(Instruction.decode("3205".hexToByteArray()))
        assertNull(Instruction.decode("0B".hexToByteArray()))
    }

    @Test
    fun theEmptyMessageDecodesToZeroValues() {
        val decoded = assertNotNull(Instruction.decode(ByteArray(0)))
        assertEquals(Instruction(0uL, 0uL, 0uL, 0uL, 0uL, ByteArray(0)), decoded)
    }
}
