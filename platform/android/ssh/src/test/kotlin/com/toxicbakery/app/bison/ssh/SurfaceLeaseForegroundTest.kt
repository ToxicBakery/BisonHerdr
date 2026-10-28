package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests the surface lease of [SshTransportManager] in the background with [RealSshBackend] on a real SSH server
 * ([TestSshServer]). The server has the fake `herdr` of [HerdrSessionTest], which writes each call to [callLog]. Thus
 * the test counts the `herdr api snapshot` and `herdr remote-api-bridge` calls of the poll.
 *
 * [inForeground] is the foreground state of the app. The test changes it as `MainActivity.onStart` and `onStop` do:
 * first the state, and then a lease request for each connected machine.
 */
class SurfaceLeaseForegroundTest {

    private val home = createTempDirectory("bison-lease-home").toFile()
    private val callLog = File(home, "herdr-calls")
    private val server = TestSshServer().apply { homeDirectory = home }.start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val debugLog = SshDebugLog()
    private val stateManager = SshStateManager()
    private val surfaceBus = PaneSurfaceBus()
    private val backend = RealSshBackend(
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = surfaceBus,
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = InMemorySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
    )

    @Volatile
    private var inForeground = true

    /** Runs before the backend opens the surface: after the login, before the first read and the poll. */
    @Volatile
    private var beforeOpenSurface: () -> Unit = {}

    private val transport = SshTransportManager(
        backend = object : SshBackend by backend {
            override suspend fun openSurface(
                machine: HostMachine,
                route: ResolvedSshRoute,
                backbone: ActiveBackbone,
                isSurfaceActive: () -> Boolean,
            ) {
                beforeOpenSurface()
                backend.openSurface(machine, route, backbone, isSurfaceActive)
            }
        },
        moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        paneSurfaceBus = surfaceBus,
        foregroundState = ForegroundState { inForeground },
    )
    private val machines = mutableListOf<HostMachine>()

