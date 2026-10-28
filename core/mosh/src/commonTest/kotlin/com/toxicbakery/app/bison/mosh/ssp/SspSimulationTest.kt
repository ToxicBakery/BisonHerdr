package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The transport and [FakeMoshServer] on a [SimulatedNetwork] with virtual time. The network drops, delays,
 * duplicates, and reorders datagrams.
 */
class SspSimulationTest {

    @Test
    fun eachKeyReachesTheServerOneTimeAndTheScreensConvergeOnABadNetwork() {
        for (seed in SEEDS) {
            val simulation = Simulation(seed, lossRate = 0.25, duplicateRate = 0.1, delayMs = 40, jitterMs = 60)
            val typed = simulation.typeRandomKeys(count = 300, fromMs = 500, toMs = 12_000)
            simulation.run(untilMs = 30_000)

            assertEquals(typed, simulation.server.applicationText, "seed $seed")
            assertEquals(emptyList(), simulation.server.errors, "seed $seed")
            assertEquals(simulation.server.screen, simulation.transport.hostStates.newest.state, "seed $seed")
            val stream = simulation.transport.userStream
            assertEquals(stream.pushedCount, stream.acknowledgedCount, "seed $seed")
            assertEquals(stream.pushedCount, stream.echoedCount, "seed $seed")
            assertTrue(simulation.maxKeptStates <= HostStateStore.MAX_STATES, "seed $seed")
            // The server chained diffs from states that the client did not acknowledge (wire fact 17).
            assertTrue(simulation.maxKeptStates > 1, "seed $seed")
            assertTrue(simulation.transport.smoothedRttMs != null, "seed $seed")
        }
    }

    @Test
    fun aSilenceOpensANewSocketAndTheServerFollowsTheNewAddress() {
        val simulation = Simulation(seed = 5, delayMs = 30)
        var lastContact: Long? = null
        simulation.at(1000) {
            lastContact = simulation.transport.lastContactAtMs
            simulation.network.blockToClient = true
        }
        val typed = simulation.typeRandomKeys(count = 40, fromMs = 2000, toMs = 9000)
        simulation.at(23_000) { simulation.network.blockToClient = false }
        simulation.run(untilMs = 35_000)

        // The first new socket opened 10 s after the last contact, and a second one 10 s after the first.
        assertEquals(assertNotNull(lastContact) + SspTransport.ROAM_SILENCE_MS, simulation.roamTimes[0])
        assertEquals(simulation.roamTimes[0] + SspTransport.ROAM_SILENCE_MS, simulation.roamTimes[1])
        assertEquals(simulation.address, simulation.server.target)
        assertEquals(typed, simulation.server.applicationText)
        assertEquals(simulation.server.screen, simulation.transport.hostStates.newest.state)
    }

    @Test
    fun theEndStateStopsTheSessionAndTheClientSendsNothingAfterItsAck() {
        val simulation = Simulation(seed = 6, lossRate = 0.2, delayMs = 30, jitterMs = 20)
        val typed = simulation.typeRandomKeys(count = 20, fromMs = 500, toMs = 2000)
        simulation.at(4000) { simulation.server.requestEnd() }
        simulation.run(untilMs = 20_000)

        assertTrue(simulation.transport.isEnded)
        assertEquals(typed, simulation.server.applicationText)
        val acknowledgedAt = assertNotNull(simulation.server.endAcknowledgedAtMs)
        assertTrue(acknowledgedAt < 6000, "end state acknowledged at $acknowledgedAt")
        assertEquals(0, simulation.server.datagramsAfterEnd)
    }

    @Test
    fun aPasteOf128KiBCompletesWithLossInEachDirectionAndAKeyAfterItFollowsSoon() {
        for (delayMs in listOf(50L, 150L)) {
            for (seed in 1..3) {
                val simulation = Simulation(seed, lossRate = 0.1, delayMs = delayMs, jitterMs = delayMs / 2)
                val random = Random(seed)
                val paste = String(CharArray(PASTE_SIZE) { 'a' + random.nextInt(LETTERS) })
                simulation.at(1000) { simulation.transport.push(UserEvent.Keys(paste.encodeToByteArray())) }
                simulation.at(1100) { simulation.transport.push(UserEvent.Keys("Z".encodeToByteArray())) }
                val text = { simulation.server.applicationText }
                val run = "delay $delayMs, seed $seed"
                val pasted = assertNotNull(simulation.runUntil(31_000) { text().length >= PASTE_SIZE }, run)
                val keyed = assertNotNull(simulation.runUntil(pasted + 2000) { text().endsWith("Z") }, run)
                assertEquals(paste + "Z", text())
                assertTrue(pasted - 1000 <= 30_000, "$run: the paste took ${pasted - 1000} ms")
                assertTrue(keyed - pasted <= 2000, "$run: the key took ${keyed - pasted} ms more")
                assertEquals(emptyList(), simulation.server.errors)
            }
        }
    }

