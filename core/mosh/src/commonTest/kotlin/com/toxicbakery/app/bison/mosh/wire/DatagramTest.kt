package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The datagram layout, the direction bit, and the nonce (wire facts 2, 3, and 4). */
class DatagramTest {

    private val cipher = MoshCipher(MoshKey.of("000102030405060708090A0B0C0D0E0F".hexToByteArray()))

    @Test
    fun aClientDatagramSealsToTheKnownAnswer() {
        // An independent OCB code (OpenSSL through Python) made this answer from the layout of wire facts 2 to 5.
        val datagram = Datagram(
            direction = Direction.ToServer,
            sequence = 5,
            timestamp = 0x1234,
            timestampReply = Datagram.NO_TIMESTAMP,
            fragment = Fragment(instructionId = 7uL, number = 0, isFinal = true, content = "abc".encodeToByteArray()),
        )
        assertContentEquals(
            "00000000000000053CD12C59E538B86B6542EE4D373B15A5642CC0CFE1C881FD900CEF4CB82ED8E150".hexToByteArray(),
            datagram.seal(cipher),
        )
    }

    @Test
    fun theHeaderHoldsTheDirectionInBit63AndTheSequenceInTheOtherBits() {
        val toServer = sample(Direction.ToServer, sequence = 0x0102030405060708).seal(cipher)
        assertContentEquals("0102030405060708".hexToByteArray(), toServer.copyOf(Datagram.HEADER_SIZE))
        val toClient = sample(Direction.ToClient, sequence = 0x0102030405060708).seal(cipher)
        assertContentEquals("8102030405060708".hexToByteArray(), toClient.copyOf(Datagram.HEADER_SIZE))
    }

    @Test
    fun theNonceIsFourZeroBytesAndTheHeader() {
        val packet = sample(Direction.ToClient, sequence = 9).seal(cipher)
        val nonce = ByteArray(4) + packet.copyOf(Datagram.HEADER_SIZE)
        val plaintext = assertNotNull(cipher.open(nonce, packet.copyOfRange(Datagram.HEADER_SIZE, packet.size)))
        // The timestamp, the timestamp reply, the instruction ID, the number word, and the content.
        assertContentEquals("0102FFFF00000000000000038000".hexToByteArray() + "xyz".encodeToByteArray(), plaintext)
    }

    @Test
    fun aDatagramOpensToTheSameValues() {
        for (direction in Direction.entries) {
            val datagram = sample(direction, sequence = Long.MAX_VALUE)
            val opened = assertNotNull(Datagram.open(datagram.seal(cipher), cipher, direction))
            assertEquals(direction, opened.direction)
            assertEquals(Long.MAX_VALUE, opened.sequence)
            assertEquals(0x0102, opened.timestamp)
            assertEquals(Datagram.NO_TIMESTAMP, opened.timestampReply)
            assertEquals(datagram.fragment, opened.fragment)
        }
    }

    @Test
    fun aDatagramOfTheOtherDirectionDoesNotOpen() {
        val packet = sample(Direction.ToServer, sequence = 1).seal(cipher)
        assertNull(Datagram.open(packet, cipher, Direction.ToClient))
    }

    @Test
    fun aChangedByteInTheHeaderTheCiphertextOrTheTagDoesNotOpen() {
        val packet = sample(Direction.ToClient, sequence = 3).seal(cipher)
        for (index in packet.indices) {
            val changed = packet.copyOf().also { bytes -> bytes[index] = (bytes[index].toInt() xor 0x01).toByte() }
            assertNull(Datagram.open(changed, cipher, Direction.ToClient), "byte $index")
        }
    }

    @Test
    fun aShortPacketOrADifferentKeyDoesNotOpen() {
        val packet = sample(Direction.ToClient, sequence = 3).seal(cipher)
        for (size in 0 until Datagram.OVERHEAD + Fragment.HEADER_SIZE) {
            assertNull(Datagram.open(packet.copyOf(size), cipher, Direction.ToClient), "size $size")
        }
        assertNull(Datagram.open(packet, MoshCipher(MoshKey.of(ByteArray(MoshKey.SIZE))), Direction.ToClient))
    }

    @Test
    fun aPacketOpensFromTheStartOfALargerBuffer() {
        val packet = sample(Direction.ToClient, sequence = 3).seal(cipher)
        val buffer = packet + ByteArray(100) { 0x55 }
        assertNotNull(Datagram.open(buffer, cipher, Direction.ToClient, length = packet.size))
        assertNull(Datagram.open(buffer, cipher, Direction.ToClient))
        assertNull(Datagram.open(packet, cipher, Direction.ToClient, length = packet.size + 1))
    }

    @Test
    fun aFragmentOfTheLargestContentGivesADatagramOfTheLargestSize() {
        val fragment = Fragment(1uL, 0, isFinal = true, content = ByteArray(Fragment.MAX_CONTENT_SIZE))
        val datagram = Datagram(Direction.ToServer, 0, 0, Datagram.NO_TIMESTAMP, fragment)
        assertEquals(Datagram.MAX_SEND_SIZE, datagram.seal(cipher).size)
    }

    @Test
    fun aValueOutsideItsRangeIsAnError() {
        val fragment = Fragment(1uL, 0, isFinal = true, content = ByteArray(0))
        assertFailsWith<IllegalArgumentException> { Datagram(Direction.ToServer, -1, 0, 0, fragment) }
        assertFailsWith<IllegalArgumentException> { Datagram(Direction.ToServer, 0, 0x10000, 0, fragment) }
        assertFailsWith<IllegalArgumentException> { Datagram(Direction.ToServer, 0, 0, -1, fragment) }
    }

    private fun sample(direction: Direction, sequence: Long) = Datagram(
        direction = direction,
        sequence = sequence,
        timestamp = 0x0102,
        timestampReply = Datagram.NO_TIMESTAMP,
        fragment = Fragment(instructionId = 3uL, number = 0, isFinal = true, content = "xyz".encodeToByteArray()),
    )
}
