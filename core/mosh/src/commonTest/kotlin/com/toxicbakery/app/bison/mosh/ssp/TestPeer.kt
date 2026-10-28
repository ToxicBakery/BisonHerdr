package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.Datagram
import com.toxicbakery.app.bison.mosh.wire.Direction
import com.toxicbakery.app.bison.mosh.wire.Fragment
import com.toxicbakery.app.bison.mosh.wire.FragmentAssembler
import com.toxicbakery.app.bison.mosh.wire.HostDiff
import com.toxicbakery.app.bison.mosh.wire.HostEvent
import com.toxicbakery.app.bison.mosh.wire.Instruction
import com.toxicbakery.app.bison.mosh.wire.MoshCipher
import com.toxicbakery.app.bison.mosh.wire.MoshKey
import com.toxicbakery.app.bison.mosh.wire.UserDiff
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import com.toxicbakery.app.bison.mosh.wire.Zlib

/** The key of the tests. */
internal val TEST_KEY: MoshKey = MoshKey.of(ByteArray(MoshKey.SIZE) { index -> (index * 3).toByte() })

/** A host state store with a text screen: each host byte event adds its text. */
internal fun textStore(): HostStateStore<String> = HostStateStore("") { state, events ->
    state + events.filterIsInstance<HostEvent.HostBytes>().joinToString("") { event -> event.bytes.decodeToString() }
}

/** A transport with a text screen that starts at the time 0. */
internal fun textTransport(): SspTransport<String> = SspTransport(MoshCipher(TEST_KEY), textStore(), startMs = 0)

/** One client datagram that [TestPeer.open] opened. */
internal class OpenedDatagram(val datagram: Datagram, val instruction: Instruction?) {
    /** The user events of the instruction, or an empty list. */
    val events: List<UserEvent> get() = instruction?.let { UserDiff.decode(it.diff)?.events }.orEmpty()

    /** The key bytes of the instruction as text. */
    val keysText: String
        get() = events.filterIsInstance<UserEvent.Keys>().joinToString("") { event -> event.bytes.decodeToString() }
}

/** A scripted server: it opens the datagrams of the client and seals datagrams for the client. */
internal class TestPeer {
    private val cipher = MoshCipher(TEST_KEY)
    private val zlib = Zlib()
    private val assembler = FragmentAssembler()
    private var sequence = 0L
    private var instructionId = 1uL

    /** Opens one client datagram. The instruction is null until the datagram completes one. */
    fun open(packet: ByteArray): OpenedDatagram {
        val datagram = checkNotNull(Datagram.open(packet, cipher, Direction.ToServer)) {
            "A client datagram did not open."
        }
        val instruction = assembler.add(datagram.fragment)
            ?.let { stream -> zlib.decompress(stream, Instruction.MAX_SIZE) }
            ?.let(Instruction::decode)
        return OpenedDatagram(datagram, instruction)
    }

    /** Opens each datagram of [packets], and returns the instructions that they complete. */
    fun instructions(packets: List<ByteArray>): List<Instruction> = packets.mapNotNull { open(it).instruction }

    /** Seals an instruction of the server as one or more datagrams. */
    fun seal(
        fromState: ULong,
        toState: ULong,
        ackState: ULong = 0uL,
        keepState: ULong = fromState,
        events: List<HostEvent> = emptyList(),
        timestamp: Int = 0,
        timestampReply: Int = Datagram.NO_TIMESTAMP,
        sequence: Long? = null,
    ): List<ByteArray> {
        val instruction = Instruction(
            fromState = fromState,
            toState = toState,
            ackState = ackState,
            keepState = keepState,
            diff = HostDiff(events).encode(),
            noise = byteArrayOf(1, 2, 3),
        )
        val fragments = Fragment.split(instructionId++, zlib.compress(instruction.encode()))
        return fragments.mapIndexed { index, fragment ->
            val number = sequence?.plus(index) ?: this.sequence++
            Datagram(
                direction = Direction.ToClient,
                sequence = number,
                timestamp = timestamp,
                timestampReply = if (index == 0) timestampReply else Datagram.NO_TIMESTAMP,
                fragment = fragment,
            ).seal(cipher)
        }
    }
}

/** A host diff with the text [value]. */
internal fun hostText(value: String): List<HostEvent> = listOf(HostEvent.HostBytes(value.encodeToByteArray()))
