package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.PaneLayoutPane
import com.toxicbakery.app.bison.protocol.PaneLayoutSnapshot
import com.toxicbakery.app.bison.protocol.PaneLayoutSplit
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import com.toxicbakery.app.bison.protocol.SurfaceRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SshPlatformTest {

    @Test
    fun keystoreKeyManagerGeneratesAndSignsEd25519AndEcdsaChallenges() {
        val manager = AndroidKeystoreKeyManager()
        val edKey = manager.getOrCreatePublicKey(
            keyAlias = "test_ed25519",
            algorithm = KeyAlgorithm.Ed25519,
            comment = "tablet@workbox",
        )
        assertTrue(edKey.openSshAuthorizedKeyLine.startsWith("ssh-ed25519 "))
        assertTrue(edKey.openSshAuthorizedKeyLine.endsWith(" tablet@workbox"))

        val edSig = manager.signSshChallenge(
            keyAlias = "test_ed25519",
            algorithm = KeyAlgorithm.Ed25519,
            challenge = "challenge-payload".toByteArray(),
        )
        assertTrue(edSig.isNotEmpty())

        val ecdsaKey = manager.getOrCreatePublicKey(
            keyAlias = "test_ecdsa",
            algorithm = KeyAlgorithm.EcdsaP256,
            comment = "tablet@ecdsa",
        )
        assertTrue(ecdsaKey.openSshAuthorizedKeyLine.startsWith("ecdsa-sha2-nistp256 "))
        val ecdsaSig = manager.signSshChallenge(
            keyAlias = "test_ecdsa",
            algorithm = KeyAlgorithm.EcdsaP256,
            challenge = "challenge-payload".toByteArray(),
        )
        assertTrue(ecdsaSig.isNotEmpty())
    }

    @Test
    fun yubiKeyFido2ManagerSignsSkChallengeAndDismissesTouchPrompt() = runTest {
        val stateManager = SshStateManager()
        val debugLog = SshDebugLog()
        val yubiKey = YubiKeyFido2Manager(
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
        )

        val result = yubiKey.signChallenge(
            machineId = "workbox",
            keyType = HardwareKeyType.SkSshEd25519.opensshKeyType,
            applicationId = "ssh:herdr",
            challenge = "fido2-challenge".toByteArray(),
        )
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isNotEmpty())
        assertNull(stateManager.hardwareKeyTouchPrompt.value)
    }

    @Test
    fun proxyJumpTunnelDetectsTailscaleAndBuildsDirectTcpIpPayload() {
        val tunnel = ProxyJumpTunnel()
        assertTrue(tunnel.isTailscaleAddress("100.64.0.12"))
        assertTrue(tunnel.isTailscaleAddress("workbox.tailnet.ts.net"))
        assertFalse(tunnel.isTailscaleAddress("192.168.1.50"))

        val machine = HostMachine(
            id = "gpu-rig",
            name = "gpu-rig",
            hostname = "10.0.0.42",
            port = 2222,
            username = "ian",
            proxyJump = ProxyJumpConfig(
                bastionHost = "bastion.tailnet.ts.net",
                bastionPort = 22,
                bastionUser = "jump",
                tailscaleMagicDns = true,
            ),
        )
        val route = tunnel.resolveRoute(machine)
        assertTrue(route.viaBastion)
        assertTrue(route.isTailscaleMesh)
        assertEquals("bastion.tailnet.ts.net", route.entryHost)
        assertEquals("10.0.0.42", route.targetHost)
        assertNotNull(route.directTcpIpPayload)
    }

    @Test
    fun moshUdpEngineSynthesizesFrameWithPredictiveEchoAndDerivedSplits() = runTest {
        val debugLog = SshDebugLog()
        val surfaceBus = PaneSurfaceBus()
        val engine = MoshUdpEngine(
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )

        val started = engine.startSession(
            machineId = "workbox",
            host = "127.0.0.1",
            udpPort = 60004,
            sessionKey = "4k9X2vL8pQ1mN7rT3wY6zA",
            cols = 80u,
            rows = 24u,
            localEcho = true,
        )
        assertTrue(started)
        assertTrue(engine.isSessionActive("workbox"))

        // Send printable input and verify predictive echo cell is underlined
        engine.sendInput("workbox", "ls".encodeToByteArray())
        val afterType = assertNotNull(surfaceBus.surfaceFor("workbox"))
        val cell0 = assertNotNull(afterType.frame.cellAt(0, 0))
        assertEquals("l", cell0.symbol)
        assertTrue(cell0.isPredictedEcho)

        // Apply layout snapshot with vertical split and verify derived split hitRect
        engine.updateLayoutSnapshot(
            machineId = "workbox",
            layout = PaneLayoutSnapshot(
                workspaceId = "w1",
                tabId = "w1:t1",
                zoomed = false,
                panes = listOf(
                    PaneLayoutPane("w1:p1", SurfaceRect(0u, 0u, 40u, 24u), focused = true),
                    PaneLayoutPane("w1:p2", SurfaceRect(40u, 0u, 40u, 24u), focused = false),
                ),
                splits = listOf(
                    PaneLayoutSplit(
                        id = "s0:root",
                        direction = PaneSurfaceSplitDirection.Vertical,
                        ratio = 0.5f,
                        rect = SurfaceRect(0u, 0u, 80u, 24u),
                    )
                ),
            ),
            tabCount = 2,
        )
        val withSplits = assertNotNull(surfaceBus.surfaceFor("workbox"))
        assertEquals(1, withSplits.splits.size)
        assertEquals(40u.toUShort(), withSplits.splits.single().hitRect.x)

        // Park and wake via roam datagram
        engine.setParked("workbox", true)
        assertTrue(engine.isParked("workbox"))
        val roamCount = engine.sendWakeupDatagram("workbox")
        assertEquals(1, roamCount)
        assertFalse(engine.isParked("workbox"))
    }

    @Test
    fun sshTransportManagerNegotiatesMoshAndFallsBackToSshEndpoint() = runTest {
        val debugLog = SshDebugLog()
        val stateManager = SshStateManager()
        val snapshotBus = EndpointSnapshotBus()
        val surfaceBus = PaneSurfaceBus()
        val notificationBus = SemanticNotificationBus()
        val moshEngine = MoshUdpEngine(debugLog, surfaceBus)
        val transport = SshTransportManager(
            context = null,
            moshUdpEngine = moshEngine,
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            endpointSnapshotBus = snapshotBus,
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = notificationBus,
        )

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
        transport.moshBootstrapBannerProvider = { "mosh-server: not found" }
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
