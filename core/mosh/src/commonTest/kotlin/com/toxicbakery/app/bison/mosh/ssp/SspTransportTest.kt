package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.Datagram
import com.toxicbakery.app.bison.mosh.wire.Direction
import com.toxicbakery.app.bison.mosh.wire.Fragment
import com.toxicbakery.app.bison.mosh.wire.HostEvent
import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.MoshKey
import com.toxicbakery.app.bison.mosh.wire.UserDiff
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sender rules, the receiver rules, and the timers of [SspTransport], with a scripted server and virtual time. */
class SspTransportTest {

    private val transport = textTransport()
    private val peer = TestPeer()

    @Test
    fun theFirstPollSendsTheSizeStateAtOnceAndAgainEach250MsBeforeTheFirstReply() {
        transport.push(UserEvent.Resize(80, 24))
        val first = peer.instructions(transport.poll(0)).single()
        assertEquals(0uL, first.fromState)
        assertEquals(1uL, first.toState)
        assertEquals(UserDiff(listOf(UserEvent.Resize(80, 24))), UserDiff.decode(first.diff))
        assertEquals(250, transport.nextDeadlineMs(0))
        assertTrue(transport.poll(249).isEmpty())
        val sendTimes = (250L..2000L).filter { now -> transport.poll(now).isNotEmpty() }
        assertEquals((1L..8L).map { it * 250 }, sendTimes)
    }

    @Test
    fun aReplyGivesAnRttSampleAndTheClientRepliesOnceToTheServerTimestamp() {
        transport.push(UserEvent.Resize(80, 24))
        transport.poll(0)
        val reply = peer.seal(0uL, 1uL, ackState = 1uL, events = hostText("$"), timestamp = 5000, timestampReply = 0)
        val result = transport.receive(reply.single(), nowMs = 40)
        assertTrue(result.isAuthentic)
        assertTrue(result.hasNewHostState)
        assertEquals(40, result.rttSampleMs)
        assertEquals(40, transport.smoothedRttMs)
        assertEquals(40, transport.lastContactAtMs)

        // The delayed ack holds the server timestamp plus the 100 ms that the client held it.
        val ack = peer.open(transport.poll(140).single()).datagram
        assertEquals(140, ack.timestamp)
        assertEquals(5100, ack.timestampReply)
        val heartbeat = peer.open(transport.poll(3140).single()).datagram
        assertEquals(Datagram.NO_TIMESTAMP, heartbeat.timestampReply)
    }

    @Test
    fun aNewHostStateGetsAnAckAfter100MsWithTheAcknowledgedUserStateAndNoDiff() {
        connect(rttMs = 10)
        assertTrue(transport.poll(109).isEmpty())
        assertEquals(110, transport.nextDeadlineMs(109))
        val ack = assertNotNull(peer.open(transport.poll(110).single()).instruction)
        assertEquals(1uL, ack.fromState)
        assertEquals(1uL, ack.toState)
        assertEquals(1uL, ack.keepState)
        assertEquals(1uL, ack.ackState)
        assertEquals(0, ack.diff.size)
    }

    @Test
    fun aCopyOfAHostStateAfterTheAckGetsOneAckAtOnce() {
        connect(rttMs = 10)
        transport.poll(110)
        // The server did not get the ack, so it sends the same state again.
        val copy = peer.seal(0uL, 1uL, ackState = 1uL, events = hostText("$"))
        assertFalse(transport.receive(copy.single(), nowMs = 500).hasNewHostState)
        assertEquals(500, transport.nextDeadlineMs(500))
        val ack = assertNotNull(peer.open(transport.poll(500).single()).instruction)
        assertEquals(1uL, ack.ackState)
        assertEquals(1uL, ack.toState)
        assertEquals(0, ack.diff.size)
        assertEquals(listOf(3500L), (501L..4000L).filter { now -> transport.poll(now).isNotEmpty() })
    }

