package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Tests the backbone that [RealSshBackend] reports. This version has no Mosh transport (issue #22), so the backbone
 * is always the SSH endpoint bridge. Its round trip time comes from the poll of [RealHerdrSshClient].
 */
class BackboneReportTest {

    /** A Herdr host that answers each command batch after [roundTrip] on [clock]. */
    private class FakeHerdr(private val clock: TestTimeSource) {
        @Volatile var roundTrip: Duration = 40.milliseconds

        /** The error of the next batches, or null. */
        @Volatile var failure: IOException? = null

        /** Runs in each snapshot batch, while the clock measures the round trip. */
        @Volatile var duringSnapshot: () -> Unit = {}

        fun execute(commands: List<RealHerdrSshClient.RemoteShellCommand>): String {
            val snapshot = commands.any { it is RealHerdrSshClient.RemoteShellCommand.Exec && "herdr api snapshot" in it.command }
            if (snapshot) {
                clock += roundTrip
                duringSnapshot()
            }
            failure?.let { throw it }
            return if (snapshot) SNAPSHOT_JSON + "\n" else ""
        }
    }

    private val clock = TestTimeSource()
    private val fake = FakeHerdr(clock)
    private val stateManager = SshStateManager()
    private val machine = HostMachine(id = "box", name = "box", hostname = "192.168.1.42", username = "ian")
    private val route = ProxyJumpTunnel().resolveRoute(machine)

    private fun client() = RealHerdrSshClient(
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
        secretStore = InMemorySecretStore(),
        remoteBatchExecutor = { _, _, _, commands -> fake.execute(commands) },
        timeSource = clock,
    )

    /** Returns a [RealSshBackend] that uses [client]. The machine of the tests logs in with no stored key. */
    private fun backend(client: RealHerdrSshClient) = RealSshBackend(
        client = client,
        sshStateManager = stateManager,
        keystoreKeyManager = AndroidKeystoreKeyManager(allowSoftwareKeys = true),
    )

    /** Marks [machine] connected with a new backbone, as the transport does after a connect. */
    private fun connectBackbone() {
        stateManager.markMachineConnected(
            machine.id,
            ActiveBackbone.SshEndpoint(rttMs = RealSshBackend.UNMEASURED_RTT_MS, fallbackReason = REASON),
        )
    }

    private fun shownRttMs(): Int = assertIs<ActiveBackbone.SshEndpoint>(
        stateManager.activeTransportBackbone.value[machine.id],
    ).rttMs

    /** Runs one poll that takes [roundTrip]. */
    private suspend fun RealSshBackend.poll(roundTrip: Duration) {
        fake.roundTrip = roundTrip
        refreshSurface(machine, route)
    }

    @Test
    fun theBackboneGetsTheAverageOfTheMeasuredRoundTrips() = runTest {
        val backend = backend(client())
        connectBackbone()
        assertEquals(RealSshBackend.UNMEASURED_RTT_MS, shownRttMs())

        // The first round trip sets the average.
        backend.poll(40.milliseconds)
        assertEquals(40, shownRttMs())

        // Each new round trip moves the average by one eighth of the difference: 40 + (120 - 40) / 8 = 50.
        clock += 1.seconds
        backend.poll(120.milliseconds)
        assertEquals(50, shownRttMs())

        // The backbone keeps its other values.
        assertEquals(REASON, (stateManager.activeTransportBackbone.value[machine.id] as ActiveBackbone.SshEndpoint).fallbackReason)
    }

    @Test
    fun theBackboneChangesAtMostOnceEachSecondAndOnlyWhenTheValueChanges() = runTest {
        val client = client()
        val backend = backend(client)
        connectBackbone()

        backend.poll(40.milliseconds)
        assertEquals(40, shownRttMs())

        // 40 + (200 - 40) / 8 = 60, but the last change was less than one second ago.
        clock += 500.milliseconds
        backend.poll(200.milliseconds)
        assertEquals(60, client.averageRoundTripMs(machine.id))
        assertEquals(40, shownRttMs())

        // One second after the change, the next round trip publishes the average.
        clock += 1.seconds
        backend.poll(60.milliseconds)
        assertEquals(60, shownRttMs())

        // An average with the same value is no change, so the next round trip can publish at once.
        clock += 1.seconds
        backend.poll(60.milliseconds)
        assertEquals(60, shownRttMs())
        backend.poll(140.milliseconds)
        assertEquals(70, shownRttMs())
    }

    @Test
    fun aFailedRoundTripIsNotCounted() = runTest {
        val client = client()
        val backend = backend(client)
        connectBackbone()
        backend.poll(40.milliseconds)

        clock += 1.seconds
        fake.failure = IOException("The connection closed")
        assertFailsWith<IOException> { backend.poll(900.milliseconds) }
        assertEquals(40, client.averageRoundTripMs(machine.id))
        assertEquals(40, shownRttMs())

        // The next round trip that passes counts again.
        fake.failure = null
        backend.poll(120.milliseconds)
        assertEquals(50, shownRttMs())
    }

    @Test
    fun aRoundTripDuringAReconnectIsNotCounted() = runTest {
        val client = client()
        val backend = backend(client)
        connectBackbone()
        // The poll of the client reads nothing, so that only the test polls.
        client.startLivePolling(machine, route, isSurfaceActive = { false })
        try {
            backend.poll(40.milliseconds)
            assertEquals(40, shownRttMs())

            // The device loses its network while the round trip runs. The supervisor then waits for a network.
            clock += 1.seconds
            fake.duringSnapshot = { client.closeSockets(machine.id) }
            backend.poll(900.milliseconds)
            assertIs<SessionSupervisor.State.Reconnecting>(client.reconnectState(machine.id))
            assertEquals(40, client.averageRoundTripMs(machine.id))

            // A round trip that starts while the supervisor reconnects does not count.
            fake.duringSnapshot = {}
            backend.poll(900.milliseconds)
            assertEquals(40, client.averageRoundTripMs(machine.id))
            assertEquals(40, shownRttMs())
        } finally {
            client.disconnect(machine.id)
        }
    }

    @Test
    fun aDisconnectStartsANewAverage() = runTest {
        val client = client()
        val backend = backend(client)
        connectBackbone()
        backend.poll(40.milliseconds)
        assertEquals(40, client.averageRoundTripMs(machine.id))

        backend.disconnect(machine.id)
        assertNull(client.averageRoundTripMs(machine.id))
    }

    @Test
    fun theRealPathReportsTheSshEndpointWithAReasonWhenTheHostHasMoshServer() = runTest {
        val binDirectory = createTempDirectory("bison-mosh-bin").toFile()
        File(binDirectory, "mosh-server").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        TestSshServer().start().use { server ->
            server.binDirectory = binDirectory
            val backend = realBackend(server)
            for (mode in listOf(TransportMode.AutoPreferMosh, TransportMode.MoshOnly)) {
                val machine = server.machine(
                    id = "mosh-host-$mode",
                    username = TestSshServer.KEY_USER,
                    authMethod = server.keyAuthMethod,
                    transportMode = mode,
                )
                val route = ProxyJumpTunnel().resolveRoute(machine)
                try {
                    assertEquals(SshAuthResult.Success, backend.authenticate(machine, route))
                    assertEquals(
                        ActiveBackbone.SshEndpoint(
                            rttMs = RealSshBackend.UNMEASURED_RTT_MS,
                            fallbackReason = MoshSessionEngine.MOSH_SERVER_FOUND,
                        ),
                        backend.negotiateBackbone(machine, route),
                        "Mode $mode",
                    )
                } finally {
                    backend.disconnect(machine.id)
                }
            }
        }
        binDirectory.deleteRecursively()
    }

    @Test
    fun theRealPathReportsTheSshEndpointWithNoReasonWhenTheHostHasNoMoshServer() = runTest {
        TestSshServer().start().use { server ->
            val backend = realBackend(server)
            val machine = server.machine(
                id = "no-mosh-host",
                username = TestSshServer.KEY_USER,
                authMethod = server.keyAuthMethod,
                transportMode = TransportMode.AutoPreferMosh,
            ).copy(moshServerPath = "bison-test-no-such-mosh-server")
            val route = ProxyJumpTunnel().resolveRoute(machine)
            try {
                assertEquals(SshAuthResult.Success, backend.authenticate(machine, route))
                assertEquals(
                    ActiveBackbone.SshEndpoint(rttMs = RealSshBackend.UNMEASURED_RTT_MS, fallbackReason = null),
                    backend.negotiateBackbone(machine, route),
                )
            } finally {
                backend.disconnect(machine.id)
            }
        }
    }

    /** Returns a [RealSshBackend] that knows the host key of [server] and logs in with its key. */
    private fun realBackend(server: TestSshServer) = RealSshBackend(
        sshStateManager = SshStateManager(),
        sshDebugLog = SshDebugLog(),
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply { runBlocking { put(server.knownHost()) } },
        secretStore = InMemorySecretStore(),
    )

    private companion object {
        const val REASON = MoshSessionEngine.MOSH_SERVER_FOUND

        const val SNAPSHOT_JSON =
            """{"id":"snap","result":{"type":"session_snapshot","snapshot":{"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",""" +
                """"workspaces":[{"workspace_id":"w1","active_tab_id":"w1:t1","label":"main","focused":true}],""" +
                """"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"1","focused":true}],""" +
                """"panes":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","focused":true}],"agents":[],""" +
                """"layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"panes":[{"pane_id":"w1:p1","rect":{"x":0,"y":0,"width":80,"height":40},"focused":true}],"splits":[]}]}}}"""
    }
}
