package com.toxicbakery.app.bison.ssh

import android.security.keystore.UserNotAuthenticatedException
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.security.Signature
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests the reconnect loop of [RealHerdrSshClient] and [SshTransportManager] against real SSH servers
 * ([TestSshServer]).
 *
 * The loop runs in the background scope of the test, so its delays use virtual time. The test records each delay
 * in [delays]. The SSH connections use real sockets, so the test waits for each result with a real timeout of
 * [AWAIT_TIMEOUT].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReconnectTest {

    private val debugLog = SshDebugLog()

    /** The error of each sign with a [keyManager] key, or null when the key signs. */
    @Volatile
    private var signError: Exception? = null
    private val keyManager = AndroidKeystoreKeyManager(
        allowSoftwareKeys = true,
        signatureFactory = { algorithm -> signError?.let { throw it } ?: Signature.getInstance(algorithm) },
    )
    private val server = TestSshServer(keyManager = keyManager, name = TARGET).start()
    private val bastions = mutableListOf<TestSshServer>()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val secretStore = InMemorySecretStore().apply {
        runBlocking { put(PASSWORD_REF, TestSshServer.PASSWORD.encodeToByteArray()) }
    }
    private val stateManager = SshStateManager()
    private val surfaceBus = PaneSurfaceBus()
    private val backoff = ReconnectBackoff(Random(SEED))

    /** The delays that the reconnect loops asked for, in order. */
    private val delays = MutableStateFlow<List<Duration>>(emptyList())
    private val machines = mutableListOf<HostMachine>()
    private var backend: RealSshBackend? = null

    @AfterTest
    fun tearDown() {
        machines.forEach { machine -> backend?.disconnect(machine.id) }
        server.close()
        bastions.forEach(TestSshServer::close)
    }

    @Test
    fun aStoppedServerReconnectsWithTheBackoffAfterTheServerStartsAgain() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("reconnect-stopped-server")
        assertTrue(transport.connect(machine), debugLogDump())
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[machine.id])

        server.stop()

        awaitValue(stateManager.roamingState, "RoamingReconnect") { it[machine.id] == RoamingStatus.RoamingReconnect }
        val firstDelays = awaitValue(delays, "three failed attempts") { it.size >= 3 }.take(3)
        firstDelays.zip(listOf(1, 2, 4)).forEach { (delay, base) ->
            assertTrue(delay >= base.seconds && delay <= base.seconds * 1.25, "The delay $delay is not near $base s")
        }
        // The third attempt came after the first two delays, in virtual time.
        assertTrue(testScheduler.currentTime >= (firstDelays[0] + firstDelays[1]).inWholeMilliseconds)
        assertTrue(stateManager.isMachineConnected(machine.id), "The loop must keep the machine connected")
        assertNull(stateManager.needsUser.value[machine.id])

        server.start()

        awaitValue(stateManager.roamingState, "Healthy") { it[machine.id] == RoamingStatus.Healthy }
        assertEquals("back", runCommand(machine, "echo back"))
        assertTrue(debugLog.entries.value.any { it.message == "The session is open again" }, debugLogDump())
    }

    @Test
    fun aWrongPasswordAtTheReconnectStopsTheLoopAfterOneAttempt() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("reconnect-wrong-password")
        assertTrue(transport.connect(machine), debugLogDump())
        // The password on the host changed, but the app has the old password.
        secretStore.put(PASSWORD_REF, "not the password".encodeToByteArray())

        server.closeSessions()

        awaitFailed(machine)
        assertIs<NeedsUserCause.LoginRefused>(stateManager.needsUser.value[machine.id])
        assertFalse(stateManager.isMachineConnected(machine.id))
        assertTrue(delays.value.isEmpty(), "The loop waited for a new attempt: ${delays.value}")
        val checks = server.credentialChecks
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(checks, server.credentialChecks, "The loop tried the login again")
        assertFalse(backend().client.hasLiveSession(machine.id))
    }

    @Test
    fun aLockedKeyAtTheReconnectStopsTheLoopWithTheUserAuthCause() = runTest {
        val transport = startTransport()
        val machine = server.machine(
            id = "reconnect-locked-key",
            username = TestSshServer.KEY_USER,
            authMethod = server.keyAuthMethod,
        ).also { machines += it }
        assertTrue(transport.connect(machine), debugLogDump())
        // The time after the user authentication ended, so the key is locked again.
        signError = UserNotAuthenticatedException()

        server.closeSessions()

        awaitFailed(machine)
        assertEquals(NeedsUserCause.UserAuthRequired(TestSshServer.KEY_ALIAS), stateManager.needsUser.value[machine.id])
        assertFalse(stateManager.isMachineConnected(machine.id))
        assertTrue(delays.value.isEmpty(), "The loop waited for a new attempt: ${delays.value}")
    }

    @Test
    fun aChangedHostKeyAtTheReconnectStopsTheLoopAndPutsThePrompt() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("reconnect-changed-host-key")
        val saved = server.knownHost()
        assertTrue(transport.connect(machine), debugLogDump())
        server.hostKey = TestSshServer.newHostKey()
        val checks = server.credentialChecks

        server.closeSessions()

        awaitFailed(machine)
        val prompt = assertIs<HostKeyPrompt.Changed>(stateManager.hostKeyPrompts.value[machine.id])
        assertEquals(saved, prompt.known)
        assertEquals(server.knownHost(), prompt.presented)
        assertEquals(NeedsUserCause.HostKeyChanged(saved.hostPort), stateManager.needsUser.value[machine.id])
        assertTrue(delays.value.isEmpty(), "The loop waited for a new attempt: ${delays.value}")
        assertEquals(checks, server.credentialChecks, "The client sent credentials to a changed host key")
    }

    @Test
    fun aStoppedBastionReconnectsThroughTheBastion() = runTest {
        val bastion = TestSshServer(keyManager = keyManager, allowTcpForwarding = true, name = BASTION).start()
        bastions += bastion
        knownHostsStore.put(bastion.knownHost())
        val transport = startTransport()
        val machine = passwordMachine("reconnect-bastion").copy(
            proxyJump = ProxyJumpConfig(
                bastionHost = TestSshServer.HOST,
                bastionPort = bastion.port,
                bastionUser = TestSshServer.PASSWORD_USER,
            ),
        )
        assertTrue(transport.connect(machine), debugLogDump())
        assertEquals(TARGET, serverNameOf(machine))

        bastion.stop()

        awaitValue(stateManager.roamingState, "RoamingReconnect") { it[machine.id] == RoamingStatus.RoamingReconnect }
        awaitValue(delays, "two failed attempts") { it.size >= 2 }
        bastion.start()

        awaitValue(stateManager.roamingState, "Healthy") { it[machine.id] == RoamingStatus.Healthy }
        assertEquals(TARGET, serverNameOf(machine))
        assertTrue(backend().client.hasLiveSession(machine.id))
    }

    @Test
    fun aLostNetworkClosesTheSessionAndHoldsTheLoopUntilANetworkComes() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("reconnect-network-lost")
        assertTrue(transport.connect(machine), debugLogDump())
        val checks = server.credentialChecks

        transport.onNetworkLost()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(RoamingStatus.RoamingReconnect, stateManager.roamingState.value[machine.id])
        assertFalse(backend().client.hasLiveSession(machine.id), "The sockets did not close")
        // No attempt starts while the device has no network.
        assertEquals(
            SessionSupervisor.State.Reconnecting(failures = 0, attemptRunning = false),
            backend().client.reconnectState(machine.id),
        )
        assertEquals(checks, server.credentialChecks, "The loop tried a login with no network")

        transport.onNetworkAvailable()

        awaitValue(stateManager.roamingState, "Healthy") { it[machine.id] == RoamingStatus.Healthy }
        assertEquals(LivenessResult.Live, backend().checkLiveness(machine.id, SshTransportManager.LIVENESS_TIMEOUT))
        assertEquals("ok", runCommand(machine, "echo ok"))
    }

    @Test
    fun aFailedCheckOfAnOldSessionDoesNotCloseTheNewSession() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("reconnect-old-check")
        assertTrue(transport.connect(machine), debugLogDump())
        val oldId = backend().client.liveSessionId(machine.id) ?: fail("No session after the connect")

        // The loop opens a new session after the host closes the old session.
        server.closeSessions()
        val newId = awaitCondition("a new session") {
            backend().client.liveSessionId(machine.id)?.takeIf { it != oldId }
        }
        awaitValue(stateManager.roamingState, "Healthy") { it[machine.id] == RoamingStatus.Healthy }

        // A check of the old session failed, and its result comes now.
        backend().reconnectNow(machine.id, deadSessionId = oldId)

        assertEquals(SessionSupervisor.State.Live, backend().client.reconnectState(machine.id))
        assertEquals(newId, backend().client.liveSessionId(machine.id))
        assertEquals("ok", runCommand(machine, "echo ok"))
    }

    @Test
    fun aCommandAfterADisconnectOpensNoSession() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("disconnect-no-session")
        assertTrue(transport.connect(machine), debugLogDump())
        transport.disconnect(machine.id)
        val checks = server.credentialChecks

        assertFailsWith<SessionUnavailableException> { runCommand(machine, "echo not reached") }

        assertFalse(backend().client.hasLiveSession(machine.id))
        assertEquals(checks, server.credentialChecks, "The command opened a session")
    }

    @Test
    fun theLivenessCheckPassesOnAWorkingSessionAndFailsWithNoSession() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("liveness")
        assertTrue(transport.connect(machine), debugLogDump())

        assertEquals(
            LivenessResult.Live,
            backend().checkLiveness(machine.id, SshTransportManager.LIVENESS_TIMEOUT),
            debugLogDump(),
        )

        server.stop()
        awaitValue(stateManager.roamingState, "RoamingReconnect") { it[machine.id] == RoamingStatus.RoamingReconnect }
        assertIs<LivenessResult.Dead>(backend().checkLiveness(machine.id, SshTransportManager.LIVENESS_TIMEOUT))
    }

    /** Makes the client, the backend, and the transport. The reconnect loops run in the background scope. */
    private fun TestScope.startTransport(): SshTransportManager {
        val client = RealHerdrSshClient(
            sshDebugLog = debugLog,
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = SemanticNotificationBus(),
            knownHostsStore = knownHostsStore,
            keystoreKeyManagerProvider = { keyManager },
            secretStore = secretStore,
            reconnectScope = backgroundScope,
            reconnectDelays = { failures ->
                backoff.delayAfter(failures).also { delay -> delays.update { it + delay } }
            },
        )
        val realBackend = RealSshBackend(client = client, sshStateManager = stateManager, keystoreKeyManager = keyManager)
        backend = realBackend
        return SshTransportManager(
            backend = realBackend,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
    }

    private fun backend(): RealSshBackend = backend ?: fail("The test did not start the transport")

    private fun passwordMachine(id: String): HostMachine =
        server.machine(
            id = id,
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(PASSWORD_REF)),
        ).also { machines += it }

    private suspend fun runCommand(machine: HostMachine, command: String): String =
        backend().client.runCommand(machine, ProxyJumpTunnel().resolveRoute(machine), command).trim()

    /** Returns the name of the server that runs the commands of [machine]. */
    private suspend fun serverNameOf(machine: HostMachine): String =
        runCommand(machine, "echo \$${TestSshServer.NAME_VARIABLE}")

    /** Waits until the connection of [machine] stopped. The manager sets [ConnectionProgress.Failed] last. */
    private suspend fun awaitFailed(machine: HostMachine) {
        awaitValue(stateManager.connectionProgress, "ConnectionProgress.Failed") {
            it[machine.id] == ConnectionProgress.Failed
        }
    }

    /**
     * Waits a maximum of [AWAIT_TIMEOUT] in real time until [predicate] is true for the value of [flow]. The virtual
     * time of the test continues while it waits.
     */
    private suspend fun <T> awaitValue(flow: StateFlow<T>, description: String, predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(AWAIT_TIMEOUT) { flow.first(predicate) }
        } ?: fail("Timeout: $description\n${debugLogDump()}")

    /**
     * Waits a maximum of [AWAIT_TIMEOUT] in real time until [value] gives a result that is not null, and returns it.
     * Use it for a state that is not in a flow.
     */
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

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val TARGET = "target"
        const val BASTION = "bastion"
        const val PASSWORD_REF = "password-reconnect"
        const val SEED = 10
        val AWAIT_TIMEOUT: Duration = 20.seconds
        val AWAIT_STEP: Duration = 20.milliseconds
    }
}
