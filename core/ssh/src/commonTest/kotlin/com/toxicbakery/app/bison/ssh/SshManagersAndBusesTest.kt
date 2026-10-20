package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.SemanticNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class SshManagersAndBusesTest {

    @Test
    fun sshStateManagerTracksConnectionsBackboneRoamingAndOfflinePromptQueue() {
        val stateManager = SshStateManager()
        val moshBackbone = ActiveBackbone.MoshUdp(udpPort = 60004, rttMs = 11, localEchoEnabled = true)

        stateManager.setConnectionProgress("workbox", ConnectionProgress.AuthenticatingSsh)
        assertEquals(ConnectionProgress.AuthenticatingSsh, stateManager.connectionProgress.value["workbox"])

        stateManager.markMachineConnected("workbox", moshBackbone)
        assertTrue(stateManager.isMachineConnected("workbox"))
        assertEquals(moshBackbone, stateManager.activeTransportBackbone.value["workbox"])
        assertEquals(ConnectionProgress.Connected, stateManager.connectionProgress.value["workbox"])
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value["workbox"])

        stateManager.setRoamingStatus("workbox", RoamingStatus.SurfaceParked)
        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value["workbox"])

        // Enqueue prompts while roaming
        stateManager.enqueuePrompt("workbox", "w1:p1", "Run ./gradlew test", agentName = "claude")
        stateManager.enqueuePrompt("workbox", "w1:p1", "Check git status", agentName = "claude")
        stateManager.enqueuePrompt("gpu-rig", "w2:p1", "cargo test")

        assertEquals(2, stateManager.queuedPromptsForPane("workbox", "w1:p1").size)
        assertEquals(2, stateManager.queuedPromptsForMachine("workbox").size)

        val flushedWorkbox = stateManager.flushQueuedPrompts("workbox", "w1:p1")
        assertEquals(listOf("Run ./gradlew test", "Check git status"), flushedWorkbox.map { it.prompt })
        assertTrue(stateManager.queuedPromptsForPane("workbox", "w1:p1").isEmpty())
        assertEquals(1, stateManager.queuedPromptsForMachine("gpu-rig").size)

        stateManager.markMachineDisconnected("workbox")
        assertFalse(stateManager.isMachineConnected("workbox"))
        assertNull(stateManager.activeTransportBackbone.value["workbox"])
    }

    @Test
    fun sshRequestManagerEmitsAndReceivesRequests() = runTest {
        val requestManager = SshRequestManager()
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            requestManager.bus.first()
        }
        assertTrue(requestManager.tryEmit(SshRequest.Connect("workbox")))
        val received = deferred.await()
        assertEquals(SshRequest.Connect("workbox"), received)
    }

    @Test
    fun endpointSnapshotPaneSurfaceAndNotificationBusesRetainState() = runTest {
        val snapshotBus = EndpointSnapshotBus()
        val surfaceBus = PaneSurfaceBus()
        val notificationBus = SemanticNotificationBus()
        val debugLog = SshDebugLog(maxEntries = 3)

        val snapshot = ClientShellSnapshot(bootId = "boot-demo", revision = 1u)
        snapshotBus.publish("workbox", snapshot)
        assertEquals(snapshot, snapshotBus.snapshotFor("workbox"))

        val frame = PaneSurfaceFrame(
            bootId = "boot-demo",
            revision = 1u,
            frame = FrameData(width = 10u, height = 4u, cells = List(40) { CellData(symbol = "x") }),
        )
        surfaceBus.publish("workbox", frame)
        assertNotNull(surfaceBus.surfaceFor("workbox"))

        notificationBus.publish(
            "workbox",
            SemanticNotification(
                title = "Claude Code Blocked",
                body = "Allow Bash: ./gradlew :app:android:assembleDebug?",
                paneId = "w1:p1",
                agentName = "claude",
                agentStatus = AgentStatus.Blocked,
            ),
        )
        notificationBus.updateAgentSnippet(
            machineId = "workbox",
            paneId = "w1:p1",
            snippet = "Allow Bash: ./gradlew :app:android:assembleDebug?",
        )
        assertEquals(1, notificationBus.recentNotifications.value.size)
        assertEquals(
            "Allow Bash: ./gradlew :app:android:assembleDebug?",
            notificationBus.snippetFor("workbox", "w1:p1"),
        )

        debugLog.log("workbox", "Step 1")
        debugLog.log("workbox", "Step 2")
        debugLog.log("workbox", "Step 3")
        debugLog.log("workbox", "Step 4")
        assertEquals(3, debugLog.entries.value.size)
        assertEquals("Step 2", debugLog.entries.value.first().message)

        // Identical messages recorded at the same timestamp must still receive unique monotonic IDs
        debugLog.record(machineId = "lan-host", tag = "RealSSH", message = "SSH connection failed", isError = true, timestampEpochMs = 0L)
        debugLog.record(machineId = "lan-host", tag = "RealSSH", message = "SSH connection failed", isError = true, timestampEpochMs = 0L)
        val lanEntries = debugLog.entries.value.filter { it.machineId == "lan-host" }
        assertEquals(2, lanEntries.size)
        assertTrue(lanEntries[0].id > 0L)
        assertTrue(lanEntries[1].id > lanEntries[0].id)
    }
}
