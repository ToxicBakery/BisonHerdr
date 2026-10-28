package com.toxicbakery.app.bison.ssh

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the delays and the jitter of [ReconnectBackoff].
 */
class ReconnectBackoffTest {

    @Test
    fun withNoJitterTheDelaysAreOneTwoFourEightSixteenAndThenThirtySeconds() {
        val backoff = ReconnectBackoff(NoJitterRandom)

        val delays = (1..9).map(backoff::delayAfter)

        assertEquals(
            listOf(1, 2, 4, 8, 16, 30, 30, 30, 30).map { it.seconds },
            delays,
        )
    }

    @Test
    fun theMaximumJitterAddsTwentyFivePercent() {
        val backoff = ReconnectBackoff(MaxJitterRandom)

        (1..8).forEach { failures ->
            val base = baseFor(failures)
            val delay = backoff.delayAfter(failures)
            // Duration keeps nanoseconds, so the largest jitter is 25 percent after the rounding.
            assertEquals(base * 1.25, delay, "The delay for $failures failures")
        }
    }

    @Test
    fun eachRandomDelayIsInTheJitterBounds() {
        val backoff = ReconnectBackoff(Random(SEED))

        (1..8).forEach { failures ->
            val base = baseFor(failures)
            val delays = List(SAMPLES) { backoff.delayAfter(failures) }
            delays.forEach { delay ->
                assertTrue(delay >= base && delay <= base * 1.25, "Delay $delay for $failures failures is out of bounds")
            }
            assertTrue(delays.distinct().size > 1, "The delays for $failures failures have no jitter")
        }
    }

    @Test
    fun zeroFailuresIsAnError() {
        assertFailsWith<IllegalArgumentException> { ReconnectBackoff().delayAfter(0) }
    }

    private fun baseFor(failures: Int): Duration =
        ReconnectBackoff.BASE_DELAYS[(failures - 1).coerceAtMost(ReconnectBackoff.BASE_DELAYS.lastIndex)]

    /** A [Random] with [nextDouble] equal to 0. */
    private object NoJitterRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** A [Random] with [nextDouble] almost equal to 1. */
    private object MaxJitterRandom : Random() {
        override fun nextBits(bitCount: Int): Int =
            if (bitCount >= Int.SIZE_BITS) -1 else (1 shl bitCount) - 1
    }

    private companion object {
        const val SEED = 20
        const val SAMPLES = 1_000
    }
}
