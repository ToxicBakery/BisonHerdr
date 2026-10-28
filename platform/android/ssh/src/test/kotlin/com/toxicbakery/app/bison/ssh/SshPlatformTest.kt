package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientPaneInputEvent
import com.toxicbakery.app.bison.protocol.PaneLayoutPane
import com.toxicbakery.app.bison.protocol.PaneLayoutSnapshot
import com.toxicbakery.app.bison.protocol.SurfaceRect
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class SshPlatformTest {

    @Test
    fun keystoreKeyManagerGeneratesAndSignsEd25519AndEcdsaChallenges() {
        val manager = AndroidKeystoreKeyManager(allowSoftwareKeys = true)
        val edKey = manager.getOrCreatePublicKey(
            keyAlias = "test_ed25519",
            algorithm = KeyAlgorithm.Ed25519,
            comment = "tablet@workbox",
        )
        assertTrue(edKey.openSshAuthorizedKeyLine.startsWith("ssh-ed25519 "))
        assertTrue(edKey.openSshAuthorizedKeyLine.endsWith(" tablet@workbox"))

        val edKeyCached = manager.getOrCreatePublicKey(
            keyAlias = "test_ed25519",
            algorithm = KeyAlgorithm.Ed25519,
            comment = "tablet@workbox",
        )
        assertSame(edKey, edKeyCached)

        manager.deleteKey("test_ed25519")
        val edKeyAfterDelete = manager.getOrCreatePublicKey(
            keyAlias = "test_ed25519",
            algorithm = KeyAlgorithm.Ed25519,
            comment = "tablet@workbox",
        )
        assertNotSame(edKey, edKeyAfterDelete)

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

        // Verify JSch Android crypto providers (Ed25519 signature, X25519 key agreement, and Keystore Identity)
        val xdhClient = AndroidJschXdh().apply { init("X25519", 32) }
        val xdhServer = AndroidJschXdh().apply { init("X25519", 32) }
        assertTrue(xdhClient.validate(xdhServer.q))
        assertTrue(xdhServer.validate(xdhClient.q))
        val clientSecret = xdhClient.getSecret(xdhServer.q)
        val serverSecret = xdhServer.getSecret(xdhClient.q)
        assertTrue(clientSecret.contentEquals(serverSecret))

        val rawSeed = ByteArray(32) { (it + 1).toByte() }
        val jschEdSigner = AndroidJschSignatureEd25519().apply {
            init()
            setPrvKey(rawSeed)
            update("ssh-kex-exchange-hash".toByteArray())
        }
        val rawEdSig = jschEdSigner.sign()
        assertEquals(64, rawEdSig.size)

        val edIdentity = AndroidKeystoreJschIdentity(
            keystoreKeyManager = manager,
            keyAlias = "test_ed25519",
            algorithm = KeyAlgorithm.Ed25519,
        )
        assertEquals("ssh-ed25519", edIdentity.algName)
        assertFalse(edIdentity.isEncrypted)
        val edChallenge = "ssh-userauth-session-challenge".toByteArray()
        val wrappedEdSig = assertNotNull(edIdentity.getSignature(edChallenge, "ssh-ed25519"))
        // Extract the 32-byte raw Ed25519 public key at the end of the SSH public key wire blob
        val pubBlob = edIdentity.publicKeyBlob
        val rawPub32 = pubBlob.copyOfRange(pubBlob.size - 32, pubBlob.size)
        val jschEdVerifier = AndroidJschSignatureEd25519().apply {
            init()
            setPubKey(rawPub32)
            update(edChallenge)
        }
        assertTrue(jschEdVerifier.verify(wrappedEdSig))
    }

    @Test
    fun proxyJumpTunnelDetectsTailscaleAndRoutesThroughTheBastion() {
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
            ),
        )
        val route = tunnel.resolveRoute(machine)
        assertTrue(route.viaBastion)
        assertTrue(route.isTailscaleMesh)
        assertEquals("bastion.tailnet.ts.net", route.entryHost)
        assertEquals(22, route.entryPort)
        assertEquals("jump", route.entryUsername)
        assertEquals("10.0.0.42", route.targetHost)
        assertEquals(2222, route.targetPort)
        assertEquals("ian", route.targetUsername)
        assertEquals("jump@bastion.tailnet.ts.net:22", route.bastionAddress)
        assertEquals("10.0.0.42:2222", route.targetAddress)

        // A blank bastion user gives the user of the machine.
        val blankUser = machine.copy(proxyJump = machine.proxyJump?.copy(bastionUser = "  "))
        assertEquals("ian", tunnel.resolveRoute(blankUser).entryUsername)
    }

    @Test
    fun moshUdpEngineStartsNoSessionBecauseMoshIsNotAvailable() = runTest {
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
        assertFalse(started)
        assertFalse(engine.isSessionActive("workbox"))
        val entry = debugLog.entries.value.single()
        assertEquals(MoshUdpEngine.LOG_TAG, entry.tag)
        assertEquals(MoshSessionEngine.NOT_AVAILABLE, entry.message)

        // With no session, the other functions do nothing.
        assertFalse(engine.sendInput("workbox", "ls".encodeToByteArray()))
        assertFalse(engine.resize("workbox", 100u, 30u))
        engine.updateLayoutSnapshot(
            machineId = "workbox",
            layout = PaneLayoutSnapshot(
                workspaceId = "w1",
                tabId = "w1:t1",
                zoomed = false,
                panes = listOf(PaneLayoutPane("w1:p1", SurfaceRect(0u, 0u, 80u, 24u), focused = true)),
                splits = emptyList(),
            ),
        )
        assertEquals(0, engine.sendWakeupDatagram("workbox"))
        assertFalse(engine.isParked("workbox"))
        assertNull(surfaceBus.surfaceFor("workbox"))
        assertEquals(1, debugLog.entries.value.size)
    }

    @Test
    fun realHerdrSshClientAppliesInstantOptimisticEchoAndCoalescesInputOnDedicatedLane() = runTest {
        val debugLog = SshDebugLog()
        val snapshotBus = EndpointSnapshotBus()
        val surfaceBus = PaneSurfaceBus()
        val notificationBus = SemanticNotificationBus()

        val recordedBatches = mutableListOf<Pair<RealHerdrSshClient.WorkerLane, List<RealHerdrSshClient.RemoteShellCommand>>>()
        var remotePaneBuffer = "$ "
        // Holds the Input lane until the test has checked the optimistic echo. Without it, the
        // background flush can replace the echo with the server read before the check.
        val inputLaneGate = CompletableDeferred<Unit>()

        val client = RealHerdrSshClient(
            sshDebugLog = debugLog,
            endpointSnapshotBus = snapshotBus,
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = notificationBus,
            knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
            secretStore = InMemorySecretStore(),
            remoteBatchExecutor = { _, _, lane, commands ->
                if (lane == RealHerdrSshClient.WorkerLane.Input) inputLaneGate.await()
                synchronized(recordedBatches) {
                    recordedBatches.add(lane to commands)
                }
                buildString {
                    for (cmd in commands) {
                        when (cmd) {
                            is RealHerdrSshClient.RemoteShellCommand.Exec -> {
                                if (cmd.command.contains("herdr api snapshot")) {
                                    appendLine(
                                        """{"id":"snap","result":{"type":"session_snapshot","snapshot":{"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"%1","workspaces":[{"workspace_id":"w1","active_tab_id":"w1:t1","label":"main","focused":true}],"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"shell","focused":true}],"panes":[{"pane_id":"%1","workspace_id":"w1","tab_id":"w1:t1","label":"zsh","cwd":"~","focused":true}],"agents":[],"layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"panes":[{"pane_id":"%1","rect":{"x":0,"y":0,"width":80,"height":24},"focused":true}],"splits":[]}]}}}"""
                                    )
                                }
                            }
                            is RealHerdrSshClient.RemoteShellCommand.ApiBridgeLine -> {
                                val line = cmd.ndjsonLine
                                when {
                                    line.contains("\"pane.send_text\"") || line.contains("\"pane.send_input\"") -> {
                                        val textMatch = Regex("\"text\":\"([^\"]*)\"").find(line)
                                        if (textMatch != null) {
                                            remotePaneBuffer += textMatch.groupValues[1]
                                        }
                                        appendLine("""{"id":"in_1","result":{"type":"ok"}}""")
                                    }
                                    line.contains("\"pane.read\"") -> {
                                        appendLine(
                                            """{"id":"read_%1","result":{"type":"pane_read","read":{"pane_id":"%1","text":"$remotePaneBuffer"}}}"""
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )

        val machine = HostMachine(
            id = "lan-box",
            name = "LAN Box",
            hostname = "192.168.1.42",
            username = "ian",
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)

        // 1. Seed initial snapshot and surface via Poll lane
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        val initialSurface = assertNotNull(surfaceBus.surfaceFor("lan-box"))
        assertEquals("$", initialSurface.frame.cellAt(0, 0)?.symbol)

        synchronized(recordedBatches) { recordedBatches.clear() }

        // 2. Verify buildCoalescedInputCommands merges typed text into one pane.send_text, and sends the key after it.
        // Typed text never goes in pane.send_input, because Herdr can put that text in a bracketed paste.
        val coalesced = client.buildCoalescedInputCommands(
            listOf(
                RealHerdrSshClient.PendingPaneInput(1L, "%1", ClientPaneInputEvent.TextCommit("l")),
                RealHerdrSshClient.PendingPaneInput(2L, "%1", ClientPaneInputEvent.TextCommit("s")),
                RealHerdrSshClient.PendingPaneInput(
                    3L,
                    "%1",
                    ClientPaneInputEvent.Key(ClientKeyEvent(code = ClientKeyCode.Enter)),
                ),
            )
        )
        assertEquals(2, coalesced.size)
        assertTrue(coalesced[0].ndjsonLine.contains("\"method\":\"pane.send_text\""))
        assertTrue(coalesced[0].ndjsonLine.contains("\"text\":\"ls\""))
        assertTrue(coalesced[1].ndjsonLine.contains("\"method\":\"pane.send_keys\""))
        assertTrue(coalesced[1].ndjsonLine.contains("\"keys\":[\"Enter\"]"))

        // 3. Send "l" and "s" and verify instant optimistic local echo on PaneSurfaceBus
        assertTrue(client.sendPaneInput(machine, route, "%1", ClientPaneInputEvent.TextCommit("l")))
        val optimisticAfterL = assertNotNull(surfaceBus.surfaceFor("lan-box"))
        val predictedCell = assertNotNull(optimisticAfterL.frame.cellAt(1, 0))
        assertEquals("l", predictedCell.symbol)
        assertTrue(predictedCell.isPredictedEcho)
        inputLaneGate.complete(Unit)

        assertTrue(client.sendPaneInput(machine, route, "%1", ClientPaneInputEvent.TextCommit("s")))
        client.awaitInputFlush("lan-box")

        // 4. Verify input executed on WorkerLane.Input and did NOT invoke `herdr api snapshot`
        val executedCopy = synchronized(recordedBatches) { recordedBatches.toList() }
        assertTrue(executedCopy.isNotEmpty())
        assertTrue(executedCopy.all { (lane, _) -> lane == RealHerdrSshClient.WorkerLane.Input })
        val allInputCmds = executedCopy.flatMap { it.second }
        assertTrue(
            allInputCmds.none { cmd ->
                cmd is RealHerdrSshClient.RemoteShellCommand.Exec && cmd.command.contains("herdr api snapshot")
            },
            "Typing characters must not trigger full herdr api snapshot processes",
        )

        // 5. Verify authoritative server read ("$ ls") replaced the optimistic prediction
        val settledSurface = assertNotNull(surfaceBus.surfaceFor("lan-box"))
        assertEquals("l", settledSurface.frame.cellAt(2, 0)?.symbol)
        assertEquals("s", settledSurface.frame.cellAt(3, 0)?.symbol)
        assertFalse(settledSurface.frame.cellAt(2, 0)?.isPredictedEcho == true)

        client.disconnect("lan-box")
    }
}
