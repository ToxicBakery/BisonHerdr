package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.ClientShellEndpointRequest
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.MoshBootstrapConfig
import java.security.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * End-to-End connection lifecycle and transport path tests for `:platform:android:ssh`.
 *
 * Covers Paths 1A..6C across [SshTransportManager], [SimulatedSshBackend], [MoshUdpEngine], [ProxyJumpTunnel],
 * [AndroidKeystoreKeyManager], [SshStateManager], [EndpointSnapshotBus],
 * [PaneSurfaceBus], and [SemanticNotificationBus].
 *
 * This version has no Mosh transport (issue #22). Each path thus connects over the SSH session backbone, and the
 * Mosh engine starts no session.
 *
 * [SimulatedSshBackend] is in the `src/debug` source set, so these tests are in `src/testDebug`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPathsE2ETest {

    private class Harness(
        keystoreKeyManager: AndroidKeystoreKeyManager = AndroidKeystoreKeyManager(allowSoftwareKeys = true),
        promptScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) {
        val debugLog = SshDebugLog()
        val stateManager = SshStateManager()
        val snapshotBus = EndpointSnapshotBus()
        val surfaceBus = PaneSurfaceBus()
        val notificationBus = SemanticNotificationBus()
        val moshEngine = MoshUdpEngine(
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
        val backend = SimulatedSshBackend(
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            endpointSnapshotBus = snapshotBus,
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = notificationBus,
            keystoreKeyManager = keystoreKeyManager,
        )
        val transport = SshTransportManager(
            backend = backend,
            moshUdpEngine = moshEngine,
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
            promptScope = promptScope,
        )
    }

    /** Returns the lane that the target design gives [request] while the surface lease is on. */
    private fun expectedLane(request: ClientShellEndpointRequest): String =
        if (MoshBootstrapConfig.isClientShellSupportedMethod(request.method)) {
            SimulatedSshBackend.CLIENT_BRIDGE_LANE
        } else {
            SimulatedSshBackend.API_BRIDGE_LANE
        }

    /** Sends [request] to [machineId] and asserts that the response names the lane of the target design. */
    private suspend fun Harness.assertSentOnSshEndpointLane(machineId: String, request: ClientShellEndpointRequest) {
        val response = transport.sendEndpointCommand(machineId, request)
        assertTrue(response.contains(expectedLane(request)), "Wrong lane for ${request.method}: $response")
    }

    @Test
    fun path1A_autoPreferMosh_hostWithMoshServer_connectsOverTheSshEndpoint_andRoutesFocusRpcs() = runTest {
        val h = Harness()
        val observedStages = mutableListOf<ConnectionProgress>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            h.stateManager.connectionProgress.collect { map ->
                val stage = map["workbox-1a"]
                if (stage != null && observedStages.lastOrNull() != stage) {
                    observedStages.add(stage)
                }
            }
        }

        val machine = HostMachine(
            id = "workbox-1a",
            name = "Workbox 1A",
            hostname = "100.64.0.12",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
            moshUdpPortRange = "60010:60020",
        )
        assertTrue(h.transport.connect(machine))
        collectorJob.cancel()

        // 1. Verify full 4-stage progression -> Connected
        assertEquals(
            listOf(
                ConnectionProgress.AuthenticatingSsh,
                ConnectionProgress.BootstrappingMoshOrBridge,
                ConnectionProgress.VerifyingUdpOrHello,
                ConnectionProgress.SyncingHerdrSnapshot,
                ConnectionProgress.Connected,
            ),
            observedStages,
        )

        // 2. The host has mosh-server, but this version connects over the SSH endpoint and tells why.
        val backbone = assertIs<ActiveBackbone.SshEndpoint>(h.stateManager.activeTransportBackbone.value["workbox-1a"])
        assertEquals(SimulatedSshBackend.SIMULATED_RTT_MS, backbone.rttMs)
        assertEquals(MoshSessionEngine.MOSH_SERVER_FOUND, backbone.fallbackReason)
        assertFalse(h.moshEngine.isSessionActive("workbox-1a"))

        val initialSnapshot = assertNotNull(h.snapshotBus.snapshotFor("workbox-1a"))
        assertEquals("w1", initialSnapshot.focusedWorkspaceId)
        assertEquals("w1:t1", initialSnapshot.focusedTabId)
        assertEquals("w1:p1", initialSnapshot.focusedPaneId)

        val initialSurface = assertNotNull(h.surfaceBus.surfaceFor("workbox-1a"))
        assertEquals(2, initialSurface.panes.size)

        // 3. Focus RPCs get the lanes of the target design and update the snapshot and the surface.
        h.assertSentOnSshEndpointLane("workbox-1a", HerdrRpcBuilder.workspaceFocus("w2"))
        assertEquals("w2", h.snapshotBus.snapshotFor("workbox-1a")?.focusedWorkspaceId)

        h.assertSentOnSshEndpointLane("workbox-1a", HerdrRpcBuilder.tabFocus("w1:t2"))
        assertEquals("w1:t2", h.snapshotBus.snapshotFor("workbox-1a")?.focusedTabId)

        h.assertSentOnSshEndpointLane("workbox-1a", HerdrRpcBuilder.paneFocus("w1:p2"))
        assertEquals("w1:p2", h.snapshotBus.snapshotFor("workbox-1a")?.focusedPaneId)
        val surfaceAfterPaneFocus = assertNotNull(h.surfaceBus.surfaceFor("workbox-1a"))
        assertTrue(surfaceAfterPaneFocus.panes.first { it.paneId == "w1:p2" }.focused)

        h.assertSentOnSshEndpointLane("workbox-1a", HerdrRpcBuilder.agentFocus(paneId = "w1:p1", name = "claude"))
        assertEquals("w1:p1", h.snapshotBus.snapshotFor("workbox-1a")?.focusedPaneId)
        val surfaceAfterAgentFocus = assertNotNull(h.surfaceBus.surfaceFor("workbox-1a"))
        assertTrue(surfaceAfterAgentFocus.panes.first { it.paneId == "w1:p1" }.focused)
    }

    @Test
    fun path1B_autoPreferMosh_moshServerMissing_connectsOverTheSshEndpointWithNoReason() = runTest {
        val h = Harness()
        h.backend.moshServerProbe = { false }

        val machine = HostMachine(
            id = "host-1b",
            name = "Host 1B",
            hostname = "10.0.0.15",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
        )

        assertTrue(h.transport.connect(machine))
        assertEquals(ConnectionProgress.Connected, h.stateManager.connectionProgress.value["host-1b"])

        val backbone = assertIs<ActiveBackbone.SshEndpoint>(h.stateManager.activeTransportBackbone.value["host-1b"])
        assertNull(backbone.fallbackReason)

        val logs = h.debugLog.entries.value.filter { it.machineId == "host-1b" }
        assertTrue(logs.none { it.message == MoshSessionEngine.MOSH_SERVER_FOUND })
        assertTrue(logs.any { it.tag == SimulatedSshBackend.SIMULATED_HOST_LOG_TAG })
        assertNotNull(h.snapshotBus.snapshotFor("host-1b"))
        assertNotNull(h.surfaceBus.surfaceFor("host-1b"))
    }

    @Test
    fun path2_moshOnly_connectsOverTheSshEndpoint_becauseThisVersionHasNoMosh() = runTest {
        // Path 2A: The host has mosh-server. The backbone tells that Mosh is not available.
        val hWithServer = Harness()
        val moshOnlyHost = HostMachine(
            id = "mosh-2a",
            name = "Mosh 2A",
            hostname = "100.64.0.21",
            username = "ian",
            transportMode = TransportMode.MoshOnly,
            moshUdpPortRange = "60050:60060",
        )
        assertTrue(hWithServer.transport.connect(moshOnlyHost))
        val withServer = assertIs<ActiveBackbone.SshEndpoint>(
            hWithServer.stateManager.activeTransportBackbone.value["mosh-2a"],
        )
        assertEquals(MoshSessionEngine.MOSH_SERVER_FOUND, withServer.fallbackReason)
        assertFalse(hWithServer.moshEngine.isSessionActive("mosh-2a"))

        // Path 2B: The host has no mosh-server. The machine still connects over the SSH endpoint, and the backbone
        // tells that Mosh is not available, because the machine asks for Mosh only.
        val hMissing = Harness()
        hMissing.backend.moshServerProbe = { false }
        val missingHost = moshOnlyHost.copy(id = "mosh-2b-missing")
        assertTrue(hMissing.transport.connect(missingHost))
        assertEquals(ConnectionProgress.Connected, hMissing.stateManager.connectionProgress.value["mosh-2b-missing"])
        val missing = assertIs<ActiveBackbone.SshEndpoint>(
            hMissing.stateManager.activeTransportBackbone.value["mosh-2b-missing"],
        )
        assertEquals(MoshSessionEngine.NOT_AVAILABLE, missing.fallbackReason)
        assertTrue(
            hMissing.debugLog.entries.value.any {
                it.machineId == "mosh-2b-missing" && it.message == MoshSessionEngine.NOT_AVAILABLE
            },
        )
    }

    @Test
    fun path3_sshEndpointOnly_skipsMoshProbeAndConnectsDirectlyViaRemoteClientBridge() = runTest {
        val h = Harness()
        var moshServerProbed = false
        h.backend.moshServerProbe = {
            moshServerProbed = true
            true
        }

        val machine = HostMachine(
            id = "ssh-only-3",
            name = "SSH Endpoint Host",
            hostname = "10.0.10.99",
            username = "ian",
            transportMode = TransportMode.SshEndpointOnly,
        )

        assertTrue(h.transport.connect(machine))
        assertFalse(moshServerProbed, "SshEndpointOnly must skip mosh-server probing completely")
        assertFalse(h.moshEngine.isSessionActive("ssh-only-3"))

        val backbone = assertIs<ActiveBackbone.SshEndpoint>(h.stateManager.activeTransportBackbone.value["ssh-only-3"])
        assertNull(backbone.fallbackReason)
        assertNotNull(h.snapshotBus.snapshotFor("ssh-only-3"))
        assertNotNull(h.surfaceBus.surfaceFor("ssh-only-3"))

        val logs = h.debugLog.entries.value.filter { it.machineId == "ssh-only-3" }
        assertTrue(logs.none { it.message == MoshSessionEngine.MOSH_SERVER_FOUND })
        assertTrue(logs.any { it.tag == SimulatedSshBackend.SIMULATED_HOST_LOG_TAG })
    }

    @Test
    fun path4A_4B_sshAuthMethodVariants_keystore_andPassword() = runTest {
        // Path 4A: KeystoreKey across Ed25519, Rsa4096, EcdsaP256
        for (algorithm in listOf(KeyAlgorithm.Ed25519, KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256)) {
            val h = Harness()
            val machineId = "auth-keystore-${algorithm.name.lowercase()}"
            val machine = HostMachine(
                id = machineId,
                name = "Keystore ${algorithm.name}",
                hostname = "10.1.0.10",
                username = "ian",
                authMethod = SshAuthMethod.KeystoreKey(
                    keyAlias = "alias_${algorithm.name.lowercase()}",
                    algorithm = algorithm,
                    requireBiometric = false,
                ),
            )
            assertTrue(h.transport.connect(machine), "Expected KeystoreKey($algorithm) to succeed")
            assertEquals(ConnectionProgress.Connected, h.stateManager.connectionProgress.value[machineId])
        }

        // Path 4B: Password (valid succeeds; blank or invalid fails with ConnectionProgress.Failed)
        val hPwValid = Harness()
        val validPwHost = HostMachine(
            id = "auth-pw-valid",
            name = "Valid Password Host",
            hostname = "10.1.0.20",
            username = "ian",
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = "keystore:pw_ref_workbox"),
        )
        assertTrue(hPwValid.transport.connect(validPwHost))
        assertEquals(ConnectionProgress.Connected, hPwValid.stateManager.connectionProgress.value["auth-pw-valid"])

        val hPwBlank = Harness()
        val blankPwHost = validPwHost.copy(
            id = "auth-pw-blank",
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = "   "),
        )
        assertFalse(hPwBlank.transport.connect(blankPwHost))
        assertEquals(ConnectionProgress.Failed, hPwBlank.stateManager.connectionProgress.value["auth-pw-blank"])

        val hPwInvalid = Harness()
        val invalidPwHost = validPwHost.copy(
            id = "auth-pw-invalid",
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = "invalid:expired_token"),
        )
        assertFalse(hPwInvalid.transport.connect(invalidPwHost))
        assertEquals(ConnectionProgress.Failed, hPwInvalid.stateManager.connectionProgress.value["auth-pw-invalid"])
        assertTrue(
            hPwInvalid.debugLog.entries.value.any {
                it.isError && it.tag == "SSHAuth" && it.message.contains("failed: AuthFailed")
            },
        )
    }

    @Test
    fun path4C_keystoreKeyThatTheKeyStoreCannotMakeFailsTheLogin() = runTest {
        val keystoreError = ProviderException("Keystore operation failed")
        val h = Harness(
            keystoreKeyManager = AndroidKeystoreKeyManager(
                allowSoftwareKeys = false,
                keystoreKeyGenerator = { _, _, _ -> throw keystoreError },
            ),
        )
        val machine = HostMachine(
            id = "workbox-4c",
            name = "Workbox 4C",
            hostname = "100.64.0.12",
            username = "ian",
            authMethod = SshAuthMethod.KeystoreKey(keyAlias = "alias_4c", algorithm = KeyAlgorithm.EcdsaP256),
            transportMode = TransportMode.SshEndpointOnly,
        )

        val result = assertIs<SshAuthResult.Failed>(h.backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine)))
        assertSame(keystoreError, assertIs<KeyCreationException>(result.cause).cause)
        assertFalse(h.transport.connect(machine))
        assertEquals(ConnectionProgress.Failed, h.stateManager.connectionProgress.value["workbox-4c"])
    }

    @Test
    fun path5A_5B_5C_proxyJumpAndTailscale_direct_bastion_magicDns_andInvalidBastionFailure() = runTest {
        // Path 5A: Direct host connection (proxyJump = null)
        val hDirect = Harness()
        val directHost = HostMachine(
            id = "route-direct",
            name = "Direct Host",
            hostname = "192.168.10.25",
            port = 22,
            username = "ian",
            proxyJump = null,
        )
        val directRoute = hDirect.transport.proxyJumpTunnel.resolveRoute(directHost)
        assertFalse(directRoute.viaBastion)
        assertFalse(directRoute.isTailscaleMesh)
        assertEquals(directRoute.targetHost, directRoute.entryHost)
        assertEquals(directRoute.targetPort, directRoute.entryPort)
        assertTrue(hDirect.transport.connect(directHost))
        assertEquals(ConnectionProgress.Connected, hDirect.stateManager.connectionProgress.value["route-direct"])

        // Path 5B: Multi-hop SSH Bastion
        val hBastion = Harness()
        val bastionHost = HostMachine(
            id = "route-bastion",
            name = "Bastion Target",
            hostname = "10.20.30.40",
            port = 22,
            username = "ian",
            proxyJump = ProxyJumpConfig(
                bastionHost = "bastion.example.com",
                bastionPort = 2222,
                bastionUser = "jump",
            ),
        )
        val bastionRoute = hBastion.transport.proxyJumpTunnel.resolveRoute(bastionHost)
        assertTrue(bastionRoute.viaBastion)
        assertFalse(bastionRoute.isTailscaleMesh)
        assertEquals("bastion.example.com", bastionRoute.entryHost)
        assertEquals(2222, bastionRoute.entryPort)
        assertEquals("jump", bastionRoute.entryUsername)
        assertEquals("10.20.30.40", bastionRoute.targetHost)
        assertEquals(22, bastionRoute.targetPort)
        assertTrue(hBastion.transport.connect(bastionHost))
        assertEquals(ConnectionProgress.Connected, hBastion.stateManager.connectionProgress.value["route-bastion"])

        // Path 5C (part 1): A MagicDNS short name behind a CGNAT bastion. The bastion resolves the name.
        val hTailscale = Harness()
        val tailscaleHost = HostMachine(
            id = "route-tailscale",
            name = "Tailscale Box",
            hostname = "workbox",
            port = 22,
            username = "ian",
            proxyJump = ProxyJumpConfig(
                bastionHost = "100.64.0.1",
                bastionPort = 22,
                bastionUser = "ts",
            ),
        )
        val tsRoute = hTailscale.transport.proxyJumpTunnel.resolveRoute(tailscaleHost)
        assertTrue(tsRoute.viaBastion)
        assertTrue(tsRoute.isTailscaleMesh)
        assertEquals("100.64.0.1", tsRoute.entryHost)
        assertEquals("workbox", tsRoute.targetHost)
        assertTrue(hTailscale.transport.connect(tailscaleHost))
        assertEquals(ConnectionProgress.Connected, hTailscale.stateManager.connectionProgress.value["route-tailscale"])

        // Path 5C (part 2): A blank or unreachable bastion host gives ConnectionProgress.Failed
        val hBlankBastion = Harness()
        val blankBastionHost = bastionHost.copy(
            id = "route-invalid-blank",
            proxyJump = ProxyJumpConfig(
                bastionHost = "   ",
                bastionPort = 2222,
                bastionUser = "jump",
            ),
        )
        assertFalse(hBlankBastion.transport.connect(blankBastionHost))
        assertEquals(
            ConnectionProgress.Failed,
            hBlankBastion.stateManager.connectionProgress.value["route-invalid-blank"],
        )

        // The transport has no host name rule, so a `.invalid` bastion gets to the backend. The simulated backend
        // gives the network error of the bastion. With the old rule, the cause was NeedsUserCause.Other.
        val hUnreachableBastion = Harness()
        val probedRoutes = mutableListOf<ResolvedSshRoute>()
        hUnreachableBastion.backend.sshRouteProbe = { _, route ->
            probedRoutes += route
            route.entryHost != "dead-bastion.invalid"
        }
        val unreachableBastionHost = bastionHost.copy(
            id = "route-invalid-unreachable",
            proxyJump = ProxyJumpConfig(
                bastionHost = "dead-bastion.invalid",
                bastionPort = 2222,
                bastionUser = "jump",
            ),
        )
        assertFalse(hUnreachableBastion.transport.connect(unreachableBastionHost))
        assertEquals(
            ConnectionProgress.Failed,
            hUnreachableBastion.stateManager.connectionProgress.value["route-invalid-unreachable"],
        )
        assertEquals(setOf("dead-bastion.invalid"), probedRoutes.map { it.entryHost }.toSet())
        assertIs<NeedsUserCause.Unreachable>(
            hUnreachableBastion.stateManager.needsUser.value["route-invalid-unreachable"],
        )
    }

    @Test
    fun path6A_6B_6C_surfaceParking_resumePresentationFence_networkRoaming_andFifoOfflinePromptFlush() = runTest {
        // The flush after the network check runs in the background scope of the test.
        val h = Harness(promptScope = backgroundScope)
        val machineId = "roam-workbox"
        val machine = HostMachine(
            id = machineId,
            name = "Roaming Workbox",
            hostname = "100.64.0.88",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
        )
        assertTrue(h.transport.connect(machine))
        val surfaceBeforePark = assertNotNull(h.surfaceBus.surfaceFor(machineId))

        // Path 6A: setSurfaceLease(active = false) transitions to SurfaceParked while preserving frame
        h.transport.setSurfaceLease(machineId, active = false)
        assertEquals(RoamingStatus.SurfaceParked, h.stateManager.roamingState.value[machineId])
        val surfaceWhileParked = assertNotNull(h.surfaceBus.surfaceFor(machineId))
        assertEquals(surfaceBeforePark.bootId, surfaceWhileParked.bootId)
        assertEquals(surfaceBeforePark.panes.size, surfaceWhileParked.panes.size)

        // Path 6B: setSurfaceLease(active = true) publishes the frame with a new presentation epoch and restores
        // Healthy.
        h.transport.setSurfaceLease(machineId, active = true)
        assertEquals(RoamingStatus.Healthy, h.stateManager.roamingState.value[machineId])
        assertTrue(
            h.debugLog.entries.value.any {
                it.machineId == machineId &&
                    it.tag == "PresentationFence" &&
                    it.message.startsWith("Resumed: published the last frame again")
            },
        )

        // Path 6C: Queue prompts while RoamingReconnect, then call onNetworkAvailable() -> FIFO flush
        h.stateManager.setRoamingStatus(machineId, RoamingStatus.RoamingReconnect)
        val firstQueued = h.transport.submitAgentPrompt(
            machineId = machineId,
            paneId = "w1:p1",
            agentName = "claude",
            prompt = "First prompt while switching Wi-Fi to 5G",
        )
        val secondQueued = h.transport.submitAgentPrompt(
            machineId = machineId,
            paneId = "w1:p1",
            agentName = "claude",
            prompt = "Second prompt while switching Wi-Fi to 5G",
        )
        assertFalse(firstQueued, "Prompt submitted during RoamingReconnect must be queued")
        assertFalse(secondQueued, "Prompt submitted during RoamingReconnect must be queued")

        val queuedList = h.stateManager.queuedPromptsForMachine(machineId)
        assertEquals(2, queuedList.size)
        assertEquals("First prompt while switching Wi-Fi to 5G", queuedList[0].prompt)
        assertEquals("Second prompt while switching Wi-Fi to 5G", queuedList[1].prompt)

        val logCountBeforeRoam = h.debugLog.entries.value.size
        h.transport.onNetworkAvailable(machineId)
        runCurrent()

        assertEquals(RoamingStatus.Healthy, h.stateManager.roamingState.value[machineId])
        assertTrue(h.stateManager.queuedPromptsForMachine(machineId).isEmpty())

        // The SSH endpoint stays the backbone after the network change.
        assertIs<ActiveBackbone.SshEndpoint>(h.stateManager.activeTransportBackbone.value[machineId])
        assertFalse(h.moshEngine.isSessionActive(machineId))

        val rpcLogsAfterRoam = h.debugLog.entries.value
            .drop(logCountBeforeRoam)
            .filter { it.machineId == machineId && it.tag == "RPC" }
        assertEquals(2, rpcLogsAfterRoam.size)
        assertTrue(rpcLogsAfterRoam[0].message.contains("First prompt while switching Wi-Fi to 5G"))
        assertTrue(rpcLogsAfterRoam[1].message.contains("Second prompt while switching Wi-Fi to 5G"))
    }

    @Test
    fun sshTransportManagerConnectsEachSimulatedHostOverTheSshEndpoint() = runTest {
        val h = Harness()
        val stateManager = h.stateManager
        val transport = h.transport

        val workbox = HostMachine(
            id = "workbox",
            name = "workbox",
            hostname = "100.64.0.12",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
        )
        assertTrue(transport.connect(workbox))
        val workboxBackbone = assertIs<ActiveBackbone.SshEndpoint>(
            stateManager.activeTransportBackbone.value["workbox"],
        )
        assertEquals(MoshSessionEngine.MOSH_SERVER_FOUND, workboxBackbone.fallbackReason)

        // A focus RPC gets the lane of the target design.
        h.assertSentOnSshEndpointLane("workbox", HerdrRpcBuilder.workspaceFocus("w2"))

        // Verify background surface parking and wakeup presentation fence
        transport.setSurfaceLease("workbox", active = false)
        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value["workbox"])
        transport.setSurfaceLease("workbox", active = true)
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value["workbox"])

        // A host with no mosh-server gets no reason.
        h.backend.moshServerProbe = { false }
        val gpuRig = HostMachine(
            id = "gpu-rig",
            name = "gpu-rig",
            hostname = "100.64.0.25",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
        )
        assertTrue(transport.connect(gpuRig))
        val gpuRigBackbone = assertIs<ActiveBackbone.SshEndpoint>(stateManager.activeTransportBackbone.value["gpu-rig"])
        assertNull(gpuRigBackbone.fallbackReason)

        // Verify pane input and a network change on the SSH endpoint
        assertTrue(
            transport.sendPaneInput(
                machineId = "workbox",
                paneId = "w1:p1",
                event = ClientPaneInputEvent.TextCommit("echo ok"),
            )
        )
        transport.onNetworkAvailable("workbox")
        assertEquals(workboxBackbone, stateManager.activeTransportBackbone.value["workbox"])
    }
}
