package com.toxicbakery.app.bison.mosh.ssp

import com.toxicbakery.app.bison.mosh.wire.UserDiff
import com.toxicbakery.app.bison.mosh.wire.UserEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The user states and the diff of the sender rules of decision 4. */
class UserStreamTest {

    @Test
    fun aNewStateTakesAllPendingEventsAndTheNumberIncreasesByOne() {
        val stream = UserStream()
        assertEquals(1, stream.push(keys("a")))
        assertEquals(2, stream.push(UserEvent.Resize(80, 24)))
        assertTrue(stream.numberPending())
        assertEquals(1uL, stream.currentState)
        assertFalse(stream.hasPending)
        assertFalse(stream.numberPending())
        stream.push(keys("b"))
        assertTrue(stream.numberPending())
        assertEquals(2uL, stream.currentState)
        assertEquals(3, stream.numberedCount)
    }

    @Test
    fun theDiffHoldsAllEventsAfterTheAcknowledgedStateWithKeysJoined() {
        val stream = UserStream()
        stream.push(keys("a"))
        stream.push(keys("b"))
        stream.numberPending()
        stream.push(UserEvent.Resize(80, 24))
        stream.push(keys("c"))
        stream.numberPending()
        stream.push(keys("d"))
        stream.numberPending()
        assertEquals(
            UserDiff(listOf(keys("ab"), UserEvent.Resize(80, 24), keys("cd"))),
            UserDiff.decode(stream.encodedDiff()),
        )

        stream.acknowledge(2uL)
        assertEquals(2uL, stream.acknowledgedState)
        assertEquals(UserDiff(listOf(keys("d"))), UserDiff.decode(stream.encodedDiff()))
        assertEquals(4, stream.acknowledgedCount)
    }

    @Test
    fun anOldOrUnknownAcknowledgmentHasNoEffect() {
        val stream = UserStream()
        stream.push(keys("a"))
        stream.numberPending()
        stream.acknowledge(5uL)
        assertEquals(0uL, stream.acknowledgedState)
        stream.acknowledge(1uL)
        stream.acknowledge(0uL)
        assertEquals(1uL, stream.acknowledgedState)
        assertFalse(stream.hasUnacknowledged)
        assertEquals(UserDiff(emptyList()), UserDiff.decode(stream.encodedDiff()))
    }

    @Test
    fun twoResizesStayTwoEventsInTheirOrder() {
        // "Refresh pane" sends one row less and then the correct size. The two sizes must both reach the server.
        val stream = UserStream()
        stream.push(UserEvent.Resize(80, 23))
        stream.push(UserEvent.Resize(80, 24))
        stream.numberPending()
        assertEquals(
            UserDiff(listOf(UserEvent.Resize(80, 23), UserEvent.Resize(80, 24))),
            UserDiff.decode(stream.encodedDiff()),
        )
    }

    @Test
    fun aLargeKeyEventBecomesPartsAndTheDiffStaysInTheLimit() {
        val part = 16 * 1024
        val stream = UserStream(maxDiffSize = 40_000, maxKeysPartSize = part)
        val paste = ByteArray(100_000) { index -> ('a' + index % 26).code.toByte() }
        assertEquals(7, stream.push(UserEvent.Keys(paste)))
        assertTrue(stream.numberPending())
        // Two parts of 16 KiB fit in 40,000 bytes. The third part waits for an acknowledgment.
        val first = assertNotNull(UserDiff.decode(stream.encodedDiff()))
        assertEquals(2 * part, (first.events.single() as UserEvent.Keys).bytes.size)
        assertTrue(stream.hasPending)
        assertFalse(stream.canNumber)
        assertFalse(stream.numberPending())

        val received = ArrayList<Byte>()
        received += (first.events.single() as UserEvent.Keys).bytes.toList()
        while (stream.hasUnacknowledged) {
            stream.acknowledge(stream.currentState)
            if (stream.numberPending()) {
                val diff = assertNotNull(UserDiff.decode(stream.encodedDiff()))
                assertTrue(stream.encodedDiff().size <= 40_000)
                received += (diff.events.single() as UserEvent.Keys).bytes.toList()
            }
        }
        assertContentEquals(paste, received.toByteArray())
        assertEquals(7, stream.acknowledgedCount)
    }

    @Test
    fun byDefaultOnePartFillsOneStateAndEachDiffStaysInTheLimit() {
        val stream = UserStream()
        val paste = ByteArray(20_000) { index -> ('a' + index % 26).code.toByte() }
        stream.push(UserEvent.Keys(paste))
        stream.push(keys("z"))
        val sizes = ArrayList<Int>()
        while (stream.numberPending()) {
            val diff = assertNotNull(UserDiff.decode(stream.encodedDiff()))
            sizes += diff.events.filterIsInstance<UserEvent.Keys>().sumOf { event -> event.bytes.size }
            assertTrue(stream.encodedDiff().size <= UserStream.MAX_DIFF_SIZE)
            stream.acknowledge(stream.currentState)
        }
        val part = UserStream.MAX_DIFF_SIZE - UserStream.EVENT_OVERHEAD
        assertEquals(listOf(part, part, 20_000 - 2 * part + 1), sizes)
    }

    @Test
    fun aLimitThatCannotHoldAKeyPartIsAnError() {
        // Such a stream could never send a part, and the input would stop for ever.
        assertFailsWith<IllegalArgumentException> { UserStream(maxDiffSize = 1000, maxKeysPartSize = 1000) }
        assertFailsWith<IllegalArgumentException> { UserStream(maxDiffSize = 1000, maxKeysPartSize = 0) }
        UserStream(maxDiffSize = 1000, maxKeysPartSize = 1000 - UserStream.EVENT_OVERHEAD)
    }

    @Test
    fun theEchoAckGivesTheCountOfEventsThatTheApplicationGot() {
        val stream = UserStream()
        stream.push(keys("a"))
        stream.push(keys("b"))
        stream.numberPending()
        stream.push(keys("c"))
        stream.numberPending()
        stream.acknowledge(2uL)
        assertEquals(0, stream.echoedCount)
        stream.echo(1uL)
        assertEquals(2, stream.echoedCount)
        stream.echo(1uL)
        stream.echo(9uL)
        assertEquals(2, stream.echoedCount)
        stream.echo(2uL)
        assertEquals(3, stream.echoedCount)
    }

    private fun keys(text: String) = UserEvent.Keys(text.encodeToByteArray())
}
