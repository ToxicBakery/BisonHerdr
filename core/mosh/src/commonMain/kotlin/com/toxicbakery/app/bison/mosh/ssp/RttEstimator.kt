package com.toxicbakery.app.bison.mosh.ssp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Estimates the round-trip time (RTT) of one session with the algorithm of RFC 6298, with the changes of section
 * 2.2 of the Mosh paper (wire fact 13):
 * - The minimum retransmission timeout (RTO) is [MIN_RTO_MS], not 1 s.
 * - Each datagram has its own sequence number and timestamp, so a sample after a retransmission is valid.
 *
 * This client also caps the RTO at [MAX_RTO_MS], so that a key never waits more than 1 s for a retransmission.
 * Before the first sample, the RTO is [INITIAL_RTO_MS]: the client sends the first state each 250 ms while it waits
 * for the first reply.
 *
 * The frame interval is the shortest time between two new user states: SRTT / 2, in the range
 * [MIN_FRAME_INTERVAL_MS] to [MAX_FRAME_INTERVAL_MS] (wire fact 14).
 */
class RttEstimator {
    private var smoothed = 0.0
    private var variation = 0.0
    private var backoffCount = 0

    /** True after the first sample. */
    var hasSample: Boolean = false
        private set

    /** The smoothed RTT (SRTT) in milliseconds, or null before the first sample. */
    val smoothedRttMs: Long? get() = if (hasSample) smoothed.roundToLong() else null

    /** The retransmission timeout in milliseconds, with the back-off. */
    val rtoMs: Long
        get() {
            if (!hasSample) return INITIAL_RTO_MS
            val base = ceil(smoothed + maxOf(CLOCK_GRANULARITY_MS, K * variation)).toLong()
                .coerceIn(MIN_RTO_MS, MAX_RTO_MS)
            return (base shl backoffCount).coerceAtMost(MAX_RTO_MS)
        }

    /** The frame interval in milliseconds. */
    val frameIntervalMs: Long
        get() = if (hasSample) {
            (smoothed / 2).roundToLong().coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
        } else {
            MAX_FRAME_INTERVAL_MS
        }

    /** Adds one RTT sample of [sampleMs] milliseconds. A sample also ends the back-off (RFC 6298, section 5). */
    fun addSample(sampleMs: Long) {
        require(sampleMs >= 0) { "The RTT sample $sampleMs is negative." }
        val sample = sampleMs.toDouble()
        if (hasSample) {
            // RFC 6298 (2.3): RTTVAR uses the SRTT from before this sample.
            variation = (1 - BETA) * variation + BETA * abs(smoothed - sample)
            smoothed = (1 - ALPHA) * smoothed + ALPHA * sample
        } else {
            smoothed = sample
            variation = sample / 2
            hasSample = true
        }
        backoffCount = 0
    }

    /**
     * Doubles the RTO after a retransmission (RFC 6298, section 5.5), up to [MAX_RTO_MS]. Before the first sample,
     * the RTO does not change.
     */
    fun backOff() {
        if (hasSample && rtoMs < MAX_RTO_MS) backoffCount++
    }

    companion object {
        /** The smallest RTO (wire fact 13). */
        const val MIN_RTO_MS: Long = 50

        /** The largest RTO. This is a choice of this client. */
        const val MAX_RTO_MS: Long = 1000

        /** The RTO before the first sample. This is a choice of this client. */
        const val INITIAL_RTO_MS: Long = 250

        /** The shortest frame interval: a rate of 50 Hz (wire fact 14). */
        const val MIN_FRAME_INTERVAL_MS: Long = 20

        /** The longest frame interval. This is a choice of this client. */
        const val MAX_FRAME_INTERVAL_MS: Long = 250

        private const val ALPHA = 1.0 / 8
        private const val BETA = 1.0 / 4
        private const val K = 4.0

        // The timestamps count whole milliseconds.
        private const val CLOCK_GRANULARITY_MS = 1.0
    }
}
