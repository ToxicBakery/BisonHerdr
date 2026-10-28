package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.HerdrRpcBuilder
import com.toxicbakery.app.bison.protocol.SemanticNotification
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Tests the agent alerts of [RealHerdrSshClient] for real hosts (issue #53): the alerts of the snapshots of the poll,
 * and the background path over SSH while the surface lease is off.
 */
class AgentAlertsTest {

    private val notifications = SemanticNotificationBus()

    /** The alerts on the bus in the order that the client sent them, as `pane=status`. */
    private fun alerts(): List<String> = notifications.recentNotifications.value.reversed()
        .map { (_, alert) -> "${alert.paneId}=${alert.agentStatus?.wireName}" }

    private fun lastAlert(): SemanticNotification = notifications.recentNotifications.value.first().second

    @Test
    fun eachSnapshotOfThePollGivesOneAlertForEachTransition() = runTest {
        var snapshot = snapshotLine("working")
        val client = RealHerdrSshClient(
            sshDebugLog = SshDebugLog(),
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = PaneSurfaceBus(),
            semanticNotificationBus = notifications,
            knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()),
            secretStore = InMemorySecretStore(),
            localNetworkGuard = permittedLocalNetwork(),
            remoteBatchExecutor = { _, _, _, commands ->
                val hasSnapshot = commands.any {
                    it is RealHerdrSshClient.RemoteShellCommand.Exec && "herdr api snapshot" in it.command
                }
                if (hasSnapshot) snapshot else ""
            },
        )
        val machine = HostMachine(id = "box", name = "box", hostname = "192.168.1.42", username = "ian")
        val route = ProxyJumpTunnel().resolveRoute(machine)

        // The first snapshot after a connect gives no alert, also for a blocked agent.
        snapshot = snapshotLine("blocked")
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(emptyList(), alerts())

        snapshot = snapshotLine("working")
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        snapshot = snapshotLine("blocked")
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(listOf("w1:p1=blocked"), alerts())
        val alert = lastAlert()
        assertEquals("claude", alert.title)
        assertEquals("claude", alert.agentName)
        assertEquals("Fix the build", alert.body)
        assertEquals("herdr-box", alert.bootId)

        snapshot = snapshotLine("done")
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(listOf("w1:p1=blocked", "w1:p1=done"), alerts())

        // A disconnect clears the known agents. The next snapshot is the first snapshot of a new connect.
        client.disconnect(machine.id)
        snapshot = snapshotLine("blocked")
        assertTrue(client.connectAndVerify(machine, route).isSuccess)
        assertTrue(client.refreshSnapshotAndSurface(machine, route))
        assertEquals(listOf("w1:p1=blocked", "w1:p1=done"), alerts())
    }

    @Test
    fun theBackgroundWatchGivesTheAlertsOfAHostOverSsh() = runTest {
        TestSshServer().start().use { server ->
            val host = FakeHerdrHost(server)
            host.snapshot = snapshotLine("working")
            val states = SshStateManager()
            val debugLog = SshDebugLog()
            val client = RealHerdrSshClient(
                sshDebugLog = debugLog,
                endpointSnapshotBus = EndpointSnapshotBus(),
                paneSurfaceBus = PaneSurfaceBus(),
                semanticNotificationBus = notifications,
                knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
                    runBlocking { put(server.knownHost()) }
                },
                keystoreKeyManagerProvider = { server.keyManager },
                secretStore = InMemorySecretStore(),
                localNetworkGuard = permittedLocalNetwork(),
                agentWatchRetryDelay = { RETRY_DELAY },
            )
            val backend = RealSshBackend(client, states, server.keyManager)
            val machine = server.machine(
                id = "agents",
                username = TestSshServer.KEY_USER,
                authMethod = server.keyAuthMethod,
            ).copy(herdrSessionName = "work")
            val route = ProxyJumpTunnel().resolveRoute(machine)
            var leaseOn = false
            try {
                assertEquals(SshAuthResult.Success, backend.authenticate(machine, route))
                assertTrue(client.refreshSnapshotAndSurface(machine, route))
                client.startLivePolling(machine, route, isSurfaceActive = { leaseOn })

                // With the lease off, the watch subscribes to the agent pane, and then reads a snapshot.
                awaitRealTime { host.streamStarts == 1 && "snapshot" in host.log().dropWhile { it != "started" } }
                val subscribe = host.requests().single { "events.subscribe" in it }
                assertTrue("\"pane.agent_status_changed\",\"pane_id\":\"w1:p1\"" in subscribe, subscribe)
                // Each herdr call of the watch selects the Herdr session of the machine.
                val calls = host.log().filter { it.startsWith("session ") }.toSet()
                assertEquals(
                    setOf("session work --version", "session work api", "session work remote-api-bridge"),
                    calls,
                )

                // Each transition gives one alert. The same status again gives no alert.
                host.sendEvent(statusEvent("blocked"))
                host.sendEvent(statusEvent("blocked"))
                host.sendEvent(statusEvent("done"))
                awaitRealTime { alerts().size == 2 }
                assertEquals(listOf("w1:p1=blocked", "w1:p1=done"), alerts())
                assertEquals("Fix the build", lastAlert().body)

                // Approve sends Enter to the pane over the SSH control lane while the lease is off.
                val approve = HerdrRpcBuilder.paneSendKeys("w1:p1", listOf("Enter"), requestId = "agent_alert_approve")
                val answer = assertNotNull(client.executeEndpointRpc(machine, route, approve))
                assertTrue("\"id\":\"agent_alert_approve\",\"result\"" in answer, answer)
                assertTrue(host.requests().any { "\"method\":\"pane.send_keys\"" in it && "Enter" in it })

                // The server closes the session. The agent changes during the reconnect. The watch connects again,
                // and the snapshot after the new subscription gives the alert of the change.
                host.snapshot = snapshotLine("blocked")
                server.closeSessions()
                awaitRealTime { host.streamStarts == 2 && alerts().size == 3 }
                assertEquals(listOf("w1:p1=blocked", "w1:p1=done", "w1:p1=blocked"), alerts())
                assertEquals(SessionSupervisor.State.Live, client.reconnectState(machine.id))

                // A closed session also reconnects for an action. The watch can reconnect first.
                val sessionBefore = client.liveSessionId(machine.id)
                server.closeSessions()
                awaitRealTime { client.liveSessionId(machine.id) != sessionBefore }
                val reject = HerdrRpcBuilder.paneSendKeys("w1:p1", listOf("Esc"), requestId = "agent_alert_reject")
                val rejectAnswer = assertNotNull(client.executeEndpointRpc(machine, route, reject))
                assertTrue("\"id\":\"agent_alert_reject\",\"result\"" in rejectAnswer, rejectAnswer)

                // The lease comes on. The poll stops the watch, and the stream of the host stops.
                awaitRealTime { host.streamStarts == host.streamStops + 1 }
                leaseOn = true
                awaitRealTime { host.streamStarts == host.streamStops }

                // With no server, an action fails after one attempt, and the client gives no answer.
                leaseOn = false
                server.stop()
                awaitRealTime { !client.hasLiveSession(machine.id) }
                assertNull(client.executeEndpointRpc(machine, route, approve))
            } finally {
                backend.disconnect(machine.id)
            }
            assertEquals(3, alerts().size, debugLog.entries.value.joinToString("\n") { it.message })
        }
    }

    /** The fake `herdr` of [server] and its state. See the resource `fake-herdr-agents.sh`. */
    private class FakeHerdrHost(server: TestSshServer) {
        private val state = File(server.homeDirectory, "state").apply { mkdirs() }
        private val events = File(state, "events").apply { mkdirs() }

        init {
            val script = assertNotNull(javaClass.getResource("/fake-herdr-agents.sh")).readText()
            server.installHerdr(script)
        }

        /** The line of `herdr api snapshot`. */
        var snapshot: String
            get() = File(state, "snapshot.json").readText()
            set(value) {
                val next = File(state, "snapshot.tmp")
                next.writeText(value + "\n")
                check(next.renameTo(File(state, "snapshot.json")))
            }

        /** The number of subscriptions that started. */
        val streamStarts: Int
            get() = log().count { it == "started" }

        /** The number of subscription processes that the client stopped. */
        val streamStops: Int
            get() = log().count { it == "stopped" }

        /** The steps of the host in order: `session <name> <subcommand>`, `snapshot`, `started`, and `stopped`. */
        fun log(): List<String> = File(state, "host.log").takeIf { it.exists() }?.readLines().orEmpty()

        /** Each request line that `herdr remote-api-bridge` read, in order. */
        fun requests(): List<String> = File(state, "requests.log").takeIf { it.exists() }?.readLines().orEmpty()

        /** Sends [line] on the stream of the subscription, and waits until the fake took it. */
        suspend fun sendEvent(line: String) {
            val next = File(events, "next.json")
            awaitRealTime { !next.exists() }
            val temporary = File(events, "next.tmp")
            temporary.writeText(line + "\n")
            check(temporary.renameTo(next))
            awaitRealTime { !next.exists() }
        }
    }

    private companion object {
        /** The retry delay of the agent watch in the SSH test. */
        val RETRY_DELAY = 200.milliseconds

        /** The maximum wait for one step of the SSH test. */
        val STEP_TIMEOUT = 15.seconds

        /** Waits in real time, for [STEP_TIMEOUT] at most, until [condition] is true. */
        suspend fun awaitRealTime(condition: () -> Boolean) = withContext(Dispatchers.IO) {
            withTimeout(STEP_TIMEOUT) {
                while (!condition()) delay(10.milliseconds)
            }
        }

        /** Returns a `herdr api snapshot` line with one agent in `w1:p1` with [status]. */
        fun snapshotLine(status: String): String =
            """{"id":"cli:api:snapshot","result":{"type":"session_snapshot","snapshot":{""" +
                """"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",""" +
                """"workspaces":[{"workspace_id":"w1","label":"~","number":1,"active_tab_id":"w1:t1"}],""" +
                """"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","label":"1","number":1}],""" +
                """"panes":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","focused":true}],""" +
                """"agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"claude",""" +
                """"title":"Fix the build","agent_status":"$status"}],"layouts":[]}}}"""

        /** Returns a `pane.agent_status_changed` event of `w1:p1` with [status], as Herdr 0.9.5 writes it. */
        fun statusEvent(status: String): String =
            """{"data":{"agent":"claude","agent_status":"$status","pane_id":"w1:p1","workspace_id":"w1"},""" +
                """"event":"pane.agent_status_changed"}"""
    }
}
