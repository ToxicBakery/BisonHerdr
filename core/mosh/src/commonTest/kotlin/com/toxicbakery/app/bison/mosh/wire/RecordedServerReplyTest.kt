package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The full receive path on the first reply of a real `mosh-server` 1.4.0 (wire facts 1 to 8).
 *
 * A black-box test client sent one size state (20x4, user state 1) to `mosh-server new -i 127.0.0.1`. It recorded
 * the key of the banner and the first datagram that came back. The server stopped after the test, so the key
 * protects nothing.
 */
class RecordedServerReplyTest {

    @Test
    fun theFirstReplyOpensAndDecodesToAFullPaintAtTheSizeOfTheClient() {
        val key = assertNotNull(MoshKey.parse(KEY))
        val datagram = assertNotNull(Datagram.open(PACKET.hexToByteArray(), MoshCipher(key), Direction.ToClient))
        assertEquals(0L, datagram.sequence)
        assertEquals(0x65F3, datagram.timestamp)
        // The client sent the timestamp 1000. The reply came back at once.
        assertEquals(1000, datagram.timestampReply)

        val fragment = datagram.fragment
        assertEquals(1uL, fragment.instructionId)
        assertEquals(0, fragment.number)
        assertTrue(fragment.isFinal)

        val stream = assertNotNull(FragmentAssembler().add(fragment))
        val encoded = assertNotNull(Zlib().decompress(stream, Instruction.MAX_SIZE))
        val instruction = assertNotNull(Instruction.decode(encoded))
        assertEquals(Instruction.VERSION, instruction.version)
        assertEquals(0uL, instruction.fromState)
        assertEquals(1uL, instruction.toState)
        assertEquals(1uL, instruction.ackState)
        assertEquals(0uL, instruction.keepState)
        assertEquals(2, instruction.noise.size)

        val events = assertNotNull(HostDiff.decode(instruction.diff)).events
        assertEquals(listOf<HostEvent>(HostEvent.Resize(20, 4)), events.take(1))
        val paint = assertIs<HostEvent.HostBytes>(events[1]).bytes.decodeToString()
        assertTrue(paint.startsWith("\u001b[r\u001b[0m\u001b[H\u001b[2J"), paint)
        assertEquals(2, events.size)
    }

    private companion object {
        const val KEY = "T9a8NvGVz5DSmILxXQG6Nw"
        const val PACKET = "80000000000000000cdc9e91f74388ddbf463383f2acc6fbea79a1f8fd041492cf155bbad3f9e05d" +
            "9fdec0a77d7b844ea18db4afd78916696ba399eb08d55f416c4fcf9b7ad0e131374962729a0f3e1d" +
            "1698c4598682ffa0d50c715957dbd7ce2e9d59dfcaee50879e3633a4a3cc1a4d26f4e85b0d97119f" +
            "ce5895a4680fa000d3f44076a2159b58422459eec0e8bc6e3f"
    }
}
