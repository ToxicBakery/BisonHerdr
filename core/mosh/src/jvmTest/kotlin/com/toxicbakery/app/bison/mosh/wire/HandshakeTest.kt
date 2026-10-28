package com.toxicbakery.app.bison.mosh.wire

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The wire format against a real `mosh-server` (wire facts 1 to 8). A plain [DatagramSocket] sends user states and
 * reads the replies. The test client has no state synchronization: it sends each state again until the server
 * acknowledges it, and it never acknowledges a host state.
 */
class HandshakeTest {

    @Test
    fun theFirstReplyOpensAndAcknowledgesTheSizeState() {
        val server = MoshServerFixture.start("sh", "-c", "IFS= read -r _ && exec cat")
        server.use {
            WireClient(server).use { client ->
                val reply = client.sendUntilAcknowledged(userState(1uL, UserEvent.Resize(60, 12)))

                assertEquals(1, client.replyCount, "The first reply acknowledges the state.")
                // The first instruction of the server has the ID 1 (wire fact 18).
                assertEquals(1uL, reply.datagram.fragment.instructionId)
                assertEquals(Instruction.VERSION, reply.instruction.version)
                assertEquals(0uL, reply.instruction.fromState)
                assertTrue(reply.instruction.toState > 0uL)
                assertEquals(1uL, reply.instruction.ackState)
                // The reply holds one of the timestamps of the client, plus the time that the server held it.
                assertTrue(
                    client.sentTimestamps.any { sent -> (reply.datagram.timestampReply - sent).mod(1 shl 16) < 1000 },
                    "timestamp reply ${reply.datagram.timestampReply}, sent ${client.sentTimestamps}",
                )
                val events = assertNotNull(HostDiff.decode(reply.instruction.diff)).events
                assertContains(events, HostEvent.Resize(60, 12))
                assertTrue(events.any { event -> event is HostEvent.HostBytes && event.bytes.isNotEmpty() }, "$events")
                assertEquals(0, client.rejectedCount)
            }
        }
        assertTrue(server.isStopped, "mosh-server ${server.pid} is still alive.")
    }

    @Test
    fun keysReachTheProgramAndTheSizeHasTheColumnsInFieldFiveAndTheRowsInFieldSix() {
        // The program waits for one line, and then prints the rows and the columns of its terminal.
        val server = MoshServerFixture.start("sh", "-c", "IFS= read -r _ && stty size | tr ' ' x && exec cat")
        server.use {
            WireClient(server).use { client ->
                client.sendUntilAcknowledged(userState(1uL, UserEvent.Resize(60, 12)))
                client.sendUntilAcknowledged(userState(2uL, UserEvent.Keys("\n".encodeToByteArray())))

                val events = client.receiveEventsUntil { seen ->
                    seen.hostText().contains("12x60") && seen.echoAcks().any { state -> state >= 2uL }
                }
                assertContains(events.hostText(), "12x60")
                // The echo ack holds a user state number.
                assertTrue(events.echoAcks().any { state -> state >= 2uL }, "$events")
                assertTrue(events.echoAcks().all { state -> state <= 2uL }, "$events")
                assertEquals(0, client.rejectedCount)
            }
        }
        assertTrue(server.isStopped, "mosh-server ${server.pid} is still alive.")
    }

    @Test
    fun aUserStateInSeveralFragmentsReachesTheServer() {
        val server = MoshServerFixture.start("sh", "-c", "exec cat > /dev/null")
        server.use {
            WireClient(server).use { client ->
                client.sendUntilAcknowledged(userState(1uL, UserEvent.Resize(80, 24)))
                val random = Random(SEED)
                val text = buildString {
                    repeat(PASTE_LINES) {
                        repeat(PASTE_LINE_LENGTH) { append('a' + random.nextInt(LETTERS)) }
                        append('\n')
                    }
                }
                val paste = userState(2uL, UserEvent.Keys(text.encodeToByteArray()))
                assertTrue(client.fragmentCount(paste) > 1)

                assertEquals(2uL, client.sendUntilAcknowledged(paste).instruction.ackState)
                val events = client.receiveEventsUntil { seen -> 2uL in seen.echoAcks() }
                assertContains(events.echoAcks(), 2uL)
                assertEquals(0, client.rejectedCount)
            }
        }
        assertTrue(server.isStopped, "mosh-server ${server.pid} is still alive.")
    }

    /** A user state that adds [event] to the state before it. The client has no host state. */
    private fun userState(state: ULong, event: UserEvent) = Instruction(
        fromState = state - 1uL,
        toState = state,
        ackState = 0uL,
        keepState = state - 1uL,
        diff = UserDiff(listOf(event)).encode(),
    )

    private fun List<HostEvent>.hostText(): String =
        filterIsInstance<HostEvent.HostBytes>().joinToString("") { event -> event.bytes.decodeToString() }

