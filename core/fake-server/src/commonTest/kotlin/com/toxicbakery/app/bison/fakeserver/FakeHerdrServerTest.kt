package com.toxicbakery.app.bison.fakeserver

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.store.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        // This version has no Mosh transport (issue #22), so the demo machines use the SSH endpoint bridge.
        assertTrue(stateManager.activeTransportBackbone.value["workbox"] is ActiveBackbone.SshEndpoint)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun isFakeMachineAcceptsOnlyTheMachinesThatTheServerMade() = runTest {
        val userMachine = HostMachine(
            id = "host-gpu-box-tailnet-ts-net-ian",
            name = "gpu-box",
            hostname = "gpu-box.tailnet.ts.net",
            username = "ian",
        )
        val store = MachineStore(
            storeIOProvider = InMemoryStoreIOProvider(
                mapOf(MachineStore.MACHINES_FILE_NAME to appJson.encodeToString(listOf(userMachine))),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        store.awaitLoaded()
        val fakeServer = FakeHerdrServer(
            machineStore = store,
            sshStateManager = SshStateManager(),
            sshDebugLog = SshDebugLog(),
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = PaneSurfaceBus(),
            semanticNotificationBus = SemanticNotificationBus(),
        )

        // The seeded demo machines are fake also before enable(). A .ts.net host name does not make a machine fake.
        assertTrue(fakeServer.isFakeMachine(FakeHerdrServer.WORKBOX_ID))
        assertTrue(fakeServer.isFakeMachine(FakeHerdrServer.GPU_RIG_ID))
        assertFalse(fakeServer.isFakeMachine(userMachine.id))

        fakeServer.enable()

        // The server simulates a stored user machine, but the server did not make that machine.
        assertTrue(fakeServer.handleConnect(userMachine.id))
        assertFalse(fakeServer.isFakeMachine(userMachine.id))

        // An id with no store entry gets an ad hoc machine that the server made.
        assertTrue(fakeServer.handleConnect("demo-adhoc"))
        assertTrue(fakeServer.isFakeMachine("demo-adhoc"))
    }
}
