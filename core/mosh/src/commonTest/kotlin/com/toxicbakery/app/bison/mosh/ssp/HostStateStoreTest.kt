package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.HostDiff
import com.toxicbakery.app.bison.mosh.wire.HostEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The receiver rules of decision 4 for the host states. */
class HostStateStoreTest {

    @Test
    fun aDiffAppliesOnlyWhenTheStoreHasTheSourceAndNotTheTarget() {
        val store = textStore()
        assertTrue(store.apply(0uL, 1uL, text("a")))
        assertEquals("a", store.newest.state)
        // A second copy of the same instruction has no effect.
        assertFalse(store.apply(0uL, 1uL, text("a")))
        // A diff from a state that the store does not have.
        assertFalse(store.apply(5uL, 6uL, text("x")))
        assertEquals(1uL, store.newest.number)
        assertEquals("a", store.newest.state)
    }

    @Test
    fun chainedDiffsApplyAndALateLinkCompletesTheChain() {
        // The server sends diffs from states that the client did not acknowledge (wire fact 17).
        val store = textStore()
        store.apply(0uL, 1uL, text("a"))
        assertFalse(store.apply(2uL, 3uL, text("c")))
        assertTrue(store.apply(1uL, 2uL, text("b")))
        assertTrue(store.apply(2uL, 3uL, text("c")))
        assertEquals("abc", store.newest.state)
    }

    @Test
    fun aTargetThatIsNotAfterTheSourceDoesNotApply() {
        val store = textStore()
        store.apply(0uL, 4uL, text("a"))
        assertFalse(store.apply(4uL, 4uL, text("b")))
        assertFalse(store.apply(4uL, 2uL, text("b")))
    }

    @Test
    fun theStoreRemovesTheStatesBeforeTheKeepStateButNotTheNewest() {
        val store = textStore()
        store.apply(0uL, 1uL, text("a"))
        store.apply(1uL, 2uL, text("b"))
        store.removeBefore(2uL)
        assertEquals(1, store.size)
        assertFalse(0uL in store)
        store.removeBefore(9uL)
        assertEquals(1, store.size)
        assertTrue(2uL in store)
        // A state before the keep state is of no use.
        assertFalse(store.apply(2uL, 3uL, text("c")))
        assertTrue(store.apply(2uL, 10uL, text("c")))
    }

    @Test
    fun theStoreKeepsAtMostTheLimitOfStates() {
        val store = textStore()
        for (number in 1uL..100uL) {
            assertTrue(store.apply(number - 1uL, number, text("x")))
            assertTrue(store.size <= HostStateStore.MAX_STATES)
        }
        assertEquals(HostStateStore.MAX_STATES, store.size)
        assertEquals(100uL, store.newest.number)
        assertTrue(69uL in store)
        assertFalse(68uL in store)
    }

    @Test
    fun eachStateHasTheEchoAckOfItsDiffOrOfItsSource() {
        val store = textStore()
        store.apply(0uL, 1uL, HostDiff(listOf(HostEvent.EchoAck(3uL))))
        store.apply(1uL, 2uL, text("a"))
        assertEquals(3uL, store.newest.echoAck)
        store.apply(2uL, 3uL, HostDiff(listOf(HostEvent.EchoAck(4uL), HostEvent.EchoAck(5uL))))
        assertEquals(5uL, store.newest.echoAck)
    }

    @Test
    fun aLimitBelowOneIsAnError() {
        assertFailsWith<IllegalArgumentException> { HostStateStore("", maxStates = 0) { state, _ -> state } }
    }

    private fun textStore() = HostStateStore("") { state: String, events ->
        state + events.filterIsInstance<HostEvent.HostBytes>().joinToString("") { it.bytes.decodeToString() }
    }

    private fun text(value: String) = HostDiff(listOf(HostEvent.HostBytes(value.encodeToByteArray())))
}