    private fun List<HostEvent>.echoAcks(): List<ULong> = filterIsInstance<HostEvent.EchoAck>().map { it.state }

    private class Reply(val datagram: Datagram, val instruction: Instruction)

    /** Sends instructions to one [MoshServerFixture] and reads its replies. */
    private class WireClient(private val server: MoshServerFixture) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = RECEIVE_TIMEOUT.inWholeMilliseconds.toInt()
        }
        private val cipher = MoshCipher(server.key)
        private val zlib = Zlib()
        private val assembler = FragmentAssembler()
        private val start = TimeSource.Monotonic.markNow()
        private var sequence = 0L
        private var instructionId = 0uL
        private val buffer = ByteArray(MAX_DATAGRAM_SIZE)

        /** The timestamps of all sent datagrams. */
        val sentTimestamps = mutableListOf<Int>()

        /** The count of received datagrams that did not open. */
        var rejectedCount = 0
            private set

        /** The count of complete instructions that the client got. */
        var replyCount = 0
            private set

        /** The host events of all replies, in the order of arrival. */
        private val events = mutableListOf<HostEvent>()

        /** The instruction ID of the newest fragment from the server. */
        private var lastInstructionId = 0uL

        /** Returns the count of fragments that [instruction] needs. */
        fun fragmentCount(instruction: Instruction): Int =
            Fragment.split(0uL, zlib.compress(instruction.encode())).size

        /** Sends [instruction] each [RESEND_INTERVAL] until a reply acknowledges its `toState`. */
        fun sendUntilAcknowledged(instruction: Instruction): Reply {
            val deadline = TimeSource.Monotonic.markNow() + TIMEOUT
            while (deadline.hasNotPassedNow()) {
                send(instruction)
                val resend = TimeSource.Monotonic.markNow() + RESEND_INTERVAL
                while (resend.hasNotPassedNow()) {
                    val reply = receive() ?: continue
                    if (reply.instruction.ackState >= instruction.toState) return reply
                }
            }
            throw AssertionError("No reply acknowledged the state ${instruction.toState} in $TIMEOUT.")
        }

        /**
         * Reads replies until [done] is true for the host events of all replies, or until [TIMEOUT] ends. Returns
         * the host events of all replies, also of the replies that [sendUntilAcknowledged] read.
         */
        fun receiveEventsUntil(done: (List<HostEvent>) -> Boolean): List<HostEvent> {
            val deadline = TimeSource.Monotonic.markNow() + TIMEOUT
            while (deadline.hasNotPassedNow() && !done(events)) receive()
            return events.toList()
        }

        private fun send(instruction: Instruction) {
            val timestamp = (start.elapsedNow().inWholeMilliseconds + TIMESTAMP_OFFSET).toInt() and TIMESTAMP_MASK
            sentTimestamps += timestamp
            for (fragment in Fragment.split(instructionId++, zlib.compress(instruction.encode()))) {
                val packet = Datagram(Direction.ToServer, sequence++, timestamp, Datagram.NO_TIMESTAMP, fragment)
                    .seal(cipher)
                socket.send(DatagramPacket(packet, packet.size, server.address))
            }
        }

        /** Returns the next complete instruction, or null when none comes in [RECEIVE_TIMEOUT]. */
        private fun receive(): Reply? {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val datagram = Datagram.open(buffer, cipher, Direction.ToClient, packet.length)
            if (datagram == null) {
                rejectedCount++
                return null
            }
            // The server gives each instruction a higher ID (wire fact 18). FragmentAssembler depends on this
            // order. The loopback path does not change the order of datagrams.
            val instructionId = datagram.fragment.instructionId
            assertTrue(instructionId >= lastInstructionId, "ID $instructionId came after ID $lastInstructionId.")
            lastInstructionId = instructionId
            val stream = assembler.add(datagram.fragment) ?: return null
            val encoded = assertNotNull(zlib.decompress(stream, Instruction.MAX_SIZE))
            val instruction = assertNotNull(Instruction.decode(encoded))
            replyCount++
            events += assertNotNull(HostDiff.decode(instruction.diff)).events
            return Reply(datagram, instruction)
        }

        override fun close() = socket.close()
    }

    private companion object {
        val TIMEOUT = 5.seconds
        val RESEND_INTERVAL = 250.milliseconds
        val RECEIVE_TIMEOUT = 50.milliseconds
        const val MAX_DATAGRAM_SIZE = 65_536
        const val TIMESTAMP_OFFSET = 1000
        const val TIMESTAMP_MASK = 0xFFFF
        const val SEED = 4
        const val PASTE_LINES = 100
        const val PASTE_LINE_LENGTH = 60
        const val LETTERS = 26
    }
}
