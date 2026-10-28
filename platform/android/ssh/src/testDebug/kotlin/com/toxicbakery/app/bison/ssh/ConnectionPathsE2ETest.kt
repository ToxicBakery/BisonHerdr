package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * End-to-End connection lifecycle and transport path tests for `:platform:android:ssh`.
 *
 * Covers Paths 1A..6C across [SshTransportManager], [SimulatedSshBackend], [MoshUdpEngine], [ProxyJumpTunnel],
 * [AndroidKeystoreKeyManager], [YubiKeyFido2Manager], [SshStateManager], [EndpointSnapshotBus],
 * [PaneSurfaceBus], and [SemanticNotificationBus].
 *
 * [SimulatedSshBackend] is in the `src/debug` source set, so these tests are in `src/testDebug`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPathsE2ETest {

    private class Harness {
        val debugLog = SshDebugLog()
        val stateManager = SshStateManager()
        val snapshotBus = EndpointSnapshotBus()
        val surfaceBus = PaneSurfaceBus()
        val notificationBus = SemanticNotificationBus()
        val moshEngine = MoshUdpEngine(
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
        val yubiKey = YubiKeyFido2Manager(
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
        )
        val backend = SimulatedSshBackend(
            moshUdpEngine = moshEngine,
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            endpointSnapshotBus = snapshotBus,
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = notificationBus,
            keystoreKeyManager = AndroidKeystoreKeyManager(),
            yubiKeyFido2Manager = yubiKey,
        )
        val transport = SshTransportManager(
            backend = backend,
            moshUdpEngine = moshEngine,
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
    }

    @Test
    fun path1A_autoPreferMosh_moshUdpSuccess_fourStageProgression_bspSplits_andFocusRpcs() = runTest {
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

        // 2. Verify ActiveBackbone.MoshUdp, ClientShellSnapshot, and synthesized PaneSurfaceFrame with BSP splits
        val backbone = assertIs<ActiveBackbone.MoshUdp>(h.stateManager.activeTransportBackbone.value["workbox-1a"])
        assertEquals(60010, backbone.udpPort)
        assertTrue(backbone.localEchoEnabled)
        assertEquals(0, backbone.roamedCount)

        val initialSnapshot = assertNotNull(h.snapshotBus.snapshotFor("workbox-1a"))
        assertEquals("w1", initialSnapshot.focusedWorkspaceId)
        assertEquals("w1:t1", initialSnapshot.focusedTabId)
        assertEquals("w1:p1", initialSnapshot.focusedPaneId)

        val initialSurface = assertNotNull(h.surfaceBus.surfaceFor("workbox-1a"))
        assertEquals(2, initialSurface.panes.size)
        assertTrue(initialSurface.splits.isNotEmpty(), "Expected BSP split derived from PaneLayoutSnapshot")
        assertEquals("s0:root", initialSurface.splits.first().id)

        // 3. Verify focus RPCs in Mosh mode route through herdr.sock (remote-api-bridge) and update snapshot/surface
        val wsResp = h.transport.sendEndpointCommand("workbox-1a", HerdrRpcBuilder.workspaceFocus("w2"))
        assertTrue(wsResp?.contains("herdr remote-api-bridge (herdr.sock)") == true)
        assertEquals("w2", h.snapshotBus.snapshotFor("workbox-1a")?.focusedWorkspaceId)

        val tabResp = h.transport.sendEndpointCommand("workbox-1a", HerdrRpcBuilder.tabFocus("w1:t2"))
        assertTrue(tabResp?.contains("herdr remote-api-bridge (herdr.sock)") == true)
        assertEquals("w1:t2", h.snapshotBus.snapshotFor("workbox-1a")?.focusedTabId)

        val paneResp = h.transport.sendEndpointCommand("workbox-1a", HerdrRpcBuilder.paneFocus("w1:p2"))
        assertTrue(paneResp?.contains("herdr remote-api-bridge (herdr.sock)") == true)
        assertEquals("w1:p2", h.snapshotBus.snapshotFor("workbox-1a")?.focusedPaneId)
        val surfaceAfterPaneFocus = assertNotNull(h.surfaceBus.surfaceFor("workbox-1a"))
        assertTrue(surfaceAfterPaneFocus.panes.first { it.paneId == "w1:p2" }.focused)

        val agentResp = h.transport.sendEndpointCommand(
            "workbox-1a",
            HerdrRpcBuilder.agentFocus(paneId = "w1:p1", name = "claude"),
        )
        assertTrue(agentResp?.contains("herdr remote-api-bridge (herdr.sock)") == true)
        assertEquals("w1:p1", h.snapshotBus.snapshotFor("workbox-1a")?.focusedPaneId)
        val surfaceAfterAgentFocus = assertNotNull(h.surfaceBus.surfaceFor("workbox-1a"))
        assertTrue(surfaceAfterAgentFocus.panes.first { it.paneId == "w1:p1" }.focused)
    }

    @Test
    fun path1B_autoPreferMosh_moshServerMissing_fallsBackToSshEndpoint() = runTest {
        val h = Harness()
        h.backend.moshBootstrapBannerProvider = { "sh: 1: mosh-server: not found" }

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
        val reason = assertNotNull(backbone.fallbackReason)
        assertTrue(reason.contains("mosh-server", ignoreCase = true))

        val logs = h.debugLog.entries.value.filter { it.machineId == "host-1b" }
        assertTrue(
            logs.any {
                it.tag == "EndpointGen1" &&
                    it.message.contains("herdr remote-client-bridge --idle-timeout-v1") &&
                    it.message.contains("surfaceActive=true")
            },
        )
        assertNotNull(h.snapshotBus.snapshotFor("host-1b"))
        assertNotNull(h.surfaceBus.surfaceFor("host-1b"))
    }

    @Test
    fun path1C_autoPreferMosh_udpFirewallBlockedAndHandshakeTimeout_fallsBackToSshEndpoint() = runTest {
        val h = Harness()
        h.backend.moshBootstrapBannerProvider = { "MOSH CONNECT 60042 4k9X2vL8pQ1mN7rT3wY6zA" }
        h.backend.moshUdpReachabilityProbe = { _, _ ->
            // Simulate UDP packets dropped by firewall exceeding the 1,500ms handshake timeout
            delay(SshTransportManager.MOSH_UDP_HANDSHAKE_TIMEOUT_MS + 500L)
            true
        }

        val machine = HostMachine(
            id = "host-1c",
            name = "Host 1C",
            hostname = "203.0.113.50",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
        )

        assertTrue(h.transport.connect(machine))
        assertEquals(ConnectionProgress.Connected, h.stateManager.connectionProgress.value["host-1c"])
        assertFalse(h.moshEngine.isSessionActive("host-1c"))

        val backbone = assertIs<ActiveBackbone.SshEndpoint>(h.stateManager.activeTransportBackbone.value["host-1c"])
        val fallbackReason = assertNotNull(backbone.fallbackReason)
        assertTrue(
            fallbackReason.contains("UDP", ignoreCase = true) &&
                (fallbackReason.contains("unreachable", ignoreCase = true) ||
                    fallbackReason.contains("blocked", ignoreCase = true)),
        )
    }

    @Test
    fun path2A_and_2B_moshOnly_succeedsWithUdp_andFailsWithoutFallingBackWhenMissingOrBlocked() = runTest {
        // Path 2A: MoshOnly succeeds when mosh-server + UDP succeed
        val hSuccess = Harness()
        val moshSuccessHost = HostMachine(
            id = "mosh-2a",
            name = "Mosh 2A",
            hostname = "100.64.0.21",
            username = "ian",
            transportMode = TransportMode.MoshOnly,
            moshUdpPortRange = "60050:60060",
        )
        assertTrue(hSuccess.transport.connect(moshSuccessHost))
        val moshBackbone = assertIs<ActiveBackbone.MoshUdp>(
            hSuccess.stateManager.activeTransportBackbone.value["mosh-2a"],
        )
        assertEquals(60050, moshBackbone.udpPort)

        // Path 2B (part 1): MoshOnly fails when mosh-server is missing (no SSH TCP fallback)
        val hMissing = Harness()
        hMissing.backend.moshBootstrapBannerProvider = { "command not found: mosh-server" }
        val moshMissingHost = moshSuccessHost.copy(id = "mosh-2b-missing")
        assertFalse(hMissing.transport.connect(moshMissingHost))
        assertEquals(ConnectionProgress.Failed, hMissing.stateManager.connectionProgress.value["mosh-2b-missing"])
        assertNull(hMissing.stateManager.activeTransportBackbone.value["mosh-2b-missing"])
        assertFalse(hMissing.stateManager.isMachineConnected("mosh-2b-missing"))

        // Path 2B (part 2): MoshOnly fails when UDP handshake is blocked/times out (no SSH TCP fallback)
        val hBlocked = Harness()
        hBlocked.backend.moshBootstrapBannerProvider = { "MOSH CONNECT 60055 4k9X2vL8pQ1mN7rT3wY6zA" }
        hBlocked.backend.moshUdpReachabilityProbe = { _, _ -> false }
        val moshBlockedHost = moshSuccessHost.copy(id = "mosh-2b-blocked")
        assertFalse(hBlocked.transport.connect(moshBlockedHost))
        assertEquals(ConnectionProgress.Failed, hBlocked.stateManager.connectionProgress.value["mosh-2b-blocked"])
        assertNull(hBlocked.stateManager.activeTransportBackbone.value["mosh-2b-blocked"])
        assertFalse(hBlocked.stateManager.isMachineConnected("mosh-2b-blocked"))
    }

    @Test
    fun path3_sshEndpointOnly_skipsMoshProbeAndConnectsDirectlyViaRemoteClientBridge() = runTest {
        val h = Harness()
        var moshBannerProbed = false
        h.backend.moshBootstrapBannerProvider = {
            moshBannerProbed = true
            "MOSH CONNECT 60000 4k9X2vL8pQ1mN7rT3wY6zA"
        }

        val machine = HostMachine(
            id = "ssh-only-3",
            name = "SSH Endpoint Host",
            hostname = "10.0.10.99",
            username = "ian",
            transportMode = TransportMode.SshEndpointOnly,
        )

        assertTrue(h.transport.connect(machine))
        assertFalse(moshBannerProbed, "SshEndpointOnly must skip mosh-server probing completely")
        assertFalse(h.moshEngine.isSessionActive("ssh-only-3"))

        val backbone = assertIs<ActiveBackbone.SshEndpoint>(h.stateManager.activeTransportBackbone.value["ssh-only-3"])
        assertNull(backbone.fallbackReason)
        assertNotNull(h.snapshotBus.snapshotFor("ssh-only-3"))
        assertNotNull(h.surfaceBus.surfaceFor("ssh-only-3"))

        val logs = h.debugLog.entries.value.filter { it.machineId == "ssh-only-3" }
        assertTrue(logs.none { it.tag == "MoshBootstrap" })
        assertTrue(
            logs.any {
                it.tag == "EndpointGen1" &&
                    it.message.contains("herdr remote-client-bridge --idle-timeout-v1") &&
                    it.message.contains("surfaceActive=true")
            },
        )
    }

    @Test
    fun path4A_4B_4C_allSshAuthMethodVariants_keystore_password_andHardwareSecurityKeys() = runTest {
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

        // Path 4C: HardwareSecurityKey across SkSshEd25519, SkEcdsaSha2Nistp256, PivSmartCard and all transports
        val keyTypes = listOf(
            HardwareKeyType.SkSshEd25519,
            HardwareKeyType.SkEcdsaSha2Nistp256,
            HardwareKeyType.PivSmartCard,
        )
        val transports = listOf(
            HardwareKeyTransport.UsbAndNfc,
            HardwareKeyTransport.NfcOnly,
            HardwareKeyTransport.UsbOnly,
        )

        for (keyType in keyTypes) {
            for (hwTransport in transports) {
                val h = Harness()
                val machineId = "auth-hw-${keyType.name}-${hwTransport.name}"
                var capturedPromptDuringSign: SshRequest.PromptHardwareKeyTouch? = null
                h.yubiKey.onTouchPromptActive = { prompt ->
                    // Verify prompt is actively published on SshStateManager while waiting for touch
                    capturedPromptDuringSign = h.stateManager.hardwareKeyTouchPrompt.value
                    assertEquals(prompt, capturedPromptDuringSign)
                }

                val machine = HostMachine(
                    id = machineId,
                    name = "YubiKey $keyType $hwTransport",
                    hostname = "10.1.0.30",
                    username = "ian",
                    authMethod = SshAuthMethod.HardwareSecurityKey(
                        keyType = keyType,
                        transport = hwTransport,
                        applicationId = "ssh:bison-e2e",
                    ),
                )
                assertTrue(h.transport.connect(machine))
                assertNotNull(capturedPromptDuringSign, "Touch prompt must be emitted during $keyType/$hwTransport auth")
                assertEquals(machineId, capturedPromptDuringSign?.machineId)
                assertEquals(keyType.opensshKeyType, capturedPromptDuringSign?.keyType)
                assertNull(
                    h.stateManager.hardwareKeyTouchPrompt.value,
                    "Touch prompt must be cleared after signing completes",
                )
            }
        }
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
        assertNull(directRoute.directTcpIpPayload)
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
                tailscaleMagicDns = false,
            ),
        )
        val bastionRoute = hBastion.transport.proxyJumpTunnel.resolveRoute(bastionHost)
        assertTrue(bastionRoute.viaBastion)
        assertFalse(bastionRoute.isTailscaleMesh)
        assertEquals("bastion.example.com", bastionRoute.entryHost)
        assertEquals(2222, bastionRoute.entryPort)
        assertEquals("jump", bastionRoute.entryUsername)
        assertEquals("10.20.30.40", bastionRoute.targetHost)
        assertNotNull(bastionRoute.directTcpIpPayload)
        assertTrue(hBastion.transport.connect(bastionHost))
        assertEquals(ConnectionProgress.Connected, hBastion.stateManager.connectionProgress.value["route-bastion"])

        // Path 5C (part 1): Tailscale MagicDNS + CGNAT bastion
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
                tailscaleMagicDns = true,
            ),
        )
        val tsRoute = hTailscale.transport.proxyJumpTunnel.resolveRoute(tailscaleHost)
        assertTrue(tsRoute.viaBastion)
        assertTrue(tsRoute.isTailscaleMesh)
        assertEquals("100.64.0.1", tsRoute.entryHost)
        assertEquals("workbox.ts.net", tsRoute.targetHost)
        assertTrue(hTailscale.transport.connect(tailscaleHost))
        assertEquals(ConnectionProgress.Connected, hTailscale.stateManager.connectionProgress.value["route-tailscale"])

        // Path 5C (part 2): Invalid bastion host (blank or unreachable) fails cleanly to ConnectionProgress.Failed
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

        val hUnreachableBastion = Harness()
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
    }

    @Test
    fun path6A_6B_6C_surfaceParking_resumePresentationFence_networkRoaming_andFifoOfflinePromptFlush() = runTest {
        val h = Harness()
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

        // Path 6A: setSurfaceLease(active = false) parks Mosh UDP and transitions to SurfaceParked while preserving frame
        h.transport.setSurfaceLease(machineId, active = false)
        assertTrue(h.moshEngine.isParked(machineId))
        assertEquals(RoamingStatus.SurfaceParked, h.stateManager.roamingState.value[machineId])
        val surfaceWhileParked = assertNotNull(h.surfaceBus.surfaceFor(machineId))
        assertEquals(surfaceBeforePark.bootId, surfaceWhileParked.bootId)
        assertEquals(surfaceBeforePark.panes.size, surfaceWhileParked.panes.size)

        // Path 6B: setSurfaceLease(active = true) wakes Mosh UDP, commits presentation fence, and restores Healthy
        h.transport.setSurfaceLease(machineId, active = true)
        assertFalse(h.moshEngine.isParked(machineId))
        assertEquals(RoamingStatus.Healthy, h.stateManager.roamingState.value[machineId])
        assertTrue(
            h.debugLog.entries.value.any {
                it.machineId == machineId &&
                    it.tag == "PresentationFence" &&
                    it.message.contains("endpoint.presentation.ready.v1")
            },
        )

        // Path 6C: Queue prompts while RoamingReconnect, then call onNetworkAvailable() -> FIFO flush + roamedCount++
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

        assertEquals(RoamingStatus.Healthy, h.stateManager.roamingState.value[machineId])
        assertTrue(h.stateManager.queuedPromptsForMachine(machineId).isEmpty())

        val backboneAfterRoam = assertIs<ActiveBackbone.MoshUdp>(
            h.stateManager.activeTransportBackbone.value[machineId],
        )
        assertEquals(1, backboneAfterRoam.roamedCount)

        val rpcLogsAfterRoam = h.debugLog.entries.value
            .drop(logCountBeforeRoam)
            .filter { it.machineId == machineId && it.tag == "RPC" }
        assertEquals(2, rpcLogsAfterRoam.size)
        assertTrue(rpcLogsAfterRoam[0].message.contains("First prompt while switching Wi-Fi to 5G"))
        assertTrue(rpcLogsAfterRoam[1].message.contains("Second prompt while switching Wi-Fi to 5G"))
    }

    @Test
    fun sshTransportManagerNegotiatesMoshAndFallsBackToSshEndpoint() = runTest {
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
        val moshBackbone = assertIs<ActiveBackbone.MoshUdp>(stateManager.activeTransportBackbone.value["workbox"])
        assertEquals(60000, moshBackbone.udpPort)

        // Verify Mosh focus RPC routes over herdr remote-api-bridge (herdr.sock)
        val focusResp = transport.sendEndpointCommand("workbox", HerdrRpcBuilder.workspaceFocus("w2"))
        assertTrue(focusResp?.contains("herdr.sock") == true)

        // Verify background surface parking and wakeup presentation fence
        transport.setSurfaceLease("workbox", active = false)
        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value["workbox"])
        transport.setSurfaceLease("workbox", active = true)
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value["workbox"])

        // Verify fallback when mosh-server banner is absent
        h.backend.moshBootstrapBannerProvider = { "mosh-server: not found" }
        val fallbackHost = HostMachine(
            id = "gpu-rig",
            name = "gpu-rig",
            hostname = "100.64.0.25",
            username = "ian",
            transportMode = TransportMode.AutoPreferMosh,
            authMethod = SshAuthMethod.HardwareSecurityKey(),
        )
        assertTrue(transport.connect(fallbackHost))
        assertIs<ActiveBackbone.SshEndpoint>(stateManager.activeTransportBackbone.value["gpu-rig"])

        // Verify pane input and network roam wakeup
        assertTrue(
            transport.sendPaneInput(
                machineId = "workbox",
                paneId = "w1:p1",
                event = ClientPaneInputEvent.TextCommit("echo ok"),
            )
        )
        transport.onNetworkAvailable("workbox")
        val afterRoam = assertIs<ActiveBackbone.MoshUdp>(stateManager.activeTransportBackbone.value["workbox"])
        assertEquals(1, afterRoam.roamedCount)
    }
}
