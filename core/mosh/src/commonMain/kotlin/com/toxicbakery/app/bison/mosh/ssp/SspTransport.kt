package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.Datagram
import com.toxicbakery.app.bison.mosh.wire.Direction
import com.toxicbakery.app.bison.mosh.wire.Fragment
import com.toxicbakery.app.bison.mosh.wire.FragmentAssembler
import com.toxicbakery.app.bison.mosh.wire.HostDiff
import com.toxicbakery.app.bison.mosh.wire.Instruction
import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import com.toxicbakery.app.bison.mosh.wire.Zlib

/** The result of [SspTransport.receive] for one datagram. */
data class ReceiveResult(
    /** True when the datagram opened with the session key. */
    val isAuthentic: Boolean,
    /** True when the datagram completed an instruction that made a new host state. */
    val hasNewHostState: Boolean,
    /** The RTT sample from the timestamp reply of the datagram, or null. */
    val rttSampleMs: Long?,
) {
    companion object {
        /** The result for a datagram that did not open. */
        val REJECTED: ReceiveResult = ReceiveResult(isAuthentic = false, hasNewHostState = false, rttSampleMs = null)
    }
}

/**
 * The client side of the state synchronization protocol (SSP) of one Mosh session, as a pure state machine. It has
 * no I/O and no clock. Each call takes the time in milliseconds of one monotonic clock:
 * - [push] adds a user event.
 * - [receive] takes one datagram from the server.
 * - [poll] returns the sealed datagrams to send now.
 * - [nextDeadlineMs] gives the time of the next [poll] that can send a datagram.
 * - [isRoamDue] tells when the caller must open a new socket, and [onRoamed] records the new socket.
 *
 * The sender rules of decision 4 apply:
 * - A new user state goes out when the last new state is older than the frame interval. Else it goes out at the
 *   end of the interval.
 * - The diff holds all events after the state that the server acknowledged. `fromState` and `keepState` are that
 *   state.
 * - The transport sends the diff again after the RTO while the server did not acknowledge the newest state.
 * - A heartbeat or an ack has the acknowledged state as `fromState` and `toState`, and an empty diff.
 * - Each datagram acknowledges the newest host state. A new host state gets an ack after [DELAYED_ACK_MS], or
 *   earlier with the next datagram.
 * - The transport sends a heartbeat when it sent nothing for [HEARTBEAT_INTERVAL_MS].
 * - The first datagram, and the first datagram after a new socket, go out at once. Such a datagram holds the diff
 *   when the server did not acknowledge the newest state.
 *
 * The receiver rules of decision 4 also apply. The transport drops a datagram that does not open. It takes
 * `ackState` and `keepState` from each instruction, also when it cannot apply the diff. Only a datagram with a
 * sequence number higher than all before gives the timestamps, so that a late copy cannot give a wrong RTT sample.
 *
 * Each datagram has a sequence number one higher than the datagram before it. This is true for the life of the
 * transport, also for a resend and after a new socket. Thus the nonce of each datagram is different.
 *
 * The end state ([END_STATE], wire fact 11) ends the session. The transport acknowledges it at once, because the
 * server then stops at once, not after about 4 s. After that ack, the transport sends nothing more. It answers only
 * a second copy of the end state.
 *
 * Not safe for use by two threads at the same time.
 */
