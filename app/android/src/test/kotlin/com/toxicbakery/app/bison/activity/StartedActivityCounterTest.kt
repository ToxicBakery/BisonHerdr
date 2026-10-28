package com.toxicbakery.app.bison.activity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class StartedActivityCounterTest {

    @Test
    fun anyStartedIsTrueUntilTheLastActivityStops() = runTest {
        val counter = StartedActivityCounter()
        assertFalse(counter.anyStarted.first())

        counter.onActivityStarted()
        counter.onActivityStarted()
        counter.onActivityStopped()
        assertTrue(counter.anyStarted.first())

        counter.onActivityStopped()
        assertFalse(counter.anyStarted.first())
    }

    @Test
    fun aStopWithNoStartDoesNotMakeTheCountNegative() = runTest {
        val counter = StartedActivityCounter()
        counter.onActivityStopped()

        counter.onActivityStarted()
        assertTrue(counter.anyStarted.first())
    }
}