    @Test
    fun aCopyWhileTheDelayedAckWaitsMakesTheAckGoOutAtOnce() {
        connect(rttMs = 10)
        transport.receive(peer.seal(0uL, 1uL, ackState = 1uL, events = hostText("$")).single(), nowMs = 40)
        assertEquals(1, transport.poll(40).size)
        assertTrue((41L..200L).none { now -> transport.poll(now).isNotEmpty() })
    }

    @Test
    fun aDiffFromAStateThatTheClientDoesNotHaveGetsOneAckAtOnce() {
        connect(rttMs = 10)
        transport.poll(110)
        assertFalse(transport.receive(peer.seal(5uL, 6uL, ackState = 1uL).single(), nowMs = 500).hasNewHostState)
        val ack = assertNotNull(peer.open(transport.poll(500).single()).instruction)
        assertEquals(1uL, ack.ackState)
        assertTrue((501L..3000L).none { now -> transport.poll(now).isNotEmpty() })
    }

    @Test
    fun aPureAckOfTheServerGetsNoAnswer() {
        connect(rttMs = 10)
        transport.poll(110)
        assertFalse(transport.receive(peer.seal(1uL, 1uL, ackState = 1uL).single(), nowMs = 500).hasNewHostState)
        assertEquals(3110, transport.nextDeadlineMs(500))
        assertTrue((500L..3109L).none { now -> transport.poll(now).isNotEmpty() })
    }

    @Test
    fun anAckGoesOutWithTheNextUserStateAndNotAgainAfter100Ms() {
        connect(rttMs = 10)
        transport.poll(110)
        transport.receive(peer.seal(1uL, 2uL, ackState = 1uL, events = hostText("x")).single(), nowMs = 1000)
        transport.push(keys("a"))
        val state = assertNotNull(peer.open(transport.poll(1030).single()).instruction)
        assertEquals(2uL, state.toState)
        assertEquals(2uL, state.ackState)
        // The server acknowledges the user state with no new host state, so no resend and no ack follow.
        assertFalse(transport.receive(peer.seal(2uL, 2uL, ackState = 2uL).single(), nowMs = 1040).hasNewHostState)
        assertTrue((1041L..1200L).none { now -> transport.poll(now).isNotEmpty() })
    }

    @Test
    fun aNewUserStateWaitsForTheFrameInterval() {
        connect(rttMs = 100)
        assertEquals(50, transport.frameIntervalMs)
        transport.poll(200)
        transport.push(keys("a"))
        assertEquals(2uL, peer.instructions(transport.poll(1000)).single().toState)
        transport.push(keys("b"))
        assertTrue(transport.poll(1010).isEmpty())
        assertEquals(1050, transport.nextDeadlineMs(1010))
        val next = peer.instructions(transport.poll(1050)).single()
        assertEquals(1uL, next.fromState)
        assertEquals(3uL, next.toState)
        assertEquals(UserDiff(listOf(keys("ab"))), UserDiff.decode(next.diff))
    }

    @Test
    fun theDiffGoesAgainAfterTheRtoWithTheBackOffUntilTheServerAcknowledgesIt() {
        connect(rttMs = 10)
        transport.poll(110)
        assertEquals(RttEstimator.MIN_RTO_MS, transport.rtoMs)
        transport.push(keys("a"))
        val sent = mutableListOf<Pair<Long, OpenedDatagram>>()
        for (now in 1000L..5000L) transport.poll(now).forEach { packet -> sent += now to peer.open(packet) }
        val times = sent.map { (now, _) -> now }
        assertEquals(listOf(50L, 100L, 200L, 400L, 800L, 1000L, 1000L), times.zipWithNext { a, b -> b - a })
        assertTrue(sent.all { (_, opened) -> opened.instruction?.toState == 2uL && opened.keysText == "a" })
        assertEquals(sent.size, sent.map { (_, opened) -> opened.datagram.fragment.instructionId }.toSet().size)

        transport.receive(peer.seal(1uL, 2uL, ackState = 2uL).single(), nowMs = 5000)
        val later = (5001L..9000L).flatMap { now -> transport.poll(now).map { packet -> now to peer.open(packet) } }
        // Only the delayed ack and one heartbeat. Both have no diff.
        assertEquals(listOf(5100L, 8100L), later.map { (now, _) -> now })
        assertTrue(later.all { (_, opened) -> opened.instruction?.toState == 2uL && opened.events.isEmpty() })
    }