class SspTransport<S>(
    private val cipher: MoshCipher,
    /** The host states that the transport received. */
    val hostStates: HostStateStore<S>,
    startMs: Long,
    /** The user events and the user states of the session. */
    val userStream: UserStream = UserStream(),
    private val rtt: RttEstimator = RttEstimator(),
    private val zlib: Zlib = Zlib(),
) {
    private val startedAtMs = startMs
    private val assembler = FragmentAssembler()
    private var nextSequence = 0L
    private var nextInstructionId = 1uL
    private var newestServerSequence = -1L
    private var serverTimestamp = Datagram.NO_TIMESTAMP
    private var serverTimestampAtMs = 0L
    private var lastSendAtMs = startMs
    private var lastDiffSentAtMs = startMs
    private var lastNewStateAtMs = startMs - RttEstimator.MAX_FRAME_INTERVAL_MS
    private var lastRoamAtMs = startMs
    private var ackPending = false
    private var ackDueAtMs = 0L

    // The first poll sends at once, so that the server learns the address of the client.
    private var sendNow = true

    /** The time of the newest authentic datagram, or null before the first one. */
    var lastContactAtMs: Long? = null
        private set

    /** The count of datagrams that the transport sealed. It is also the next sequence number. */
    val sentDatagramCount: Long get() = nextSequence

    /** True after the end state. */
    val isEnded: Boolean get() = hostStates.newest.number == END_STATE

    /** The echo ack of the newest host state: the newest user state that the application got (wire fact 15). */
    val echoAck: ULong get() = hostStates.newest.echoAck

    /** The smoothed RTT in milliseconds, or null before the first sample. */
    val smoothedRttMs: Long? get() = rtt.smoothedRttMs

    /** The retransmission timeout in milliseconds. */
    val rtoMs: Long get() = rtt.rtoMs

    /** The frame interval in milliseconds. */
    val frameIntervalMs: Long get() = rtt.frameIntervalMs

    /** Adds a user event. Returns the index of the event in the [userStream]. */
    fun push(event: UserEvent): Long = userStream.push(event)

    /** Takes the first [length] bytes of [packet], one datagram from the server, at the time [nowMs]. */
    fun receive(packet: ByteArray, nowMs: Long, length: Int = packet.size): ReceiveResult {
        val datagram = Datagram.open(packet, cipher, Direction.ToClient, length) ?: return ReceiveResult.REJECTED
        val sample = takeTimestamps(datagram, nowMs)
        val instruction = assembler.add(datagram.fragment)
            ?.let { stream -> zlib.decompress(stream, Instruction.MAX_SIZE) }
            ?.let(Instruction::decode)
            ?.takeIf { decoded -> decoded.version == Instruction.VERSION }
        val newHostState = instruction != null && takeInstruction(instruction, nowMs)
        return ReceiveResult(isAuthentic = true, hasNewHostState = newHostState, rttSampleMs = sample)
    }

    /** Returns the sealed datagrams to send at the time [nowMs]. The list is empty when nothing is due. */
    fun poll(nowMs: Long): List<ByteArray> {
        if (isEnded) return if (ackPending && nowMs >= ackDueAtMs) send(nowMs, withDiff = false) else emptyList()
        var withDiff = sendNow && userStream.hasUnacknowledged
        if (userStream.canNumber && nowMs >= lastNewStateAtMs + rtt.frameIntervalMs) {
            userStream.numberPending()
            lastNewStateAtMs = nowMs
            withDiff = true
        } else if (userStream.hasUnacknowledged && nowMs >= lastDiffSentAtMs + rtt.rtoMs) {
            // The next resend waits for the doubled RTO.
            rtt.backOff()
            withDiff = true
        }
        val due = withDiff ||
            sendNow ||
            (ackPending && nowMs >= ackDueAtMs) ||
            nowMs >= lastSendAtMs + HEARTBEAT_INTERVAL_MS
        return if (due) send(nowMs, withDiff) else emptyList()
    }

    /**
     * Returns the time of the next [poll] that can send a datagram, from the time [nowMs]. Returns [Long.MAX_VALUE]
     * when the transport has nothing more to send. The time includes the next time that [isRoamDue] becomes true.
     */
    fun nextDeadlineMs(nowMs: Long): Long {
        if (isEnded) return if (ackPending) ackDueAtMs else Long.MAX_VALUE
        if (sendNow) return nowMs
        var next = lastSendAtMs + HEARTBEAT_INTERVAL_MS
        if (userStream.canNumber) next = minOf(next, maxOf(nowMs, lastNewStateAtMs + rtt.frameIntervalMs))
        if (userStream.hasUnacknowledged) next = minOf(next, lastDiffSentAtMs + rtt.rtoMs)
        if (ackPending) next = minOf(next, ackDueAtMs)
        val roamAt = roamDueAtMs()
        if (roamAt > nowMs) next = minOf(next, roamAt)
        return next
    }

    /**
     * Returns true when no authentic datagram came for [ROAM_SILENCE_MS], and no new socket opened in that time. A
     * network can drop the port entry of the old socket. A new local port gets a new entry.
     */
    fun isRoamDue(nowMs: Long): Boolean = !isEnded && nowMs >= roamDueAtMs()

    /** Records that the caller opened a new socket at [nowMs]. The next [poll] sends a datagram on it at once. */
    fun onRoamed(nowMs: Long) {
        lastRoamAtMs = nowMs
        sendNow = true
    }

    private fun roamDueAtMs(): Long = maxOf(lastContactAtMs ?: startedAtMs, lastRoamAtMs) + ROAM_SILENCE_MS

    private fun takeTimestamps(datagram: Datagram, nowMs: Long): Long? {
        if (datagram.sequence <= newestServerSequence) return null
        newestServerSequence = datagram.sequence
        lastContactAtMs = nowMs
        serverTimestamp = datagram.timestamp
        serverTimestampAtMs = nowMs
        if (datagram.timestampReply == Datagram.NO_TIMESTAMP) return null
        val sample = (timestampOf(nowMs) - datagram.timestampReply).toLong() and TIMESTAMP_MASK
        if (sample >= MAX_SAMPLE_AGE_MS) return null
        rtt.addSample(sample)
        return sample
    }

    private fun takeInstruction(instruction: Instruction, nowMs: Long): Boolean {
        userStream.acknowledge(instruction.ackState)
        val diff = HostDiff.decode(instruction.diff)
        val applied = diff != null && hostStates.apply(instruction.fromState, instruction.toState, diff)
        hostStates.removeBefore(instruction.keepState)
        if (applied) {
            userStream.echo(hostStates.newest.echoAck)
            if (!ackPending) {
                ackPending = true
                ackDueAtMs = nowMs + DELAYED_ACK_MS
            }
        }
        // The server stops at once when it gets the ack of the end state. It sends the end state again without it.
        if (isEnded && instruction.toState == END_STATE) {
            ackPending = true
            ackDueAtMs = nowMs
        }
        return applied
    }

    private fun send(nowMs: Long, withDiff: Boolean): List<ByteArray> {
        val acknowledged = userStream.acknowledgedState
        val instruction = Instruction(
            fromState = acknowledged,
            toState = if (withDiff) userStream.currentState else acknowledged,
            ackState = hostStates.newest.number,
            keepState = acknowledged,
            diff = if (withDiff) userStream.encodedDiff() else ByteArray(0),
        )
        val fragments = Fragment.split(nextInstructionId++, zlib.compress(instruction.encode()))
        val timestamp = timestampOf(nowMs)
        val reply = takeTimestampReply(nowMs)
        val packets = fragments.mapIndexed { index, fragment ->
            Datagram(
                direction = Direction.ToServer,
                sequence = nextSequence++,
                timestamp = timestamp,
                timestampReply = if (index == 0) reply else Datagram.NO_TIMESTAMP,
                fragment = fragment,
            ).seal(cipher)
        }
        lastSendAtMs = nowMs
        if (withDiff) lastDiffSentAtMs = nowMs
        ackPending = false
        sendNow = false
        return packets
    }

    // The reply holds the newest server timestamp plus the time that the client held it (wire fact 13). Like the
    // server, the client sends each reply in one datagram only. The other datagrams have no reply.
    private fun takeTimestampReply(nowMs: Long): Int {
        if (serverTimestamp == Datagram.NO_TIMESTAMP) return Datagram.NO_TIMESTAMP
        val reply = ((serverTimestamp + nowMs - serverTimestampAtMs) and TIMESTAMP_MASK).toInt()
        serverTimestamp = Datagram.NO_TIMESTAMP
        return reply
    }

    private fun timestampOf(nowMs: Long): Int = (nowMs and TIMESTAMP_MASK).toInt()

    companion object {
        /** The target state that tells that the session ended: 2^64 - 1 (wire fact 11). */
        val END_STATE: ULong = ULong.MAX_VALUE

        /** The longest time with no datagram from the client (Mosh paper, section 2.3). */
        const val HEARTBEAT_INTERVAL_MS: Long = 3000

        /** The delay of an ack for a new host state (wire fact 14). */
        const val DELAYED_ACK_MS: Long = 100

        /** The silence after which the client opens a new socket. This is a choice of this client. */
        const val ROAM_SILENCE_MS: Long = 10_000

        /** A timestamp reply that is older than this value gives no RTT sample (receiver rule 6). */
        const val MAX_SAMPLE_AGE_MS: Long = 5000

        private const val TIMESTAMP_MASK = 0xFFFFL
    }
}
