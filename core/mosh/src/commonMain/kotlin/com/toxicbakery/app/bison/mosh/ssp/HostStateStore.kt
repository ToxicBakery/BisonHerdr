package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.HostDiff
import com.toxicbakery.app.bison.mosh.wire.HostEvent

/**
 * The host states that the client received, by state number. [S] is the screen of a state. Each state also has its
 * echo ack (wire fact 15).
 *
 * The receiver rules of decision 4 apply:
 * - [apply] makes a new state only when the store has the `fromState` and does not have the `toState`. Thus a late
 *   copy or a second copy of an instruction has no effect.
 * - [removeBefore] removes the states before the `keepState` of the server.
 * - The store keeps at most [maxStates] states. The server can send a diff from a state that the client did not
 *   acknowledge (wire fact 17), so the store keeps more than the newest state.
 *
 * The store never removes its newest state. State 0 is [initial] with the echo ack 0.
 *
 * Not safe for use by two threads at the same time.
 */
class HostStateStore<S>(
    initial: S,
    private val maxStates: Int = MAX_STATES,
    private val applyEvents: (state: S, events: List<HostEvent>) -> S,
) {
    /** One kept state. */
    class Entry<S>(val number: ULong, val state: S, val echoAck: ULong)

    // Sorted by number. The last entry is the newest state.
    private val entries = ArrayList<Entry<S>>()
    private var keepFloor = 0uL

    init {
        require(maxStates >= 1) { "The store must keep at least one state, not $maxStates." }
        entries += Entry(0uL, initial, 0uL)
    }

    /** The newest state, which has the highest number. */
    val newest: Entry<S> get() = entries.last()

    /** The count of kept states. */
    val size: Int get() = entries.size

    /** Returns true when the store has the state [number]. */
    operator fun contains(number: ULong): Boolean = indexOf(number) >= 0

    /**
     * Applies [diff] to the state [fromState], and keeps the result as the state [toState]. Returns false, and changes
     * nothing, when the store does not have [fromState], when it has [toState], or when [toState] is not after
     * [fromState]. A state before the newest `keepState` of the server is of no use, so it also gives false.
     */
    internal fun apply(fromState: ULong, toState: ULong, diff: HostDiff): Boolean {
        if (toState <= fromState || toState < keepFloor || toState in this) return false
        val source = entries.getOrNull(indexOf(fromState)) ?: return false
        val echoAck = diff.events.filterIsInstance<HostEvent.EchoAck>().lastOrNull()?.state ?: source.echoAck
        val entry = Entry(toState, applyEvents(source.state, diff.events), echoAck)
        val insertAt = entries.indexOfFirst { kept -> kept.number > toState }.let { if (it < 0) entries.size else it }
        entries.add(insertAt, entry)
        while (entries.size > maxStates) entries.removeAt(0)
        // A state older than all kept states can go out at once.
        return toState in this
    }

    /** Removes the states before [keepState], except the newest state. */
    internal fun removeBefore(keepState: ULong) {
        if (keepState <= keepFloor) return
        keepFloor = keepState
        while (entries.size > 1 && entries.first().number < keepState) entries.removeAt(0)
    }

    private fun indexOf(number: ULong): Int = entries.indexOfFirst { entry -> entry.number == number }

    companion object {
        /** The largest count of kept states. This is a choice of this client. */
        const val MAX_STATES: Int = 32
    }
}
