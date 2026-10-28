package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.service.ServiceStopPolicy.Companion.IDLE_TIMEOUT
import com.toxicbakery.app.bison.service.ServiceStopPolicy.Companion.isConnectStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests the 30 s idle rule of [ServiceStopPolicy] with the virtual time of `kotlinx-coroutines-test`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStopPolicyTest {

    private val connectedMachines = MutableStateFlow<Set<String>>(emptySet())
    private val connectionProgress = MutableStateFlow<Map<String, ConnectionProgress>>(emptyMap())
    private val anyActivityStarted = MutableStateFlow(false)
    private val policy = ServiceStopPolicy()

    @Test
    fun idleTimeoutIsThirtySeconds() {
        assertEquals(30.seconds, IDLE_TIMEOUT)
    }

    @Test
    fun stopsThirtySecondsAfterTheServiceBecomesIdle() = runTest {
        val stop = startPolicy()

        assertStopsAfter(stop, IDLE_TIMEOUT)
        assertEquals(IDLE_TIMEOUT.inWholeMilliseconds, currentTime)
    }

    @Test
    fun doesNotStopWhileAnActivityIsStarted() = runTest {
        anyActivityStarted.value = true
        val stop = startPolicy()

        advanceAndRun(10.minutes)
        assertFalse(stop.isCompleted)

        anyActivityStarted.value = false
        runCurrent()
        assertStopsAfter(stop, IDLE_TIMEOUT)
    }

    @Test
    fun doesNotStopWhileAMachineIsConnected() = runTest {
        connectedMachines.value = setOf("workbox")
        anyActivityStarted.value = true
        val stop = startPolicy()

        // The user leaves the app. A connected machine keeps the service open.
        anyActivityStarted.value = false
        advanceAndRun(10.minutes)
        assertFalse(stop.isCompleted)

        connectedMachines.value = emptySet()
        runCurrent()
        assertStopsAfter(stop, IDLE_TIMEOUT)
    }

    @Test
    fun aConnectionBeforeTheEndOfTheTimeoutStartsANewTimeout() = runTest {
        val stop = startPolicy()

        advanceAndRun(20.seconds)
        connectedMachines.value = setOf("workbox")
        advanceAndRun(5.seconds)
        connectedMachines.value = emptySet()
        runCurrent()

        // The first timeout ends at 30 s. The machine cancelled it, so the new timeout ends at 55 s.
        assertStopsAfter(stop, IDLE_TIMEOUT)
        assertEquals(55.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun aStartedActivityBeforeTheEndOfTheTimeoutCancelsTheStop() = runTest {
        val stop = startPolicy()

        advanceAndRun(29.seconds)
        anyActivityStarted.value = true
        advanceAndRun(10.minutes)
        assertFalse(stop.isCompleted)

        anyActivityStarted.value = false
        runCurrent()
        assertStopsAfter(stop, IDLE_TIMEOUT)
    }

    @Test
    fun aDifferentSetOfConnectedMachinesDoesNotStartANewTimeout() = runTest {
        connectedMachines.value = setOf("workbox")
        val stop = startPolicy()

        connectedMachines.value = setOf("workbox", "gpu-rig")
        advanceAndRun(10.minutes)
        assertFalse(stop.isCompleted)

        connectedMachines.value = emptySet()
        runCurrent()
        assertStopsAfter(stop, IDLE_TIMEOUT)
    }

    @Test
    fun doesNotStopWhileAConnectionIsInProgress() = runTest {
        connectionProgress.value = mapOf("workbox" to ConnectionProgress.AuthenticatingSsh)
        val stop = startPolicy()

        // The user leaves the app during a slow connect. A stop now cancels the connect.
        advanceAndRun(10.minutes)
        assertFalse(stop.isCompleted)

        connectionProgress.value = mapOf("workbox" to ConnectionProgress.Failed)
        runCurrent()
        assertStopsAfter(stop, IDLE_TIMEOUT)
    }

    @Test
    fun aConnectStepBeforeTheEndOfTheTimeoutStartsANewTimeout() = runTest {
        connectionProgress.value = mapOf("workbox" to ConnectionProgress.Idle)
        val stop = startPolicy()

        advanceAndRun(20.seconds)
        connectionProgress.value = mapOf("workbox" to ConnectionProgress.SyncingHerdrSnapshot)
        advanceAndRun(5.seconds)
        connectionProgress.value = mapOf("workbox" to ConnectionProgress.Idle)
        runCurrent()

        assertStopsAfter(stop, IDLE_TIMEOUT)
        assertEquals(55.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun onlyTheMiddleStepsOfAConnectAreConnectSteps() {
        val connectSteps = ConnectionProgress.entries.filter { step -> step.isConnectStep() }
        assertEquals(
            listOf(
                ConnectionProgress.AuthenticatingSsh,
                ConnectionProgress.BootstrappingMoshOrBridge,
                ConnectionProgress.VerifyingUdpOrHello,
                ConnectionProgress.SyncingHerdrSnapshot,
            ),
            connectSteps,
        )
    }

    @Test
    fun eachConnectStepKeepsTheServiceBusy() = runTest {
        ConnectionProgress.entries.filter { step -> step.isConnectStep() }.forEach { step ->
            connectionProgress.value = mapOf("workbox" to step, "gpu-rig" to ConnectionProgress.Failed)
            val stop = startPolicy()
            advanceAndRun(10.minutes)
            assertFalse(stop.isCompleted, "$step did not keep the service busy")
            stop.cancel()
        }
    }

    private fun TestScope.startPolicy(): Job {
        val job = launch {
            policy.awaitIdleTimeout(
                connectedMachines = connectedMachines,
                connectionProgress = connectionProgress,
                anyActivityStarted = anyActivityStarted,
            )
        }
        runCurrent()
        return job
    }

    private fun TestScope.advanceAndRun(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    /** Makes sure that [stop] completes after [duration] and not 1 ms before. */
    private fun TestScope.assertStopsAfter(stop: Job, duration: Duration) {
        advanceAndRun(duration - 1.milliseconds)
        assertFalse(stop.isCompleted, "The policy stopped the service before the end of the timeout")
        advanceAndRun(1.milliseconds)
        assertTrue(stop.isCompleted, "The policy did not stop the service at the end of the timeout")
    }
}
