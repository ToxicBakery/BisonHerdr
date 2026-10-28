package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.UserDiff
import com.toxicbakery.app.bison.mosh.wire.UserEvent

/**
 * The user stream of one session: the user events (keys and sizes) and the numbered user states that hold them.
 *
 * A new event waits in a pending list. When the sender makes a new user state, the pending events go into it, and
 * the state number increases by one. The server acknowledges a state with the `ackState` of its instructions. The
 * diff of the next client instruction holds all events after the acknowledged state (sender rule 1).
 *
 * The events after the acknowledged state have at most [maxDiffSize] bytes. More events wait until the server
 * acknowledges a state. Thus one instruction stays in the fragment limit and in the receive buffer of the server.
 * [push] cuts a large key event into parts of at most [MAX_KEYS_PART_SIZE] bytes, so that each part fits.
 *
 * Each event gets an index: the first event has the index 1. [acknowledgedCount] and [echoedCount] give the count of
 * events that the server has, and that the application on the server got.
 *
 * Not safe for use by two threads at the same time.
 */
class UserStream(private val maxDiffSize: Int = MAX_DIFF_SIZE) {

    private class State(val number: ULong, val events: List<UserEvent>, val size: Int, val eventCount: Long)

    private val pending = ArrayDeque<UserEvent>()
    private val unacknowledged = ArrayDeque<State>()

    // The state number and the event count of each state after the newest echo ack.
    private val echoMarks = ArrayDeque<Pair<ULong, Long>>()
    private var unacknowledgedSize = 0
    private var echoedState = 0uL
    private var cachedDiff: ByteArray? = null

    /** The newest user state that the server acknowledged. */
    var acknowledgedState: ULong = 0uL
        private set

    /** The newest user state. */
    var currentState: ULong = 0uL
        private set

    /** The count of events that [push] added. */
    var pushedCount: Long = 0
        private set

    /** The count of events in [currentState] and the states before it. */
    var numberedCount: Long = 0
        private set

    /** The count of events in [acknowledgedState] and the states before it. */
    var acknowledgedCount: Long = 0
        private set

    /** The count of events that the application on the server got, from the echo ack (wire fact 15). */
    var echoedCount: Long = 0
        private set

    /** True when events wait for a new state. */
    val hasPending: Boolean get() = pending.isNotEmpty()

    /** True when the server did not acknowledge [currentState]. */
    val hasUnacknowledged: Boolean get() = currentState > acknowledgedState

    /** True when a new state can take the first pending event in the size limit. */
    val canNumber: Boolean
        get() = pending.isNotEmpty() && unacknowledgedSize + sizeOf(pending.first()) <= maxDiffSize

    /**
     * Adds [event]. Returns the index of the event. A key event with more than [MAX_KEYS_PART_SIZE] bytes becomes
     * more than one event, and the result is the index of the last part.
     */
    fun push(event: UserEvent): Long {
        if (event is UserEvent.Keys && event.bytes.size > MAX_KEYS_PART_SIZE) {
            for (start in event.bytes.indices step MAX_KEYS_PART_SIZE) {
                val end = minOf(event.bytes.size, start + MAX_KEYS_PART_SIZE)
                pending.addLast(UserEvent.Keys(event.bytes.copyOfRange(start, end)))
                pushedCount++
            }
        } else {
            pending.addLast(event)
            pushedCount++
        }
        return pushedCount
    }

    /**
     * Makes a new user state of the pending events that fit in the size limit. Returns false when no event fits.
     */
    internal fun numberPending(): Boolean {
        if (!canNumber) return false
        val events = ArrayList<UserEvent>()
        var size = 0
        while (pending.isNotEmpty() && unacknowledgedSize + size + sizeOf(pending.first()) <= maxDiffSize) {
            val event = pending.removeFirst()
            events += event
            size += sizeOf(event)
        }
        currentState++
        numberedCount += events.size
        unacknowledged.addLast(State(currentState, events, size, numberedCount))
        unacknowledgedSize += size
        cachedDiff = null
        echoMarks.addLast(currentState to numberedCount)
        if (echoMarks.size > MAX_ECHO_MARKS) echoMarks.removeFirst()
        return true
    }

    /** Returns the wire form of the diff from [acknowledgedState] to [currentState]. Next key events are joined. */
    internal fun encodedDiff(): ByteArray {
        cachedDiff?.let { return it }
        val events = ArrayList<UserEvent>()
        val keys = ArrayList<ByteArray>()
        fun joinKeys() {
            if (keys.isEmpty()) return
            val joined = ByteArray(keys.sumOf { part -> part.size })
            var offset = 0
            for (part in keys) {
                part.copyInto(joined, offset)
                offset += part.size
            }
            events += UserEvent.Keys(joined)
            keys.clear()
        }
        for (event in unacknowledged.flatMap { state -> state.events }) {
            if (event is UserEvent.Keys) {
                keys += event.bytes
            } else {
                joinKeys()
                events += event
            }
        }
        joinKeys()
        return UserDiff(events).encode().also { bytes -> cachedDiff = bytes }
    }

    /**
     * Records that the server has [state]. A state that is not newer, or that the client did not make, has no
     * effect.
     */
    internal fun acknowledge(state: ULong) {
        if (state <= acknowledgedState || state > currentState) return
        while (unacknowledged.isNotEmpty() && unacknowledged.first().number <= state) {
            val removed = unacknowledged.removeFirst()
            unacknowledgedSize -= removed.size
            acknowledgedCount = removed.eventCount
        }
        acknowledgedState = state
        cachedDiff = null
    }

    /** Records the echo ack [state] of the server. A state that is not newer has no effect. */
    internal fun echo(state: ULong) {
        if (state <= echoedState || state > currentState) return
        while (echoMarks.isNotEmpty() && echoMarks.first().first <= state) {
            echoedCount = echoMarks.removeFirst().second
        }
        echoedState = state
    }

    private fun sizeOf(event: UserEvent): Int = when (event) {
        is UserEvent.Keys -> event.bytes.size + EVENT_OVERHEAD
        is UserEvent.Resize -> EVENT_OVERHEAD
    }

    companion object {
        /**
         * The largest size of the events after the acknowledged state: 192 KiB. This is a choice of this client. It
         * holds a paste of 128 KiB and more keys. In a black-box test, the server joined one burst of 220 fragments
         * (256 KiB of random bytes) on the loopback.
         */
        const val MAX_DIFF_SIZE: Int = 192 * 1024

        /** The largest part of a key event. */
        const val MAX_KEYS_PART_SIZE: Int = 16 * 1024

        // The protobuf tags and lengths of one event take fewer bytes than this value.
        private const val EVENT_OVERHEAD = 16

        // The echo ack can be older than the acknowledged state, so the marks stay after an acknowledgment.
        private const val MAX_ECHO_MARKS = 1024
    }
}