    @Test
    fun anIdleSessionSendsAHeartbeatEach3Seconds() {
        connect(rttMs = 10)
        val sendTimes = (11L..9500L).filter { now -> transport.poll(now).isNotEmpty() }
        assertEquals(listOf(110L, 3110L, 6110L, 9110L), sendTimes)
    }

    @Test
    fun eachDatagramHasTheNextSequenceNumberAlsoForResendsFragmentsAndANewSocket() {
        connect(rttMs = 10)
        val packets = ArrayList<ByteArray>()
        transport.push(UserEvent.Keys(Random(1).nextBytes(10_000)))
        for (now in 100L..2000L) packets += transport.poll(now)
        transport.onRoamed(2000)
        for (now in 2000L..4000L) packets += transport.poll(now)
        val sequences = packets.map { packet -> peer.open(packet).datagram.sequence }
        // The first poll of connect() sent the datagram with the number 0.
        assertEquals((1L..packets.size.toLong()).toList(), sequences)
        assertEquals(packets.size + 1L, transport.sentDatagramCount)
        assertTrue(packets.any { packet -> peer.open(packet).datagram.fragment.number > 0 })
        val headers = packets.map { packet -> packet.copyOf(Datagram.HEADER_SIZE).toList() }
        assertEquals(headers.size, headers.toSet().size)
    }

    @Test
    fun aSilenceOf10SecondsAsksForANewSocketAndTheTransportSendsOnItAtOnce() {
        connect(rttMs = 10)
        for (now in 11L..10_000L) transport.poll(now)
        assertFalse(transport.isRoamDue(10_009))
        assertEquals(10_010, transport.nextDeadlineMs(10_000))
        assertTrue(transport.isRoamDue(10_010))
        transport.onRoamed(10_010)
        assertFalse(transport.isRoamDue(10_011))
        assertTrue(transport.poll(10_010).isNotEmpty())
        assertFalse(transport.isRoamDue(20_009))
        assertTrue(transport.isRoamDue(20_010))
    }

    @Test
    fun theEndStateEndsTheSessionAfterOneImmediateAck() {
        connect(rttMs = 10)
        transport.poll(110)
        val end = peer.seal(1uL, SspTransport.END_STATE, ackState = 1uL, events = hostText("bye"))
        assertTrue(transport.receive(end.single(), nowMs = 500).hasNewHostState)
        assertTrue(transport.isEnded)
        assertEquals("\$bye", transport.hostStates.newest.state)
        assertEquals(500, transport.nextDeadlineMs(500))
        val ack = assertNotNull(peer.open(transport.poll(500).single()).instruction)
        assertEquals(SspTransport.END_STATE, ack.ackState)
        assertEquals(Long.MAX_VALUE, transport.nextDeadlineMs(501))
        transport.push(keys("late"))
        assertTrue((501L..10_000L).none { now -> transport.poll(now).isNotEmpty() })
        assertFalse(transport.isRoamDue(20_000))

        // The server sends the end state again when the ack did not arrive.
        val copy = peer.seal(1uL, SspTransport.END_STATE, ackState = 1uL, events = hostText("bye"))
        assertFalse(transport.receive(copy.single(), nowMs = 11_000).hasNewHostState)
        assertEquals(SspTransport.END_STATE, peer.instructions(transport.poll(11_000)).single().ackState)
    }

    @Test
    fun anEndStateFromAnUnknownStateDoesNotEndTheSession() {
        connect(rttMs = 10)
        transport.receive(peer.seal(7uL, SspTransport.END_STATE).single(), nowMs = 500)
        assertFalse(transport.isEnded)
    }

