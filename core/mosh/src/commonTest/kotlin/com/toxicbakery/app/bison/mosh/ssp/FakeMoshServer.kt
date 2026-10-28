package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.Datagram
import com.toxicbakery.app.bison.mosh.wire.Direction
import com.toxicbakery.app.bison.mosh.wire.Fragment
import com.toxicbakery.app.bison.mosh.wire.FragmentAssembler
import com.toxicbakery.app.bison.mosh.wire.HostDiff
import com.toxicbakery.app.bison.mosh.wire.HostEvent
import com.toxicbakery.app.bison.mosh.wire.Instruction
import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.UserDiff
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import com.toxicbakery.app.bison.mosh.wire.Zlib

/**
 * A server for the simulation tests. Its rules come from the black-box facts of decision 4:
 * - It replies to the address of the newest authentic datagram (wire fact 10).
 * - It applies a user diff only when it has the `fromState` and not the `toState`. Its application gets each key one
 *   time. It does not answer a copy of a user state that it has. A real server does the same.
 * - The screen is small, as on a real terminal: the count of keys and the last keys. Each diff holds the full
 *   screen, and the client screen is the text of the last diff ([screenStore]).
 * - It chains each diff from its newest sent state. When the client does not acknowledge in [RESEND_MS], it sends
 *   a diff from the state that the client acknowledged (wire fact 17). Its `keepState` is that state.
 * - The echo ack is the newest user state that the application got at least 50 ms before (wire fact 15).
 * - With no change, it makes a new state each 3 s as a heartbeat. A real server does the same.
 * - It puts each timestamp reply in one datagram, with the time that it held the timestamp.
 * - After the end state, it resends the end state until the client acknowledges it. Then it stops.
 */
internal class FakeMoshServer {
    private class HostSnapshot(val text: String, val echoAck: ULong)

    private val cipher = MoshCipher(TEST_KEY)
    private val zlib = Zlib()
    private val assembler = FragmentAssembler()
    private var sequence = 0L
    private var instructionId = 1uL
    private var newestClientSequence = -1L
    private var clientTimestamp = Datagram.NO_TIMESTAMP
    private var clientTimestampAtMs = 0L

    // The user side. Each user state is the text of all its keys.
    private val userStates = HashMap<ULong, String>().apply { put(0uL, "") }
    private var newestUserState = 0uL
    private val deliveries = ArrayDeque<Pair<Long, ULong>>()
    private var echoAck = 0uL
    private var userAckDueAtMs = Long.MAX_VALUE
    private var sentUserAck = 0uL

    // The host side.
    private val hostStates = HashMap<ULong, HostSnapshot>().apply { put(0uL, HostSnapshot("", 0uL)) }
    private var currentHost = 0uL
    private var clientHostAck = 0uL
    private var newestSentHost = 0uL

    // The time of the first send of a state that the client did not acknowledge, or null.
    private var unacknowledgedSinceMs: Long? = null
    private var lastSendAtMs = 0L
    private var lastNewHostAtMs = 0L
    private var changeAtMs: Long? = null
    private var endRequested = false

    /** The text that the application got, in order. */
    var applicationText: String = ""
        private set

    /** The address of the client, from the newest authentic datagram. */
    var target: Int = -1
        private set

    /** Errors in the user stream of the client. A correct client gives none. */
    val errors: MutableList<String> = mutableListOf()

    /** The screen of the newest host state. */
    val screen: String get() = hostStates.getValue(currentHost).text

    private val applicationScreen: String get() = "${applicationText.length}:${applicationText.takeLast(SCREEN_TAIL)}"

    /** The time at which the client acknowledged the end state, or null. */
    var endAcknowledgedAtMs: Long? = null
        private set

    /** The count of client datagrams after the client acknowledged the end state. */
    var datagramsAfterEnd: Int = 0
        private set

    /** Makes the application exit. The next poll sends the end state. */
    fun requestEnd() {
        endRequested = true
    }

    /** Takes one client datagram from [address]. */
    fun receive(packet: ByteArray, address: Int, nowMs: Long) {
        val datagram = Datagram.open(packet, cipher, Direction.ToServer) ?: return
        if (endAcknowledgedAtMs != null) datagramsAfterEnd++
        if (datagram.sequence > newestClientSequence) {
            newestClientSequence = datagram.sequence
            target = address
            clientTimestamp = datagram.timestamp
            clientTimestampAtMs = nowMs
        }
        val instruction = assembler.add(datagram.fragment)
            ?.let { stream -> zlib.decompress(stream, Instruction.MAX_SIZE) }
            ?.let(Instruction::decode) ?: return
        if (instruction.ackState > clientHostAck && instruction.ackState in hostStates) {
            clientHostAck = instruction.ackState
            unacknowledgedSinceMs = if (clientHostAck >= newestSentHost) null else nowMs
            if (clientHostAck == SspTransport.END_STATE && endAcknowledgedAtMs == null) endAcknowledgedAtMs = nowMs
        }
        userStates.keys.removeAll { state -> state < instruction.keepState && state != newestUserState }
        val diff = UserDiff.decode(instruction.diff)
        if (diff == null) {
            errors.add("A user diff did not decode.")
            return
        }
        val source = userStates[instruction.fromState]
        if (source == null || instruction.toState in userStates || instruction.toState <= instruction.fromState) return
        val keys = diff.events.filterIsInstance<UserEvent.Keys>().joinToString("") { it.bytes.decodeToString() }
        val text = source + keys
        userStates[instruction.toState] = text
        if (instruction.toState > newestUserState) newestUserState = instruction.toState
        when {
            text.startsWith(applicationText) -> applicationText = text
            !applicationText.startsWith(text) -> errors.add("State ${instruction.toState} does not agree.")
        }
        deliveries.addLast(nowMs to instruction.toState)
        userAckDueAtMs = minOf(userAckDueAtMs, nowMs + DELAYED_ACK_MS)
    }

