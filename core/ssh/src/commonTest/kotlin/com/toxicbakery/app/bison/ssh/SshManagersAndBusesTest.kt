package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.KnownHost
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sshRequestManagerKeepsRequestsUntilTheFirstCollectorStarts() = runTest {
        val requestManager = SshRequestManager()

        // No collector exists yet, for example before BisonSessionService starts.
        requestManager.emit(SshRequest.SetSurfaceLease("workbox", active = true))
        assertTrue(requestManager.tryEmit(SshRequest.Connect("gpu-rig")))

        val received = mutableListOf<SshRequest>()
        val collector = launch { requestManager.bus.collect { received.add(it) } }
        runCurrent()

        assertEquals(
            listOf(
                SshRequest.SetSurfaceLease("workbox", active = true),
                SshRequest.Connect("gpu-rig"),
            ),
            received,
        )
        collector.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sshRequestManagerDoesNotReplayAnOldRequestToANewCollector() = runTest {
        val requestManager = SshRequestManager()

        val firstCollector = launch { requestManager.bus.first() }
        runCurrent()
        requestManager.emit(SshRequest.Disconnect("workbox"))
        runCurrent()
        assertTrue(firstCollector.isCompleted)

        // A new collector, for example a restarted service, must not get the old Disconnect again.
        val received = mutableListOf<SshRequest>()
        val secondCollector = launch { requestManager.bus.collect { received.add(it) } }
        runCurrent()
        assertTrue(received.isEmpty())

        requestManager.emit(SshRequest.Connect("workbox"))
        runCurrent()
        assertEquals(listOf<SshRequest>(SshRequest.Connect("workbox")), received)
        secondCollector.cancel()
    }

    @Test
    fun sshRequestManagerTryEmitFailsWhenTheQueueIsFull() = runTest {
        val requestManager = SshRequestManager()
        repeat(SshRequestManager.REQUEST_CAPACITY) { index ->
            assertTrue(requestManager.tryEmit(SshRequest.Connect("machine-$index")))
        }
        assertFalse(requestManager.tryEmit(SshRequest.Connect("overflow")))

        val requests = requestManager.bus.take(SshRequestManager.REQUEST_CAPACITY).toList()
        assertEquals(SshRequestManager.REQUEST_CAPACITY, requests.size)
        assertEquals(SshRequest.Connect("machine-0"), requests.first())
        assertEquals(SshRequest.Connect("machine-63"), requests.last())
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

    @Test
    fun hostKeyPromptsKeepOnePromptForEachMachine() {
        val stateManager = SshStateManager()
        val key = KnownHost(
            host = "devbox.local",
            port = 22,
            keyType = "ssh-ed25519",
            fingerprint = "SHA256:abc",
            publicKey = "AAAA",
        )
        val unknown = HostKeyPrompt.Unknown(machineId = "workbox", presented = key)
        val changed = HostKeyPrompt.Changed(
            machineId = "workbox",
            known = key,
            presented = key.copy(fingerprint = "SHA256:def"),
        )
        val other = HostKeyPrompt.Unknown(machineId = "gpu-rig", presented = key)

        stateManager.setHostKeyPrompt(unknown)
        stateManager.setHostKeyPrompt(other)
        stateManager.setHostKeyPrompt(changed)
        assertEquals(mapOf("workbox" to changed, "gpu-rig" to other), stateManager.hostKeyPrompts.value)

        stateManager.clearHostKeyPrompt("workbox")
        assertEquals(mapOf("gpu-rig" to other), stateManager.hostKeyPrompts.value)
    }

    @Test
    fun needsUserKeepsOneCauseForEachMachineAndAConnectRemovesIt() {
        val stateManager = SshStateManager()
        val refused = NeedsUserCause.LoginRefused("Auth fail")

        stateManager.setNeedsUser("workbox", NeedsUserCause.KeyMissing("work_key"))
        stateManager.setNeedsUser("gpu-rig", NeedsUserCause.PasswordMissing)
        stateManager.setNeedsUser("workbox", refused)
        assertEquals(
            mapOf("workbox" to refused, "gpu-rig" to NeedsUserCause.PasswordMissing),
            stateManager.needsUser.value,
        )

        stateManager.clearNeedsUser("gpu-rig")
        assertEquals(mapOf("workbox" to refused), stateManager.needsUser.value)

        stateManager.markMachineConnected("workbox", ActiveBackbone.SshEndpoint(rttMs = 10))
        assertTrue(stateManager.needsUser.value.isEmpty())
    }

    @Test
    fun setRoundTripTimeChangesOnlyTheBackboneOfAConnectedMachine() {
        val stateManager = SshStateManager()
        val reason = "The host has mosh-server"

        // A machine with no backbone does not get one.
        assertFalse(stateManager.setRoundTripTime("workbox", 42))
        assertNull(stateManager.activeTransportBackbone.value["workbox"])

        stateManager.markMachineConnected("workbox", ActiveBackbone.SshEndpoint(rttMs = 0, fallbackReason = reason))
        assertTrue(stateManager.setRoundTripTime("workbox", 42))
        assertEquals(
            ActiveBackbone.SshEndpoint(rttMs = 42, fallbackReason = reason),
            stateManager.activeTransportBackbone.value["workbox"],
        )

        // The same value is no change.
        assertFalse(stateManager.setRoundTripTime("workbox", 42))

        stateManager.markMachineDisconnected("workbox")
        assertFalse(stateManager.setRoundTripTime("workbox", 50))
        assertNull(stateManager.activeTransportBackbone.value["workbox"])
    }
}
