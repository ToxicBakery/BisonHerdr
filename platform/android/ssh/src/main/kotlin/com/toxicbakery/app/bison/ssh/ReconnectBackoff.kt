package com.toxicbakery.app.bison.ssh

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Calculates the delays of the reconnect loop of [SessionSupervisor]: 1, 2, 4, 8, and 16 s, and then 30 s for each
 * attempt that follows.
 *
 * Each delay gets a random jitter of 0 to 25 percent from [random]. Thus many clients that lost the same network do
 * not connect again at the same time. A test gives a [random] with a known result.
 */
internal class ReconnectBackoff(
    private val random: Random = Random.Default,
) {

    /** Returns the delay after [failures] failed attempts in sequence. [failures] is 1 or more. */
    fun delayAfter(failures: Int): Duration {
        require(failures >= 1) { "failures must be 1 or more, not $failures" }
        val base = BASE_DELAYS[(failures - 1).coerceAtMost(BASE_DELAYS.lastIndex)]
        return base + base * (random.nextDouble() * MAX_JITTER)
    }

    companion object {
        /** The delays with no jitter. The last delay applies to each attempt after the list. */
        val BASE_DELAYS: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds)

        /** The maximum jitter as a fraction of the delay. */
        const val MAX_JITTER: Double = 0.25
    }
}
