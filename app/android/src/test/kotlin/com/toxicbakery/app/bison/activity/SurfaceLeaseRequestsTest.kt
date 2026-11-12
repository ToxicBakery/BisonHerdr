package com.toxicbakery.app.bison.activity

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Tests [sendSurfaceLeaseRequests], the lease requests of `MainActivity.onStart` and `MainActivity.onStop`.
 */
class SurfaceLeaseRequestsTest {

    private val counter = StartedActivityCounter()
    private val stateManager = SshStateManager().apply {
        markMachineConnected("lan-box", ActiveBackbone.SshEndpoint(rttMs = 1))
        markMachineConnected("ts-box", ActiveBackbone.SshEndpoint(rttMs = 1))
    }
    private val requestManager = SshRequestManager()

    @Test
    fun onStartTurnsTheLeaseOfEachConnectedMachineOn() = runTest {
        counter.onActivityStarted()

        sendSurfaceLeaseRequests(active = true, counter, stateManager, requestManager)

        assertEquals(
            setOf(SshRequest.SetSurfaceLease("lan-box", true), SshRequest.SetSurfaceLease("ts-box", true)),
            requestManager.bus.take(2).toList().toSet(),
        )
    }

    @Test
    fun theStopOfTheLastActivityTurnsTheLeaseOfEachConnectedMachineOff() = runTest {
        counter.onActivityStarted()
        counter.onActivityStopped()

        sendSurfaceLeaseRequests(active = false, counter, stateManager, requestManager)

        assertEquals(
            setOf(SshRequest.SetSurfaceLease("lan-box", false), SshRequest.SetSurfaceLease("ts-box", false)),
            requestManager.bus.take(2).toList().toSet(),
        )
    }

    @Test
    fun aStopWhileAnotherActivityIsStartedKeepsTheLeasesOn() = runTest {
        counter.onActivityStarted()
        counter.onActivityStarted()
        counter.onActivityStopped()

        sendSurfaceLeaseRequests(active = false, counter, stateManager, requestManager)

        // The function sent no request: the next request in the queue is the marker.
        assertTrue(requestManager.tryEmit(SshRequest.Disconnect("marker")))
        assertEquals(SshRequest.Disconnect("marker"), requestManager.bus.first())
    }
}
