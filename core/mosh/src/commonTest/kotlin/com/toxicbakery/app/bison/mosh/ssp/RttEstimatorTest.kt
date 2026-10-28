package com.toxicbakery.app.bison.mosh.ssp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The RTT estimate of RFC 6298 with the bounds of the Mosh paper and of this client. */
class RttEstimatorTest {

    @Test
    fun beforeTheFirstSampleTheRtoIsTheInitialValueAndDoesNotBackOff() {
        val estimator = RttEstimator()
        assertFalse(estimator.hasSample)
        assertNull(estimator.smoothedRttMs)
        assertEquals(RttEstimator.INITIAL_RTO_MS, estimator.rtoMs)
        assertEquals(RttEstimator.MAX_FRAME_INTERVAL_MS, estimator.frameIntervalMs)
        estimator.backOff()
        assertEquals(RttEstimator.INITIAL_RTO_MS, estimator.rtoMs)
    }

    @Test
    fun theFirstSampleSetsTheSmoothedRttAndHalfTheVariation() {
        val estimator = RttEstimator()
        estimator.addSample(200)
        assertTrue(estimator.hasSample)
        assertEquals(200, estimator.smoothedRttMs)
        // RTO = SRTT + 4 * RTTVAR = 200 + 4 * 100, and the cap is 1000.
        assertEquals(600, estimator.rtoMs)
        assertEquals(100, estimator.frameIntervalMs)
    }

    @Test
    fun laterSamplesUseTheGainsOfRfc6298() {
        val estimator = RttEstimator()
        estimator.addSample(100)
        estimator.addSample(180)
        // RTTVAR = 3/4 * 50 + 1/4 * |100 - 180| = 57.5. SRTT = 7/8 * 100 + 1/8 * 180 = 110.
        assertEquals(110, estimator.smoothedRttMs)
        assertEquals(340, estimator.rtoMs)
    }

    @Test
    fun theRtoStaysInItsBounds() {
        val fast = RttEstimator()
        repeat(20) { fast.addSample(0) }
        assertEquals(RttEstimator.MIN_RTO_MS, fast.rtoMs)
        assertEquals(RttEstimator.MIN_FRAME_INTERVAL_MS, fast.frameIntervalMs)

        val slow = RttEstimator()
        repeat(20) { slow.addSample(4000) }
        assertEquals(RttEstimator.MAX_RTO_MS, slow.rtoMs)
        assertEquals(RttEstimator.MAX_FRAME_INTERVAL_MS, slow.frameIntervalMs)
    }

    @Test
    fun theBackOffDoublesTheRtoUpToTheCapAndASampleEndsIt() {
        val estimator = RttEstimator()
        repeat(20) { estimator.addSample(10) }
        assertEquals(RttEstimator.MIN_RTO_MS, estimator.rtoMs)
        val rtos = List(7) {
            estimator.backOff()
            estimator.rtoMs
        }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1000L, 1000L, 1000L), rtos)
        estimator.addSample(10)
        assertEquals(RttEstimator.MIN_RTO_MS, estimator.rtoMs)
    }

    @Test
    fun theFrameIntervalIsHalfTheSmoothedRtt() {
        val estimator = RttEstimator()
        repeat(40) { estimator.addSample(120) }
        assertEquals(60, estimator.frameIntervalMs)
    }

    @Test
    fun aNegativeSampleIsAnError() {
        assertFailsWith<IllegalArgumentException> { RttEstimator().addSample(-1) }
    }
}
