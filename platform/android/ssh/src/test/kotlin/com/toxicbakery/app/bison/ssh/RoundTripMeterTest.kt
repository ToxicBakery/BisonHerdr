package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Tests the average and the publish interval of [RoundTripMeter]. */
class RoundTripMeterTest {

    private val clock = TestTimeSource()
    private val meter = RoundTripMeter(clock)
    private val published = mutableListOf<Int>()

    private fun record(machineId: String, milliseconds: Int) {
        meter.record(machineId, milliseconds.milliseconds) { rttMs -> published.add(rttMs) }
    }

    @Test
    fun eachMachineHasItsOwnAverageAndInterval() {
        record("workbox", 40)
        record("gpu-rig", 200)
        assertEquals(listOf(40, 200), published)
        assertEquals(40, meter.averageMs("workbox"))
        assertEquals(200, meter.averageMs("gpu-rig"))
    }

    @Test
    fun aRoundTripOfLessThanOneMillisecondIsOneMillisecond() {
        // 0 tells that the app did not measure a round trip yet, so a measured value is 1 or more.
        meter.record("workbox", 300.microseconds) { rttMs -> published.add(rttMs) }
        assertEquals(listOf(1), published)
    }

    @Test
    fun aResetStartsANewAverageThatPublishesAtOnce() {
        record("workbox", 40)
        meter.reset("workbox")
        assertNull(meter.averageMs("workbox"))

        // The interval of the old average does not apply to the new average.
        record("workbox", 300)
        assertEquals(listOf(40, 300), published)
    }

    @Test
    fun theIntervalStartsOnlyAtAPublishThatChangedTheValue() {
        meter.record("workbox", 40.milliseconds) { false }
        record("workbox", 40)
        assertEquals(listOf(40), published)

        clock += RoundTripMeter.PUBLISH_INTERVAL / 2
        record("workbox", 120)
        assertEquals(listOf(40), published)

        clock += RoundTripMeter.PUBLISH_INTERVAL / 2
        record("workbox", 50)
        // 40 + (120 - 40) / 8 = 50, then 50 + (50 - 50) / 8 = 50.
        assertEquals(listOf(40, 50), published)
    }
}