    @Test
    fun aClientThatTypesWithNoLossSendsFewDatagrams() {
        val simulation = Simulation(seed = 7, delayMs = 25)
        simulation.typeRandomKeys(count = 100, fromMs = 500, toMs = 10_500)
        simulation.run(untilMs = 20_000)
        // About one datagram for each key and each ack, and a heartbeat each 3 s. No storm of resends.
        assertTrue(simulation.network.clientDatagrams < 300, "${simulation.network.clientDatagrams} datagrams")
    }

    /** One client and one server on a simulated network. The loop steps 1 ms at a time. */
    private class Simulation(
        seed: Int,
        lossRate: Double = 0.0,
        duplicateRate: Double = 0.0,
        delayMs: Long = 20,
        jitterMs: Long = 0,
    ) {
        private val random = Random(seed)
        val network = SimulatedNetwork(random, lossRate, duplicateRate, delayMs, jitterMs)
        val transport = SspTransport(MoshCipher(TEST_KEY), screenStore(), startMs = 0)
        val server = FakeMoshServer()
        private val actions = HashMap<Long, MutableList<() -> Unit>>()
        var address = 1
            private set
        val roamTimes = mutableListOf<Long>()
        var maxKeptStates = 0
            private set

        init {
            transport.push(UserEvent.Resize(80, 24))
        }

        fun at(timeMs: Long, action: () -> Unit) {
            actions.getOrPut(timeMs) { mutableListOf() } += action
        }

        /** Types [count] random letters at random times. Returns the text. */
        fun typeRandomKeys(count: Int, fromMs: Long, toMs: Long): String {
            val keys = List(count) { ('a' + random.nextInt(LETTERS)).toString() }
            val times = List(count) { random.nextLong(fromMs, toMs) }.sorted()
            for ((key, time) in keys.zip(times)) at(time) { transport.push(UserEvent.Keys(key.encodeToByteArray())) }
            return keys.joinToString("")
        }

        /** The time of the next step. */
        var nowMs = 0L
            private set

        // The deadline after the last poll, and true when an input came to the transport after that poll.
        private var lastDeadline = 0L
        private var inputSinceDeadline = true

        fun run(untilMs: Long) {
            runUntil(untilMs) { false }
        }

        /** Runs until [done] is true after a step, or until [untilMs]. Returns the time of that step, or null. */
        fun runUntil(untilMs: Long, done: () -> Boolean): Long? {
            while (nowMs <= untilMs) {
                val now = nowMs++
                step(now)
                if (done()) return now
            }
            return null
        }

        private fun step(now: Long) {
            actions[now]?.let { list ->
                list.forEach { action -> action() }
                inputSinceDeadline = true
            }
            network.deliver(
                nowMs = now,
                toServer = { packet, from -> server.receive(packet, from, now) },
                toClient = { packet, to ->
                    if (to == address) {
                        transport.receive(packet, now)
                        inputSinceDeadline = true
                    }
                },
            )
            maxKeptStates = maxOf(maxKeptStates, transport.hostStates.size)
            if (transport.isRoamDue(now)) {
                address++
                roamTimes += now
                transport.onRoamed(now)
                inputSinceDeadline = true
            }
            val deadline = transport.nextDeadlineMs(now)
            val packets = transport.poll(now)
            // The transport never sends before its own deadline.
            check(packets.isEmpty() || now >= deadline) { "A send at $now came before the deadline $deadline." }
            // With no input since the last poll, the deadline of that poll still holds.
            check(packets.isEmpty() || inputSinceDeadline || now >= lastDeadline) {
                "A send at $now came before the earlier deadline $lastDeadline."
            }
            // A deadline that is not in the future gives a send, so that a loop on the deadline does not spin.
            check(deadline > now || packets.isNotEmpty()) { "No send at $now, but the deadline was $deadline." }
            packets.forEach { packet -> network.sendToServer(packet, address, now) }
            server.poll(now).forEach { packet -> network.sendToClient(packet, server.target, now) }
            lastDeadline = transport.nextDeadlineMs(now)
            inputSinceDeadline = false
        }
    }

    private companion object {
        val SEEDS = listOf(1, 2, 3, 4)
        const val LETTERS = 26
        const val PASTE_SIZE = 128 * 1024
    }
}
