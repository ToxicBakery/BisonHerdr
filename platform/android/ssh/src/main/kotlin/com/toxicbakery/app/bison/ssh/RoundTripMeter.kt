package com.toxicbakery.app.bison.ssh

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps the round trip time (RTT) of the SSH session of each machine.
 *
 * [record] gets the time of one round trip. The meter keeps an exponential moving average for each machine. The
 * first sample sets the average. Each sample after it moves the average by [SAMPLE_WEIGHT] of the difference.
 *
 * [record] gives the average in whole milliseconds to its publish function, at most one time in each
 * [PUBLISH_INTERVAL] for a machine. The value is 1 or more, because 0 tells that the app did not measure a round
 * trip yet. The publish function returns true when it changed the value that the app shows. When it returns false,
 * for example because the value did not change, the interval does not start, and the next sample can publish.
 *
 * [timeSource] measures the publish interval. A test gives a source with virtual time.
 */
internal class RoundTripMeter(private val timeSource: TimeSource = TimeSource.Monotonic) {

    private class Average(var valueMs: Double) {
        var lastPublish: TimeMark? = null
    }

    private val lock = Any()

    // Only a block that holds [lock] reads and writes this map.
    private val averages = mutableMapOf<String, Average>()

    /**
     * Adds the round trip [sample] of [machineId] to the average of the machine. When the publish interval of the
     * machine is over, it calls [publish] with the new average.
     */
    fun record(machineId: String, sample: Duration, publish: (rttMs: Int) -> Boolean) {
        val sampleMs = sample.toDouble(DurationUnit.MILLISECONDS).coerceAtLeast(0.0)
        synchronized(lock) {
            val average = averages[machineId]
            val updated = if (average == null) {
                Average(sampleMs).also { averages[machineId] = it }
            } else {
                average.apply { valueMs += SAMPLE_WEIGHT * (sampleMs - valueMs) }
            }
            if (updated.lastPublish?.let { it.elapsedNow() < PUBLISH_INTERVAL } == true) return
            if (publish(updated.valueMs.roundToInt().coerceAtLeast(1))) updated.lastPublish = timeSource.markNow()
        }
    }

    /** Returns the average of [machineId] in whole milliseconds, or null before the first sample. */
    fun averageMs(machineId: String): Int? =
        synchronized(lock) { averages[machineId]?.valueMs?.roundToInt()?.coerceAtLeast(1) }

    /** Removes the average of [machineId]. The next sample then starts a new average. */
    fun reset(machineId: String) {
        synchronized(lock) { averages.remove(machineId) }
    }

    companion object {
        /** The weight of a new sample in the average. The smoothed RTT of TCP uses the same weight (RFC 6298). */
        const val SAMPLE_WEIGHT: Double = 0.125

        /** The minimum time between two published values of one machine. */
        val PUBLISH_INTERVAL: Duration = 1.seconds
    }
}
