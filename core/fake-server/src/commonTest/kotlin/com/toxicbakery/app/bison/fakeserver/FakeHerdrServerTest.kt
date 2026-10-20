package com.toxicbakery.app.bison.fakeserver

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FakeHerdrServerTest {

    @Test
    fun enableSeedsWorkboxAndGpuRigWithMoshBackboneSplitPanesAndBlockedClaudeAgent() = runTest {
        val stateManager = SshStateManager()
        val debugLog = SshDebugLog()
        val snapshotBus = EndpointSnapshotBus()
        val surfaceBus = PaneSurfaceBus()
        val notificationBus = SemanticNotificationBus()

        val fakeServer = FakeHerdrServer(
            machineStore = null,
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            endpointSnapshotBus = snapshotBus,
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = notificationBus,
        )

        assertFalse(fakeServer.isEnabled)
        fakeServer.enable()
        assertTrue(fakeServer.isEnabled)

        assertTrue(stateManager.isMachineConnected("workbox"))
        assertTrue(stateManager.isMachineConnected("gpu-rig"))
        assertTrue(stateManager.activeTransportBackbone.value["workbox"] is ActiveBackbone.MoshUdp)
        assertTrue(stateManager.activeTransportBackbone.value["gpu-rig"] is ActiveBackbone.SshEndpoint)

        val workboxSnapshot = snapshotBus.snapshotFor("workbox")
        assertNotNull(workboxSnapshot)
        assertEquals(3, workboxSnapshot.workspaces.size)
        assertEquals(AgentStatus.Blocked, workboxSnapshot.agents.first { it.name == "claude" }.agentStatus)

        val workboxSurface = surfaceBus.surfaceFor("workbox")
        assertNotNull(workboxSurface)
        assertEquals(3, workboxSurface.panes.size)
        assertEquals(2, workboxSurface.splits.size)

        // Surface lease parking and unparking
        assertTrue(fakeServer.handleSetSurfaceLease("workbox", active = false))
        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value["workbox"])
        assertTrue(fakeServer.handleSetSurfaceLease("workbox", active = true))
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value["workbox"])

        // Dragging split ratio updates PaneSurfaceFrame splits
        val ratioReq = HerdrRpcBuilder.layoutSetSplitRatio(path = emptyList(), ratio = 0.65f)
        assertNotNull(
            fakeServer.handleEndpointCommand("workbox", ratioReq.method, ratioReq.paramsJson),
        )
        val updatedSurface = surfaceBus.surfaceFor("workbox")
        assertNotNull(updatedSurface)
        assertEquals(0.65f, updatedSurface.splits.first().ratio)

        // Toggling pane zoom collapses splits to emptyList
        val zoomReq = HerdrRpcBuilder.paneZoom(paneId = "w1:p1", mode = "toggle")
        fakeServer.handleEndpointCommand("workbox", zoomReq.method, zoomReq.paramsJson)
        assertTrue(surfaceBus.surfaceFor("workbox")!!.splits.isEmpty())
        fakeServer.handleEndpointCommand("workbox", zoomReq.method, zoomReq.paramsJson)
        assertEquals(2, surfaceBus.surfaceFor("workbox")!!.splits.size)

        // Approving blocked Claude Code agent transitions it from Blocked to Working
        assertTrue(fakeServer.handlePaneInput("workbox", "w1:p1", """{"type":"key","code":"enter"}"""))
        val afterApprove = snapshotBus.snapshotFor("workbox")!!
        assertEquals(
            AgentStatus.Working,
            afterApprove.agents.first { it.name == "claude" }.agentStatus,
        )
    }
}