    /** Returns the datagrams that the server sends at [nowMs]. */
    fun poll(nowMs: Long): List<ByteArray> {
        if (endAcknowledgedAtMs != null) return emptyList()
        while (deliveries.isNotEmpty() && deliveries.first().first <= nowMs - ECHO_DELAY_MS) {
            echoAck = maxOf(echoAck, deliveries.removeFirst().second)
        }
        val current = hostStates.getValue(currentHost)
        val changed = applicationScreen != current.text || echoAck != current.echoAck
        if (changed && changeAtMs == null) changeAtMs = nowMs
        val collected = changed &&
            nowMs >= (changeAtMs ?: nowMs) + COLLECTION_MS &&
            nowMs >= lastNewHostAtMs + FRAME_MS
        val chainTimedOut = unacknowledgedSinceMs?.let { since -> nowMs >= since + RESEND_MS } ?: false
        val heartbeat = currentHost != SspTransport.END_STATE && nowMs >= lastNewHostAtMs + HEARTBEAT_MS
        return when {
            endRequested && currentHost != SspTransport.END_STATE -> {
                newHostState(SspTransport.END_STATE, nowMs)
                send(nowMs)
            }
            currentHost != SspTransport.END_STATE && (collected || heartbeat) -> {
                newHostState(currentHost + 1uL, nowMs)
                send(nowMs)
            }
            currentHost > clientHostAck && (chainTimedOut || nowMs >= lastSendAtMs + RESEND_MS) -> {
                // The client did not acknowledge the chain, so the next diff starts from its acknowledged state.
                newestSentHost = clientHostAck
                unacknowledgedSinceMs = null
                send(nowMs)
            }
            newestUserState > sentUserAck && nowMs >= userAckDueAtMs -> send(nowMs)
            else -> emptyList()
        }
    }

    private fun newHostState(number: ULong, nowMs: Long) {
        hostStates[number] = HostSnapshot(applicationScreen, echoAck)
        currentHost = number
        lastNewHostAtMs = nowMs
        changeAtMs = null
    }

    private fun send(nowMs: Long): List<ByteArray> {
        val base = if (newestSentHost > clientHostAck) newestSentHost else clientHostAck
        val from = hostStates.getValue(base)
        val to = hostStates.getValue(currentHost)
        val events = buildList {
            if (to.text != from.text) add(HostEvent.HostBytes(to.text.encodeToByteArray()))
            if (to.echoAck != from.echoAck) add(HostEvent.EchoAck(to.echoAck))
        }
        val instruction = Instruction(
            fromState = base,
            toState = currentHost,
            ackState = newestUserState,
            keepState = clientHostAck,
            diff = HostDiff(events).encode(),
            noise = byteArrayOf(0),
        )
        val reply = if (clientTimestamp == Datagram.NO_TIMESTAMP) {
            Datagram.NO_TIMESTAMP
        } else {
            ((clientTimestamp + nowMs - clientTimestampAtMs) and TIMESTAMP_MASK).toInt()
        }
        clientTimestamp = Datagram.NO_TIMESTAMP
        val fragments = Fragment.split(instructionId++, zlib.compress(instruction.encode()))
        lastSendAtMs = nowMs
        if (currentHost > clientHostAck && unacknowledgedSinceMs == null) unacknowledgedSinceMs = nowMs
        newestSentHost = maxOf(newestSentHost, currentHost)
        sentUserAck = newestUserState
        userAckDueAtMs = Long.MAX_VALUE
        return fragments.mapIndexed { index, fragment ->
            Datagram(
                direction = Direction.ToClient,
                sequence = sequence++,
                timestamp = (nowMs and TIMESTAMP_MASK).toInt(),
                timestampReply = if (index == 0) reply else Datagram.NO_TIMESTAMP,
                fragment = fragment,
            ).seal(cipher)
        }
    }

    private companion object {
        const val DELAYED_ACK_MS = 100L
        const val ECHO_DELAY_MS = 50L
        const val COLLECTION_MS = 8L
        const val FRAME_MS = 20L
        const val HEARTBEAT_MS = 3000L
        const val RESEND_MS = 200L
        const val SCREEN_TAIL = 40
        const val TIMESTAMP_MASK = 0xFFFFL
    }
}