    @Test
    fun aSecondCopyOrALateCopyOfAnInstructionHasNoEffect() {
        connect(rttMs = 10)
        val packets = peer.seal(1uL, 2uL, ackState = 1uL, events = hostText("x"), sequence = 10)
        assertTrue(transport.receive(packets.single(), nowMs = 100).hasNewHostState)
        assertFalse(transport.receive(packets.single(), nowMs = 101).hasNewHostState)
        assertEquals("\$x", transport.hostStates.newest.state)

        // A datagram with a lower sequence number gives no RTT sample, but its new state applies.
        val late = peer.seal(2uL, 3uL, ackState = 1uL, events = hostText("y"), timestampReply = 0, sequence = 5)
        val result = transport.receive(late.single(), nowMs = 102)
        assertTrue(result.hasNewHostState)
        assertNull(result.rttSampleMs)
        assertEquals("\$xy", transport.hostStates.newest.state)
    }

    @Test
    fun aDatagramThatDoesNotOpenIsRejectedAndIsNotAContact() {
        transport.push(UserEvent.Resize(80, 24))
        val own = transport.poll(0).single()
        val otherKey = MoshCipher(MoshKey.of(ByteArray(MoshKey.SIZE)))
        val foreign = Datagram(Direction.ToClient, 0, 0, 0, Fragment(1uL, 0, true, ByteArray(0))).seal(otherKey)
        val valid = peer.seal(0uL, 1uL).single()
        for (packet in listOf(own, foreign, valid.copyOf(valid.size - 1), Random(2).nextBytes(64), ByteArray(0))) {
            assertEquals(ReceiveResult.REJECTED, transport.receive(packet, nowMs = 10))
        }
        assertNull(transport.lastContactAtMs)
    }

    @Test
    fun aTimestampReplyOlderThan5SecondsGivesNoSample() {
        transport.push(UserEvent.Resize(80, 24))
        transport.poll(0)
        val reply = peer.seal(0uL, 1uL, ackState = 1uL, timestampReply = 0)
        assertNull(transport.receive(reply.single(), nowMs = 5000).rttSampleMs)
        assertNull(transport.smoothedRttMs)
    }

    @Test
    fun aLargePasteGoesOutInStatesThatStayInTheDiffLimit() {
        connect(rttMs = 10)
        val paste = Random(3).nextBytes(1024 * 1024)
        transport.push(UserEvent.Keys(paste))
        val received = ArrayList<ByteArray>()
        var acknowledged = 1uL
        var now = 100L
        while (transport.userStream.acknowledgedCount < transport.userStream.pushedCount && now < 100_000) {
            val packets = transport.poll(now)
            // A full diff of random bytes is at most 7 fragments.
            assertTrue(packets.size <= 7, "${packets.size} datagrams")
            for (instruction in peer.instructions(packets)) {
                if (instruction.toState <= acknowledged) continue
                assertTrue(instruction.diff.size <= UserStream.MAX_DIFF_SIZE)
                assertEquals(acknowledged, instruction.fromState)
                val events = assertNotNull(UserDiff.decode(instruction.diff)).events
                received += events.filterIsInstance<UserEvent.Keys>().map { event -> event.bytes }
                acknowledged = instruction.toState
                transport.receive(peer.seal(1uL, 1uL, ackState = acknowledged).single(), nowMs = now)
            }
            now += 10
        }
        assertContentEquals(paste, received.fold(ByteArray(0)) { joined, part -> joined + part })
    }

    @Test
    fun theEchoAckOfTheNewestHostStateGivesTheCountOfEventsThatTheApplicationGot() {
        connect(rttMs = 10)
        transport.push(keys("a"))
        transport.push(keys("b"))
        transport.poll(200)
        transport.receive(peer.seal(1uL, 2uL, ackState = 2uL, events = listOf(HostEvent.EchoAck(2uL))).single(), 300)
        assertEquals(2uL, transport.echoAck)
        assertEquals(3, transport.userStream.echoedCount)
    }

    private fun connect(rttMs: Long) {
        transport.push(UserEvent.Resize(80, 24))
        transport.poll(0)
        val reply = peer.seal(0uL, 1uL, ackState = 1uL, events = hostText("$"), timestamp = 7, timestampReply = 0)
        transport.receive(reply.single(), nowMs = rttMs)
    }

    private fun keys(text: String) = UserEvent.Keys(text.encodeToByteArray())
}
