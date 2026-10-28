package com.toxicbakery.app.bison.mosh.wire

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Random input and changed valid input never make a decoder throw. */
class WireFuzzTest {

    private val random = Random(SEED)
    private val cipher = MoshCipher(MoshKey.of(ByteArray(MoshKey.SIZE) { index -> index.toByte() }))

    @Test
    fun randomBytesNeverMakeADecoderThrow() {
        repeat(ROUNDS) {
            val bytes = random.nextBytes(random.nextInt(0, 200))
            Instruction.decode(bytes)
            UserDiff.decode(bytes)
            HostDiff.decode(bytes)
            Fragment.decode(bytes)
            Zlib().decompress(bytes, 1024)
            assertNull(Datagram.open(bytes, cipher, Direction.ToClient))
        }
    }

    @Test
    fun changedValidInputNeverMakesADecoderThrow() {
        val diff = HostDiff(
            listOf(
                HostEvent.Resize(80, 24),
                HostEvent.HostBytes("\u001b[Hhello".encodeToByteArray()),
                HostEvent.EchoAck(42uL),
            ),
        ).encode()
        val instruction = Instruction(
            fromState = 1uL,
            toState = 2uL,
            ackState = 3uL,
            keepState = 1uL,
            diff = diff,
            noise = byteArrayOf(1, 2, 3),
        ).encode()
        val stream = Zlib().compress(instruction)
        repeat(ROUNDS) {
            for (valid in listOf(diff, instruction, stream)) {
                val changed = change(valid)
                Instruction.decode(changed)
                UserDiff.decode(changed)
                HostDiff.decode(changed)
                Zlib().decompress(changed, Instruction.MAX_SIZE)
            }
        }
    }

    @Test
    fun randomFragmentsNeverMakeTheAssemblerThrowOrGrowPastItsLimit() {
        val assembler = FragmentAssembler()
        repeat(ROUNDS) {
            val fragment = Fragment(
                instructionId = random.nextLong(0, 8).toULong(),
                number = random.nextInt(0, 12),
                isFinal = random.nextInt(4) == 0,
                content = random.nextBytes(random.nextInt(0, 4)),
            )
            assembler.add(fragment)
            assertTrue(assembler.pendingCount <= FragmentAssembler.MAX_INSTRUCTIONS)
        }
    }

    private fun change(valid: ByteArray): ByteArray {
        val bytes = valid.copyOf()
        repeat(random.nextInt(1, 4)) {
            val index = random.nextInt(bytes.size)
            bytes[index] = random.nextInt(256).toByte()
        }
        return if (random.nextBoolean()) bytes else bytes.copyOf(random.nextInt(bytes.size + 1))
    }

    private companion object {
        const val SEED = 2012
        const val ROUNDS = 3000
    }
}
