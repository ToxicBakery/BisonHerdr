package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.Datagram
import com.toxicbakery.app.bison.mosh.wire.Direction
import com.toxicbakery.app.bison.mosh.wire.HostEvent
import com.toxicbakery.app.bison.mosh.wire.Instruction
import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.MoshServerFixture
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import com.toxicbakery.app.bison.mosh.wire.Zlib
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [SspTransport] against a real `mosh-server`. A plain [DatagramSocket] and a small loop drive the transport. The
 * loop can drop and duplicate datagrams in both directions.
 */
class SspRealServerTest {

    @Test
    fun eachKeyReachesTheProgramOneTimeWhenTheNetworkDropsAndDuplicatesDatagrams() {
        val output = createTempFile("bison-ssp", ".txt")
        try {
            // The program writes each byte that it gets to the file, with no change.
            val server = MoshServerFixture.start("sh", "-c", RAW_CAT, "sh", "$output")
            val typed = StringBuilder()
            server.use {
                Driver(server, lossRate = 0.25, duplicateRate = 0.1, seed = 22).use { driver ->
                    val random = Random(SEED)
                    driver.transport.push(UserEvent.Resize(80, 24))
                    assertTrue(driver.runUntil(TIMEOUT) { driver.isReady }, driver.report())
                    repeat(CHUNKS) {
                        val chunk = buildString { repeat(random.nextInt(1, 40)) { append('a' + random.nextInt(26)) } }
                        typed.append(chunk)
                        driver.transport.push(UserEvent.Keys(chunk.encodeToByteArray()))
                        driver.runFor(random.nextLong(5, 60).milliseconds)
                    }
                    val stream = driver.transport.userStream
                    assertTrue(driver.runUntil(TIMEOUT) { stream.echoedCount == stream.pushedCount }, driver.report())
                    assertEquals(stream.pushedCount, stream.acknowledgedCount)
                    assertTrue(driver.transport.hostStates.newest.number > 1uL, driver.report())
                    assertTrue(driver.droppedCount > 0 && driver.duplicatedCount > 0, driver.report())
                    assertEquals(0, driver.rejectedCount)
                    // Give cat time to write the last bytes of the echo ack.
                    driver.runFor(300.milliseconds)
                }
            }
            assertEquals(typed.toString(), output.readText())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun aPasteOf128KiBCompletesWhenTheNetworkDropsTenPercentOfTheDatagrams() {
        val output = createTempFile("bison-ssp", ".txt")
        try {
            val server = MoshServerFixture.start("sh", "-c", RAW_CAT, "sh", "$output")
            val random = Random(SEED)
            val paste = String(CharArray(PASTE_SIZE) { 'a' + random.nextInt(26) })
            server.use {
                Driver(server, lossRate = 0.1, seed = 3).use { driver ->
                    driver.transport.push(UserEvent.Resize(80, 24))
                    assertTrue(driver.runUntil(TIMEOUT) { driver.isReady }, driver.report())
                    val stream = driver.transport.userStream
                    val pasteIndex = driver.transport.push(UserEvent.Keys(paste.encodeToByteArray()))
                    driver.runFor(100.milliseconds)
                    // A key after the paste, for example Ctrl+C. It must not wait long behind the paste.
                    val keyIndex = driver.transport.push(UserEvent.Keys("Z".encodeToByteArray()))
                    assertTrue(driver.runUntil(PASTE_TIMEOUT) { stream.echoedCount >= pasteIndex }, driver.report())
                    assertTrue(driver.runUntil(2.seconds) { stream.echoedCount >= keyIndex }, driver.report())
                    assertTrue(driver.droppedCount > 0, driver.report())
                    driver.runFor(300.milliseconds)
                }
            }
            assertEquals(paste + "Z", output.readText())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun aLostAckGetsAnAnswerAtTheFirstResendOfTheServer() {
        val server = MoshServerFixture.start("sh", "-c", RAW_CAT, "sh", "/dev/null")
        server.use {
            Driver(server).use { driver ->
                driver.transport.push(UserEvent.Resize(80, 24))
                assertTrue(driver.runUntil(TIMEOUT) { driver.isReady }, driver.report())
                driver.runFor(500.milliseconds)
                // The echo ack of the key makes a new host state. The driver drops the delayed ack of that state.
                driver.dropNextAck = true
                driver.transport.push(UserEvent.Keys("a".encodeToByteArray()))
                assertTrue(driver.runUntil(TIMEOUT) { driver.droppedAckAtMs != null }, driver.report())
                val droppedAt = checkNotNull(driver.droppedAckAtMs)
                driver.runFor(2.seconds)
                // The server sends its state again about 150 ms to 250 ms later, and the client answers it at once.
                // With no answer, the server sends a copy about each 150 ms until the 3 s heartbeat of the client.
                val copies = driver.receivedCopies.filter { time -> time > droppedAt }
                val nextSend = driver.sendTimes.first { time -> time > droppedAt }
                assertTrue(copies.size in 1..2, "copies at $copies after the drop at $droppedAt")
                assertTrue(nextSend - copies.first() <= 20, "copy at ${copies.first()}, next send at $nextSend")
                assertTrue(nextSend - droppedAt < 500, "drop at $droppedAt, next send at $nextSend")
            }
        }
    }

    @Test
    fun theEndStateEndsTheSessionAndTheAckStopsTheServerAtOnce() {
        val server = MoshServerFixture.start("sh", "-c", "IFS= read -r _; exit 0")
        server.use {
            Driver(server).use { driver ->
                driver.transport.push(UserEvent.Resize(40, 10))
                assertTrue(driver.runUntil(TIMEOUT) { driver.transport.lastContactAtMs != null })
                driver.transport.push(UserEvent.Keys("\n".encodeToByteArray()))
                assertTrue(driver.runUntil(TIMEOUT) { driver.transport.isEnded }, driver.report())
                val endedAt = TimeSource.Monotonic.markNow()
                // Without the ack, the server stops about 4 s after the end state.
                assertTrue(driver.runUntil(1.5.seconds) { server.isStopped }, "stopped: ${server.isStopped}")
                assertTrue(endedAt.elapsedNow() < 1.5.seconds)
                assertEquals(Long.MAX_VALUE, driver.transport.nextDeadlineMs(driver.nowMs()))
            }
        }
    }

    @Test
    fun anIdleSessionKeepsContactWithHeartbeats() {
        val server = MoshServerFixture.start("sh", "-c", "exec cat > /dev/null")
        server.use {
            Driver(server).use { driver ->
                driver.transport.push(UserEvent.Resize(80, 24))
                assertTrue(driver.runUntil(TIMEOUT) { driver.transport.lastContactAtMs != null })
                driver.runFor(1.seconds)
                val contacts = mutableSetOf<Long>()
                val sentBefore = driver.transport.sentDatagramCount
                driver.runUntil(IDLE_TIME) {
                    driver.transport.lastContactAtMs?.let { contact -> contacts += contact }
                    false
                }
                // The server sends a new state about each 3 s (wire fact 12), and the client acknowledges it.
                assertTrue(contacts.size >= 3, "contacts at $contacts")
                val sent = driver.transport.sentDatagramCount - sentBefore
                assertTrue(sent in 2L..6L, "$sent client datagrams in $IDLE_TIME")
                assertTrue(!driver.transport.isRoamDue(driver.nowMs()))
                // The first reply of the server gives a sample. The server drops a client timestamp that it held for
                // about 1 s to 3 s, so an idle session can give no other sample.
                assertNotNull(driver.transport.smoothedRttMs)
            }
        }
    }

    /** Runs a transport on a socket to one server. It drops and duplicates datagrams with a seeded random source. */
    private class Driver(
        server: MoshServerFixture,
        private val lossRate: Double = 0.0,
        private val duplicateRate: Double = 0.0,
        seed: Int = 0,
    ) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val address = server.address
        private val clock = TimeSource.Monotonic.markNow()
        private val random = Random(seed)
        private val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        private val cipher = MoshCipher(server.key)
        private val zlib = Zlib()

        /** When true, the driver drops the next ack of the client. */
        var dropNextAck = false

        /** The time of the dropped ack, or null. */
        var droppedAckAtMs: Long? = null
            private set

        /** The times of the client datagrams that went out. */
        val sendTimes = mutableListOf<Long>()

        /** The times of the authentic server datagrams that gave no new host state. */
        val receivedCopies = mutableListOf<Long>()

        // The host state is the text of all host bytes that made it. The emulator comes in a later change.
        val transport = SspTransport(cipher, HostStateStore("") { text, events -> text + hostText(events) }, nowMs())

        /** True when the program printed [READY], so that the terminal is in raw mode. */
        val isReady: Boolean get() = transport.hostStates.newest.state.contains(READY)

        var droppedCount = 0
            private set
        var duplicatedCount = 0
            private set
        var rejectedCount = 0
            private set

        fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

        fun runFor(time: Duration) {
            runUntil(time) { false }
        }

        /** Runs until [done] is true. Returns false when [timeout] ends first. */
        fun runUntil(timeout: Duration, done: () -> Boolean): Boolean {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (!done()) {
                if (deadline.hasPassedNow()) return false
                step()
            }
            return true
        }

        fun report(): String =
            "sent ${transport.sentDatagramCount}, dropped $droppedCount, duplicated $duplicatedCount, " +
                "pushed ${transport.userStream.pushedCount}, acknowledged ${transport.userStream.acknowledgedCount}, " +
                "echoed ${transport.userStream.echoedCount}, host ${transport.hostStates.newest.number}"

        private fun step() {
            val now = nowMs()
            for (packet in transport.poll(now)) {
                if (dropNextAck && isAck(packet)) {
                    dropNextAck = false
                    droppedAckAtMs = now
                    continue
                }
                if (random.nextDouble() < lossRate) {
                    droppedCount++
                    continue
                }
                sendTimes += now
                val copies = if (random.nextDouble() < duplicateRate) 2 else 1
                if (copies == 2) duplicatedCount++
                repeat(copies) { socket.send(DatagramPacket(packet, packet.size, address)) }
            }
            socket.soTimeout = (transport.nextDeadlineMs(now) - now).coerceIn(1, MAX_WAIT_MS).toInt()
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return
            }
            if (random.nextDouble() < lossRate) {
                droppedCount++
                return
            }
            val copies = if (random.nextDouble() < duplicateRate) 2 else 1
            if (copies == 2) duplicatedCount++
            repeat(copies) {
                val now = nowMs()
                val result = transport.receive(buffer, now, packet.length)
                if (!result.isAuthentic) rejectedCount++
                if (result.isAuthentic && !result.hasNewHostState) receivedCopies += now
            }
        }

        // An ack or a heartbeat: one fragment with no diff, and the same fromState and toState.
        private fun isAck(packet: ByteArray): Boolean {
            val fragment = Datagram.open(packet, cipher, Direction.ToServer)?.fragment ?: return false
            if (fragment.number != 0 || !fragment.isFinal) return false
            val instruction = zlib.decompress(fragment.content, Instruction.MAX_SIZE)?.let(Instruction::decode)
            return instruction != null && instruction.diff.isEmpty() && instruction.fromState == instruction.toState
        }

        override fun close() = socket.close()
    }

    private companion object {
        /** The text that [RAW_CAT] prints when its terminal is in raw mode. */
        const val READY = "BISON-READY"

        /**
         * Writes each byte that it gets to the file "$1", with no change. A terminal in canonical mode holds at most
         * 4095 bytes of a line and drops the others, so the program prints [READY] after stty. A test sends no key
         * before it sees [READY].
         */
        const val RAW_CAT = "stty raw -echo && printf $READY && exec cat > \"$1\""

        fun hostText(events: List<HostEvent>): String =
            events.filterIsInstance<HostEvent.HostBytes>().joinToString("") { event -> event.bytes.decodeToString() }

        val TIMEOUT = 10.seconds
        val PASTE_TIMEOUT = 30.seconds
        const val PASTE_SIZE = 128 * 1024
        val IDLE_TIME = 7.seconds
        const val SEED = 7
        const val CHUNKS = 60
        const val MAX_DATAGRAM_SIZE = 65_536
        const val MAX_WAIT_MS = 20L
    }
}
