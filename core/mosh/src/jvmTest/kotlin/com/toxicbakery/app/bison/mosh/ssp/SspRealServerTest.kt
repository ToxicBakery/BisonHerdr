package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.MoshServerFixture
import com.toxicbakery.app.bison.mosh.wire.UserEvent
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
            val server = MoshServerFixture.start("sh", "-c", "stty raw -echo && exec cat > \"$1\"", "sh", "$output")
            val typed = StringBuilder()
            server.use {
                Driver(server, lossRate = 0.25, duplicateRate = 0.1, seed = 22).use { driver ->
                    val random = Random(SEED)
                    driver.transport.push(UserEvent.Resize(80, 24))
                    assertTrue(driver.runUntil(TIMEOUT) { driver.transport.lastContactAtMs != null })
                    repeat(CHUNKS) {
                        val chunk = buildString { repeat(random.nextInt(1, 40)) { append('a' + random.nextInt(26)) } }
                        typed.append(chunk)
                        driver.transport.push(UserEvent.Keys(chunk.encodeToByteArray()))
                        driver.runFor(random.nextLong(5, 60).milliseconds)
                    }
                    val stream = driver.transport.userStream
                    assertTrue(driver.runUntil(TIMEOUT) { stream.echoedCount == stream.pushedCount }, driver.report())
                    assertEquals(stream.pushedCount, stream.acknowledgedCount)
                assertTrue(driver.transport.hostStates.newest.state > 1, driver.report())
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
                // The first reply of the server gives a sample. An idle server sends no timestamp reply.
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

        // The screen of a host state is the count of diffs that made it. The emulator comes in a later change.
        val transport = SspTransport(MoshCipher(server.key), HostStateStore(0) { count, _ -> count + 1 }, nowMs())

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
                if (random.nextDouble() < lossRate) {
                    droppedCount++
                    continue
                }
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
                if (!transport.receive(buffer, nowMs(), packet.length).isAuthentic) rejectedCount++
            }
        }

        override fun close() = socket.close()
    }

    private companion object {
        val TIMEOUT = 10.seconds
        val IDLE_TIME = 7.seconds
        const val SEED = 7
        const val CHUNKS = 60
        const val MAX_DATAGRAM_SIZE = 65_536
        const val MAX_WAIT_MS = 20L
    }
}