    init {
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/herdr").apply {
            writeText(HerdrSessionTest.FAKE_HERDR)
            check(setExecutable(true)) { "Could not make the fake herdr executable: $this" }
        }
        File(home, "snapshot.json").writeText(HerdrCommandTest.SNAPSHOT_JSON + "\n")
        File(home, "read.json").writeText(HerdrSessionTest.PANE_READ_JSON + "\n")
    }

    @AfterTest
    fun tearDown() {
        runBlocking { machines.forEach { transport.disconnect(it.id) } }
        server.close()
        home.deleteRecursively()
    }

    @Test
    fun aConnectThatCompletesAfterOnStopPausesThePollUntilOnStart() = runTest {
        val machine = machine("lease-after-stop")
        // onStop occurs during the connect, before the poll starts. The machine is not connected yet, so
        // MainActivity sends no lease request for it.
        beforeOpenSurface = { inForeground = false }

        assertTrue(transport.connect(machine), debugLogDump())

        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value[machine.id], debugLogDump())
        val afterConnect = herdrReads()
        assertTrue(afterConnect.snapshots >= 1, "The connect must read the first snapshot: $afterConnect")
        waitInRealTime(POLL_WINDOW)
        assertEquals(afterConnect, herdrReads(), "The poll read the host in the background")

        onStart(machine)

        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[machine.id], debugLogDump())
        awaitSnapshotsAbove(afterConnect.snapshots, "a poll after onStart")
    }

    @Test
    fun aReconnectInTheBackgroundKeepsThePollPausedUntilOnStart() = runTest {
        val machine = machine("lease-reconnect")
        assertTrue(transport.connect(machine), debugLogDump())
        // The connect reads the first snapshot. The poll then reads the next.
        awaitSnapshotsAbove(1, "a poll in the foreground")
        onStop(machine)
        // A poll that started before onStop can still read. Take the counts when they stay the same.
        val beforeReconnect = awaitStableReads()

        // The host closes the session. The network check after a network change finds it, and the app connects
        // again in the background.
        server.closeSessions()
        transport.onNetworkAvailable(machine.id)
        awaitCondition("the reconnect") {
            debugLog.entries.value.firstOrNull { it.message == "The session is open again" }
        }
        awaitValue(stateManager.roamingState, "SurfaceParked") { it[machine.id] == RoamingStatus.SurfaceParked }
        // The status changes before a read of the surface can start. Thus wait, and then check that no read occurred.
        waitInRealTime(POLL_WINDOW)
        assertEquals(beforeReconnect, herdrReads(), "The app read the host in the background")

        onStart(machine)

        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[machine.id], debugLogDump())
        awaitSnapshotsAbove(beforeReconnect.snapshots, "a poll after onStart")
    }

    private fun machine(id: String): HostMachine =
        server.machine(id = id, username = TestSshServer.KEY_USER, authMethod = server.keyAuthMethod)
            .also { machines += it }

    /** Does the steps of `MainActivity.onStart` for [machine]: first the foreground state, then the lease request. */
    private suspend fun onStart(machine: HostMachine) {
        inForeground = true
        transport.handleRequest(SshRequest.SetSurfaceLease(machine.id, active = true))
    }

    /** Does the steps of `MainActivity.onStop` for [machine]: first the foreground state, then the lease request. */
    private suspend fun onStop(machine: HostMachine) {
        inForeground = false
        transport.handleRequest(SshRequest.SetSurfaceLease(machine.id, active = false))
    }

    /** The number of `herdr api snapshot` and `herdr remote-api-bridge` calls in [callLog]. */
    private data class HerdrReads(val snapshots: Int, val bridgeCalls: Int)

    /** Reads [callLog]. The fake writes each argument on its own line. */
    private fun herdrReads(): HerdrReads {
        val lines = if (callLog.exists()) callLog.readLines() else emptyList()
        return HerdrReads(
            snapshots = lines.count { it == "snapshot" },
            bridgeCalls = lines.count { it == "remote-api-bridge" },
        )
    }

    /** Waits until [callLog] has more than [count] `herdr api snapshot` calls. */
    private suspend fun awaitSnapshotsAbove(count: Int, description: String) {
        awaitCondition(description) { herdrReads().takeIf { it.snapshots > count } }
    }

    /**
     * Waits until two samples of [herdrReads], [STABLE_INTERVAL] apart, are equal, and returns the last sample. Thus no
     * read runs when the function returns.
     */
    private suspend fun awaitStableReads(): HerdrReads =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(AWAIT_TIMEOUT) {
                var previous = herdrReads()
                delay(STABLE_INTERVAL)
                var current = herdrReads()
                while (current != previous) {
                    previous = current
                    delay(STABLE_INTERVAL)
                    current = herdrReads()
                }
                current
            }
        } ?: fail("Timeout: the herdr calls did not stop\n${debugLogDump()}")

    /** Waits for [duration] in real time. The poll of the client uses real time. */
    private suspend fun waitInRealTime(duration: Duration) = withContext(Dispatchers.Default) { delay(duration) }

    /** Waits a maximum of [AWAIT_TIMEOUT] in real time until [predicate] is true for the value of [flow]. */
    private suspend fun <T> awaitValue(flow: StateFlow<T>, description: String, predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(AWAIT_TIMEOUT) { flow.first(predicate) }
        } ?: fail("Timeout: $description\n${debugLogDump()}")

    /** Waits a maximum of [AWAIT_TIMEOUT] in real time until [value] gives a result that is not null. */
    private suspend fun <T : Any> awaitCondition(description: String, value: () -> T?): T =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(AWAIT_TIMEOUT) {
                var result = value()
                while (result == null) {
                    delay(AWAIT_STEP)
                    result = value()
                }
                result
            }
        } ?: fail("Timeout: $description\n${debugLogDump()}")

    private fun debugLogDump(): String = debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        /** More than three intervals of the poll (400 ms). */
        val POLL_WINDOW: Duration = 1_500.milliseconds

        /** The time between two samples of [awaitStableReads]. It is more than one interval of the poll. */
        val STABLE_INTERVAL: Duration = 500.milliseconds
        val AWAIT_TIMEOUT: Duration = 20.seconds
        val AWAIT_STEP: Duration = 20.milliseconds
    }
}
