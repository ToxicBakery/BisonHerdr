package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * Tests the parts of [SshTransportManager] that do not depend on the backend type.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshTransportManagerTest {

    private val debugLog = SshDebugLog()
    private val stateManager = SshStateManager()

    private val lanBox = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")
    private val gpuRig = HostMachine(id = "gpu-rig", name = "gpu-rig", hostname = "192.168.1.44", username = "ian")

    /** Returns a manager that sends the queued prompts of a network check in the background scope of the test. */
    private fun TestScope.manager(
        backend: SshBackend,
        timeSource: TimeSource = TimeSource.Monotonic,
        surfaceBus: PaneSurfaceBus = PaneSurfaceBus(),
    ): SshTransportManager {
        return SshTransportManager(
            backend = backend,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
            timeSource = timeSource,
            promptScope = backgroundScope,
        )
    }

    /** Connects each machine of [machines] with [manager], and then clears the calls of [backend]. */
    private suspend fun connectAll(
        manager: SshTransportManager,
        backend: RecordingSshBackend,
        vararg machines: HostMachine,
    ) {
        machines.forEach { assertTrue(manager.connect(it), debugLogDump()) }
        backend.calls.clear()
    }

    /** Submits a prompt to [lanBox]. Returns false when the manager put the prompt in the queue. */
    private suspend fun queuePrompt(manager: SshTransportManager): Boolean =
        manager.submitAgentPrompt(
            machineId = lanBox.id,
            paneId = "w1:p1",
            agentName = "claude",
            prompt = "Fix the test",
        )

    private fun debugLogDump(): String = debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    @Test
    fun anRpcWithNoResponseFromTheBackendGivesAnErrorResponse() = runTest {
        // The recording backend gives no response, as the real backend does after an error.
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox)

        val response = assertNotNull(manager.sendEndpointCommand(lanBox.id, HerdrRpcBuilder.workspaceFocus("w2")))

        assertEquals(listOf("sendEndpointCommand lan-box"), backend.calls)
        assertTrue(response.contains("\"error\""), response)
        assertTrue(response.contains(SshTransportManager.NO_RESPONSE_CODE), response)
        assertFalse(response.contains("\"ok\""), response)
        assertTrue(
            debugLog.entries.value.any { it.isError && it.message == "No response for workspace.focus" },
            debugLogDump(),
        )
    }

    @Test
    fun anRpcForAMachineWithNoProfileGivesAnErrorResponse() = runTest {
        val backend = RecordingSshBackend()

        val response = assertNotNull(manager(backend).sendEndpointCommand("unknown-box", HerdrRpcBuilder.paneFocus("w1:p1")))

        assertTrue(backend.calls.isEmpty(), "The backend got ${backend.calls}")
        assertTrue(response.contains(SshTransportManager.NO_RESPONSE_CODE), response)
    }

    @Test
    fun connectWithoutAProfileFailsAndDoesNotCallTheBackend() = runTest {
        val backend = RecordingSshBackend()

        assertFalse(manager(backend).connect("unknown-box"))

        assertTrue(backend.calls.isEmpty(), "The backend got ${backend.calls}")
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["unknown-box"])
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.machineId == "unknown-box" && it.message.contains("No machine profile")
            },
        )
    }

    @Test
    fun failedLoginLogsTheKindOfTheFailure() = runTest {
        val backend = RecordingSshBackend(
            authResult = SshAuthResult.NetworkError(IOException("Connection refused")),
        )
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertEquals(listOf("authenticate lan-box", "disconnect lan-box"), backend.calls)
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["lan-box"])
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.tag == "SSHAuth" && it.message.contains("NetworkError (Connection refused)")
            },
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" },
        )
    }

    @Test
    fun unknownHostKeyStopsTheConnectAndPutsAPrompt() = runTest {
        val presented = knownHost(fingerprint = "SHA256:new")
        val backend = RecordingSshBackend(authResult = SshAuthResult.HostKeyUnknown(presented))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")
        val manager = manager(backend)

        assertFalse(manager.connect(machine))

        assertEquals(listOf("authenticate lan-box", "disconnect lan-box"), backend.calls)
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["lan-box"])
        assertEquals(
            mapOf("lan-box" to HostKeyPrompt.Unknown(machineId = "lan-box", presented = presented)),
            stateManager.hostKeyPrompts.value,
        )

        // The user accepts the key in the UI and connects again. The new connect closes the prompt.
        backend.authResult = SshAuthResult.Success
        assertTrue(manager.connect(machine))
        assertNull(stateManager.hostKeyPrompts.value["lan-box"])
    }

    @Test
    fun changedHostKeyPutsAPromptWithBothKeys() = runTest {
        val known = knownHost(fingerprint = "SHA256:old")
        val presented = knownHost(fingerprint = "SHA256:new")
        val backend = RecordingSshBackend(authResult = SshAuthResult.HostKeyChanged(known = known, presented = presented))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertEquals(
            HostKeyPrompt.Changed(machineId = "lan-box", known = known, presented = presented),
            stateManager.hostKeyPrompts.value["lan-box"],
        )
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.message.contains("HostKeyChanged") && it.message.contains("SHA256:new")
            },
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" },
        )
    }

    @Test
    fun loginWithAMissingKeyLogsWhatTheUserMustDo() = runTest {
        val backend = RecordingSshBackend(authResult = SshAuthResult.KeyMissing("work_key"))
        val machine = HostMachine(id = "key-box", name = "key-box", hostname = "192.168.1.43", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["key-box"])
        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.tag == "SSHAuth" && it.message.contains(
                    "The key work_key does not exist. Open the machine settings and copy the public key.",
                )
            },
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" },
        )
    }

    @Test
    fun disconnectClosesTheHostKeyPrompt() = runTest {
        val backend = RecordingSshBackend(authResult = SshAuthResult.HostKeyUnknown(knownHost(fingerprint = "SHA256:new")))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")
        val manager = manager(backend)
        assertFalse(manager.connect(machine))

        manager.disconnect("lan-box")

        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
    }

    @Test
    fun otherLoginFailuresPutNoHostKeyPrompt() = runTest {
        val backend = RecordingSshBackend(authResult = SshAuthResult.AuthFailed("Auth fail"))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
    }

    @Test
    fun eachFailedLoginPutsItsCauseForTheUserAndAConnectRemovesIt() = runTest {
        val presented = knownHost(fingerprint = "SHA256:new")
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        val expected = listOf(
            SshAuthResult.AuthFailed("Auth fail") to NeedsUserCause.LoginRefused("Auth fail"),
            SshAuthResult.KeyMissing("work_key") to NeedsUserCause.KeyMissing("work_key"),
            SshAuthResult.Failed(MissingSecretException("lan-box")) to NeedsUserCause.PasswordMissing,
            SshAuthResult.Failed(IllegalStateException("Algorithm negotiation fail")) to
                NeedsUserCause.Other("Algorithm negotiation fail"),
            SshAuthResult.NetworkError(ConnectException("Connection refused")) to
                NeedsUserCause.Unreachable("Connection refused"),
            SshAuthResult.HostKeyUnknown(presented) to NeedsUserCause.HostKeyUnknown(presented.hostPort),
            SshAuthResult.HostKeyChanged(known = knownHost("SHA256:old"), presented = presented) to
                NeedsUserCause.HostKeyChanged(presented.hostPort),
            SshAuthResult.InputRequired("bastion.lan:22", timedOut = false) to
                NeedsUserCause.InputRequired("bastion.lan:22"),
            SshAuthResult.InputRequired("lan-box.lan:22", timedOut = true) to
                NeedsUserCause.InputTimedOut("lan-box.lan:22"),
            SshAuthResult.InputCancelled("lan-box.lan:22") to NeedsUserCause.InputCancelled("lan-box.lan:22"),
            SshAuthResult.Failed(UserInputException.of("lan-box.lan:22", KeyboardInteractiveOutcome.Cancelled)) to
                NeedsUserCause.InputCancelled("lan-box.lan:22"),
        )

        expected.forEach { (result, cause) ->
            backend.authResult = result
            assertFalse(manager.connect(lanBox))
            assertEquals(cause, stateManager.needsUser.value[lanBox.id], "The cause of $result")
            assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[lanBox.id])
        }

        backend.authResult = SshAuthResult.Success
        assertTrue(manager.connect(lanBox))
        assertNull(stateManager.needsUser.value[lanBox.id])
        assertNull(stateManager.hostKeyPrompts.value[lanBox.id])
    }

    @Test
    fun aConnectStepThatThrowsEndsAsFailedWithTheCause() = runTest {
        val backend = RecordingSshBackend(onOpenSurface = { throw IllegalStateException("The surface broke") })

        assertFalse(manager(backend).connect(lanBox))

        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[lanBox.id])
        assertEquals(NeedsUserCause.Other("The surface broke"), stateManager.needsUser.value[lanBox.id])
        assertFalse(stateManager.isMachineConnected(lanBox.id))
        assertEquals("disconnect lan-box", backend.calls.last())
    }

    @Test
    fun aTimeoutInsideAConnectStepIsAFailedConnect() = runTest {
        val backend = RecordingSshBackend(onOpenSurface = { withTimeout(1) { awaitCancellation() } })

        assertFalse(manager(backend).connect(lanBox))

        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[lanBox.id])
        assertIs<NeedsUserCause.Other>(stateManager.needsUser.value[lanBox.id])
    }

    @Test
    fun aCancelledConnectEndsAsFailedAndStaysCancelled() = runTest {
        val atStep = CompletableDeferred<Unit>()
        val backend = RecordingSshBackend(
            onOpenSurface = {
                atStep.complete(Unit)
                awaitCancellation()
            },
        )
        val manager = manager(backend)
        val job = launch { manager.connect(lanBox) }
        atStep.await()
        assertEquals(ConnectionProgress.SyncingHerdrSnapshot, stateManager.connectionProgress.value[lanBox.id])

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[lanBox.id])
        assertNull(stateManager.needsUser.value[lanBox.id])
    }

    @Test
    fun aNetworkCheckRunsOneTimeEachSecondForEachMachine() = runTest {
        val timeSource = TestTimeSource()
        val backend = RecordingSshBackend()
        val manager = manager(backend, timeSource)
        connectAll(manager, backend, lanBox, gpuRig)

        // onAvailable and onLinkPropertiesChanged come together after a network change.
        manager.onNetworkAvailable()
        manager.onNetworkAvailable()
        timeSource += 999.milliseconds
        manager.onNetworkAvailable()
        assertEquals(listOf("checkLiveness lan-box", "checkLiveness gpu-rig"), backend.calls)

        timeSource += 1.milliseconds
        manager.onNetworkAvailable(gpuRig.id)
        manager.onNetworkAvailable()
        assertEquals(
            listOf("checkLiveness lan-box", "checkLiveness gpu-rig", "checkLiveness gpu-rig", "checkLiveness lan-box"),
            backend.calls,
        )
    }

    @Test
    fun theLivenessChecksOfAllMachinesRunAtTheSameTime() = runTest {
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox, gpuRig)
        backend.livenessDelay = SshTransportManager.LIVENESS_TIMEOUT
        val start = testScheduler.currentTime

        manager.onNetworkAvailable()

        assertEquals(listOf("checkLiveness lan-box", "checkLiveness gpu-rig"), backend.calls.take(2))
        // Two checks one after the other would take 6 s.
        assertEquals(SshTransportManager.LIVENESS_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime - start)
    }

    @Test
    fun aFailedLivenessCheckReconnectsAtOnce() = runTest {
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox)
        backend.liveness = LivenessResult.Dead(sessionId = 7L)

        manager.onNetworkAvailable()

        // The manager gives the session of the check, so that the backend keeps a newer session.
        assertEquals(listOf("checkLiveness lan-box", "reconnectNow lan-box 7"), backend.calls)
        assertEquals(RoamingStatus.RoamingReconnect, stateManager.roamingState.value[lanBox.id])
    }

    @Test
    fun aPassedLivenessCheckRestoresTheStatusAndSendsTheQueuedPrompts() = runTest {
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox)
        stateManager.setRoamingStatus(lanBox.id, RoamingStatus.RoamingReconnect)
        assertFalse(queuePrompt(manager), "The prompt was not queued")

        manager.onNetworkAvailable(lanBox.id)
        runCurrent()

        assertEquals(listOf("checkLiveness lan-box", "sendEndpointCommand lan-box"), backend.calls)
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[lanBox.id])
        assertTrue(stateManager.queuedPromptsForMachine(lanBox.id).isEmpty())
    }

    @Test
    fun aSlowPromptAfterACheckDoesNotStopTheNextNetworkEvent() = runTest {
        val promptSent = CompletableDeferred<Unit>()
        val timeSource = TestTimeSource()
        val backend = RecordingSshBackend(onSendEndpointCommand = { promptSent.await() })
        val manager = manager(backend, timeSource)
        connectAll(manager, backend, lanBox)
        stateManager.setRoamingStatus(lanBox.id, RoamingStatus.RoamingReconnect)
        assertFalse(queuePrompt(manager), "The prompt was not queued")

        // The check passes, and the prompt waits for a slow session.
        manager.onNetworkAvailable()
        runCurrent()
        assertEquals(listOf("checkLiveness lan-box", "sendEndpointCommand lan-box"), backend.calls)

        // The network goes away while the prompt waits. The event does not wait for the prompt.
        manager.onNetworkLost()
        assertEquals("closeSockets lan-box", backend.calls.last())

        // A second check comes. The prompt is already out of the queue, so it goes out one time.
        stateManager.setRoamingStatus(lanBox.id, RoamingStatus.Healthy)
        timeSource += SshTransportManager.NETWORK_CHECK_INTERVAL
        manager.onNetworkAvailable()
        promptSent.complete(Unit)
        runCurrent()

        assertEquals(1, backend.calls.count { it == "sendEndpointCommand lan-box" }, backend.calls.toString())
        assertTrue(stateManager.queuedPromptsForMachine(lanBox.id).isEmpty())
    }

    @Test
    fun aLostNetworkClosesTheSocketsAndTheNextCheckRunsAtOnce() = runTest {
        val timeSource = TestTimeSource()
        val backend = RecordingSshBackend()
        val manager = manager(backend, timeSource)
        connectAll(manager, backend, lanBox)
        manager.onNetworkAvailable()
        backend.calls.clear()

        manager.onNetworkLost()

        assertEquals(listOf("closeSockets lan-box"), backend.calls)
        assertEquals(RoamingStatus.RoamingReconnect, stateManager.roamingState.value[lanBox.id])
        assertTrue(stateManager.isMachineConnected(lanBox.id), "A lost network must not disconnect the machine")

        // The new network comes in the same second. Its check runs, and the closed session gets a reconnect now.
        backend.liveness = LivenessResult.Dead(sessionId = null)
        manager.onNetworkAvailable()
        assertEquals(
            listOf("closeSockets lan-box", "checkLiveness lan-box", "reconnectNow lan-box null"),
            backend.calls,
        )
    }

    @Test
    fun aReconnectSetsTheLeaseAgainReadsTheSurfaceAndSendsTheQueuedPrompts() = runTest {
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox)
        val events = assertNotNull(backend.sessionEvents)

        events.onReconnecting(lanBox.id, IOException("Connection reset"))
        assertEquals(RoamingStatus.RoamingReconnect, stateManager.roamingState.value[lanBox.id])
        assertFalse(queuePrompt(manager), "The prompt was not queued")

        events.onReconnected(lanBox.id)

        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[lanBox.id])
        assertEquals(listOf("refreshSurface lan-box", "sendEndpointCommand lan-box"), backend.calls)
        assertTrue(stateManager.queuedPromptsForMachine(lanBox.id).isEmpty())
        assertTrue(
            debugLog.entries.value.any { it.message.startsWith("Surface lease on: the poll reads the panes.") },
            debugLogDump(),
        )
    }

    @Test
    fun aSurfaceLeasePublishesTheFrameInTheFrameLockOfTheBackend() = runTest {
        val backend = RecordingSshBackend()
        val surfaceBus = PaneSurfaceBus()
        val manager = manager(backend, surfaceBus = surfaceBus)
        connectAll(manager, backend, lanBox)
        surfaceBus.publish(lanBox.id, PaneSurfaceFrame(frame = FrameData(width = 80u, height = 24u)))
        val epochsAtLockEnd = mutableListOf<ULong?>()
        backend.onFrameLockEnd = { machineId -> epochsAtLockEnd += surfaceBus.surfaceFor(machineId)?.presentationEpoch }

        manager.setSurfaceLease(lanBox.id, active = true)

        val epoch = assertNotNull(assertNotNull(surfaceBus.surfaceFor(lanBox.id)).presentationEpoch)
        assertEquals(listOf<ULong?>(epoch), epochsAtLockEnd, "The manager must publish the frame in the frame lock")
    }

    @Test
    fun aReconnectWhileTheSurfaceIsParkedKeepsTheParkedStatus() = runTest {
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox)
        manager.setSurfaceLease(lanBox.id, active = false)
        val events = assertNotNull(backend.sessionEvents)

        events.onReconnecting(lanBox.id, null)
        events.onReconnected(lanBox.id)

        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value[lanBox.id])
    }

    @Test
    fun aLoopThatNeedsTheUserStopsTheMachineAndPutsTheCauseAndThePrompt() = runTest {
        val backend = RecordingSshBackend()
        val manager = manager(backend)
        connectAll(manager, backend, lanBox)
        val known = knownHost(fingerprint = "SHA256:old")
        val presented = knownHost(fingerprint = "SHA256:new")

        assertNotNull(backend.sessionEvents).onNeedsUser(
            lanBox.id,
            SshAuthResult.HostKeyChanged(known = known, presented = presented),
        )

        assertFalse(stateManager.isMachineConnected(lanBox.id))
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[lanBox.id])
        assertEquals(NeedsUserCause.HostKeyChanged(presented.hostPort), stateManager.needsUser.value[lanBox.id])
        assertEquals(
            HostKeyPrompt.Changed(machineId = lanBox.id, known = known, presented = presented),
            stateManager.hostKeyPrompts.value[lanBox.id],
        )
        assertEquals(listOf("disconnect lan-box"), backend.calls)

        // Cancel in the UI disconnects the machine and removes the cause.
        manager.disconnect(lanBox.id)
        assertNull(stateManager.needsUser.value[lanBox.id])
    }

    private fun knownHost(fingerprint: String): KnownHost = KnownHost(
        host = "192.168.1.42",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprint = fingerprint,
        publicKey = "AAAA",
    )
}
