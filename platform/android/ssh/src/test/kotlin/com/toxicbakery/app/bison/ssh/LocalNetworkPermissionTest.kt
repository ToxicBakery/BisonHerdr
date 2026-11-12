package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests the Android 17 local network permission with [RealSshBackend], [RealHerdrSshClient], and
 * [SshTransportManager] against real SSH servers ([TestSshServer]).
 *
 * The servers listen on the loopback address. Android does not stop a connection to a loopback address. Thus the
 * [LocalNetworkGuard] of the test puts `127.0.0.0/8` in the prefixes of the device, in place of a Wi-Fi network. The
 * permission is [permitted]. A connection that the guard stops gets to no server, and [TestSshServer.credentialChecks]
 * stays the same.
 *
 * The reconnect loop runs in the background scope of the test, so its delays use virtual time. The test records each
 * delay in [delays]. The SSH connections use real sockets, so the test waits for each result with a real timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkPermissionTest {

    private val debugLog = SshDebugLog()
    private val keyManager = AndroidKeystoreKeyManager(allowSoftwareKeys = true)
    private val server = TestSshServer(keyManager = keyManager, name = TARGET).start()
    private val bastion = TestSshServer(keyManager = keyManager, allowTcpForwarding = true, name = BASTION).start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking {
            put(server.knownHost())
            put(bastion.knownHost())
            put(bastion.knownHost().copy(host = PUBLIC_BASTION))
        }
    }
    private val secretStore = InMemorySecretStore().apply {
        runBlocking { put(PASSWORD_REF, TestSshServer.PASSWORD.encodeToByteArray()) }
    }
    private val stateManager = SshStateManager()
    private val surfaceBus = PaneSurfaceBus()
    private val localNetworkAccess = LocalNetworkAccess()

    /** True when the app has the local network permission. */
    @Volatile
    private var permitted = false

    /** The prefixes of the device for the guard. The default makes the loopback address local. */
    @Volatile
    private var onLinkPrefixes = listOf(AddressPrefix(InetAddress.getByName("127.0.0.0").address, 8))

    /** The host names that the guard resolved, in order. */
    private val resolved: MutableList<String> = CopyOnWriteArrayList()

    /** The host and port of each request that the UI of [startUi] got, in order. */
    private val shown: MutableList<String> = CopyOnWriteArrayList()

    /** The delays that the reconnect loops asked for, in order. */
    private val delays = MutableStateFlow<List<Duration>>(emptyList())
    private val machines = mutableListOf<HostMachine>()
    private var backend: RealSshBackend? = null

    @AfterTest
    fun tearDown() {
        machines.forEach { machine -> backend?.disconnect(machine.id) }
        server.close()
        bastion.close()
    }

    @Test
    fun aConnectWithNoUiStopsAtOnceWithTheCauseAndOpensNoSocket() = runTest {
        val transport = startTransport()
        val machine = passwordMachine("lan-no-ui")

        assertFalse(transport.connect(machine))

        assertEquals(NeedsUserCause.LocalNetworkPermission(hostPort(server)), stateManager.needsUser.value[machine.id])
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[machine.id])
        assertEquals(0, server.credentialChecks, "The client got to the server")
        assertTrue(
            debugLog.entries.value.any { it.message.contains("LocalNetworkBlocked (${hostPort(server)})") },
            debugLogDump(),
        )
    }

    @Test
    fun aConnectFromTheAppAsksTheUiAndConnectsAfterTheGrant() = runTest {
        val transport = startTransport()
        startUi {
            permitted = true
            LocalNetworkOutcome.Granted
        }
        val machine = passwordMachine("lan-grant")

        assertTrue(transport.connect(machine), debugLogDump())

        assertEquals(listOf(hostPort(server)), shown)
        assertNull(stateManager.needsUser.value[machine.id])
        assertEquals(TARGET, serverNameOf(machine))
    }

    @Test
    fun aDenialStopsTheConnectAndAConnectAfterTheGrantWorks() = runTest {
        val transport = startTransport()
        startUi { LocalNetworkOutcome.Denied }
        val machine = passwordMachine("lan-deny")

        assertFalse(transport.connect(machine))

        assertEquals(NeedsUserCause.LocalNetworkPermission(hostPort(server)), stateManager.needsUser.value[machine.id])
        assertFalse(stateManager.isMachineConnected(machine.id))
        assertEquals(0, server.credentialChecks, "The client got to the server")
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(0, server.credentialChecks, "The app tried the connection again")
        assertTrue(delays.value.isEmpty(), "A reconnect loop waited for a new attempt: ${delays.value}")

        // The user allows the permission in the app settings, and connects again.
        permitted = true
        assertTrue(transport.connect(machine), debugLogDump())

        assertNull(stateManager.needsUser.value[machine.id])
        assertEquals(TARGET, serverNameOf(machine))
    }

    @Test
    fun aReconnectWithoutThePermissionStopsAfterOneAttemptAndDoesNotAsk() = runTest {
        val transport = startTransport()
        permitted = true
        val machine = passwordMachine("lan-reconnect")
        assertTrue(transport.connect(machine), debugLogDump())
        // A UI is open, but a reconnect never asks: only a connect from the user asks.
        startUi { LocalNetworkOutcome.Granted }
        val checks = server.credentialChecks

        // The app is in the background, and the host is now on the local network with no permission.
        permitted = false
        server.closeSessions()

        awaitFailed(machine)
        assertEquals(NeedsUserCause.LocalNetworkPermission(hostPort(server)), stateManager.needsUser.value[machine.id])
        assertFalse(stateManager.isMachineConnected(machine.id))
        assertTrue(shown.isEmpty(), "The reconnect asked the UI: $shown")
        assertTrue(delays.value.isEmpty(), "The loop waited for a new attempt: ${delays.value}")
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(checks, server.credentialChecks, "The loop tried the connection again")
        assertFalse(backend().client.hasLiveSession(machine.id))

        // A network change does not start the loop again.
        transport.onNetworkAvailable()
        assertEquals(checks, server.credentialChecks, "The network check tried the connection again")

        // In the foreground, the user allows the permission and connects again.
        permitted = true
        assertTrue(transport.connect(machine), debugLogDump())
        assertNull(stateManager.needsUser.value[machine.id])
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[machine.id])
    }

    @Test
    fun aBastionOnTheLocalNetworkNeedsThePermission() = runTest {
        val transport = startTransport()
        val machine = bastionMachine("lan-bastion", bastionHost = TestSshServer.HOST)

        assertFalse(transport.connect(machine))

        // The device connects only to the bastion. The bastion opens the connection to the target.
        assertEquals(NeedsUserCause.LocalNetworkPermission(hostPort(bastion)), stateManager.needsUser.value[machine.id])
        assertEquals(0, bastion.credentialChecks, "The client got to the bastion")
        assertEquals(0, server.credentialChecks, "The client got to the target")
    }

    @Test
    fun theDeviceChecksOnlyTheBastionAndNotTheTargetBehindIt() = runTest {
        // The bastion opens the connection to the target. Thus a target on the local network of the bastion needs no
        // permission on the device, and the guard never gets the target.
        onLinkPrefixes = emptyList()
        val transport = startTransport(
            resolve = { host ->
                if (host == PUBLIC_BASTION) listOf(PUBLIC_BASTION_ADDRESS) else InetAddress.getAllByName(host).toList()
            },
        )
        val machine = bastionMachine("public-bastion", bastionHost = PUBLIC_BASTION)

        assertTrue(transport.connect(machine), debugLogDump())

        assertEquals(TARGET, serverNameOf(machine))
        assertEquals(setOf(PUBLIC_BASTION), resolved.toSet())
    }

    @Test
    fun aTimeoutToAPrivateAddressAsksAndTriesAgainAfterTheGrant() = runTest {
        val batches = CopyOnWriteArrayList<String>()
        val backend = timeoutBackend(batches)
        startUi {
            permitted = true
            LocalNetworkOutcome.Granted
        }
        val machine = passwordMachine("routed-grant")

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine)))

        assertEquals(listOf(ROUTED_HOST_PORT), shown)
        assertEquals(listOf("timeout", "ok"), batches)
    }

    @Test
    fun aTimeoutToAPrivateAddressWithNoUiStopsWithTheTimeoutKind() = runTest {
        val batches = CopyOnWriteArrayList<String>()
        val backend = timeoutBackend(batches)
        val machine = passwordMachine("routed-no-ui")

        val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

        assertEquals(SshAuthResult.LocalNetworkBlocked(ROUTED_HOST_PORT, timedOut = true), result)
        assertEquals(listOf("timeout"), batches)
    }

    /**
     * Returns a backend whose client runs no real command. Its first batch fails as a connect timeout to a private
     * address, and each later batch passes. [batches] gets the result of each batch.
     */
    private fun timeoutBackend(batches: MutableList<String>): RealSshBackend {
        // The loopback address is not local here, so the check before the connect does not ask.
        onLinkPrefixes = emptyList()
        val client = RealHerdrSshClient(
            sshDebugLog = debugLog,
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = surfaceBus,
            semanticNotificationBus = SemanticNotificationBus(),
            knownHostsStore = knownHostsStore,
            keystoreKeyManagerProvider = { keyManager },
            secretStore = secretStore,
            remoteBatchExecutor = { _, _, _, _ ->
                if (batches.isEmpty()) {
                    batches += "timeout"
                    throw LocalNetworkPermissionException(ROUTED_HOST_PORT, timedOut = true)
                }
                batches += "ok"
                "herdr 0.1.0"
            },
            localNetworkGuard = LocalNetworkGuard(hasPermission = { permitted }, localPrefixes = { onLinkPrefixes }),
        )
        return RealSshBackend(
            client = client,
            sshStateManager = stateManager,
            keystoreKeyManager = keyManager,
            localNetworkAccess = localNetworkAccess,
        ).also { backend = it }
    }

    /**
     * Makes the client, the backend, and the transport. The reconnect loops run in the background scope. The guard
     * resolves a host with [resolve], or with the system resolver.
     */
    private fun TestScope.startTransport(
        resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): SshTransportManager {
        val guard = LocalNetworkGuard(
            hasPermission = { permitted },
            localPrefixes = { onLinkPrefixes },
            resolve = { host ->
                resolved += host
                resolve(host)
            },
        )
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
                ReconnectBackoff().delayAfter(failures).also { delay -> delays.update { it + delay } }
            },
            localNetworkGuard = guard,
        )
        val realBackend = RealSshBackend(
            client = client,
            sshStateManager = stateManager,
            keystoreKeyManager = keyManager,
            localNetworkAccess = localNetworkAccess,
        )
        backend = realBackend
        return SshTransportManager(
            backend = realBackend,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
            foregroundState = ForegroundState.ALWAYS,
        )
    }

    /** Starts a UI that records each request and answers with [answer]. */
    private fun TestScope.startUi(answer: suspend () -> LocalNetworkOutcome) {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            localNetworkAccess.hostRequests { request ->
                shown += request.hostPort
                answer()
            }
        }
    }

    private fun backend(): RealSshBackend = backend ?: fail("The test did not start the transport")

    private fun passwordMachine(id: String): HostMachine =
        server.machine(
            id = id,
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(PASSWORD_REF)),
        ).also { machines += it }

    /** Returns a password machine on [server] that connects through [bastion] at [bastionHost]. */
    private fun bastionMachine(id: String, bastionHost: String): HostMachine =
        server.machine(
            id = id,
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(PASSWORD_REF)),
        ).copy(
            proxyJump = ProxyJumpConfig(
                bastionHost = bastionHost,
                bastionPort = bastion.port,
                bastionUser = TestSshServer.PASSWORD_USER,
            ),
        ).also { machines += it }

    private fun hostPort(target: TestSshServer): String = KnownHost.formatHostPort(TestSshServer.HOST, target.port)

    /** Returns the name of the server that runs the commands of [machine]. */
    private suspend fun serverNameOf(machine: HostMachine): String =
        backend().client.runCommand(
            machine,
            ProxyJumpTunnel().resolveRoute(machine),
            "echo \$${TestSshServer.NAME_VARIABLE}",
        ).trim()

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

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val TARGET = "target"
        const val BASTION = "bastion"
        const val PASSWORD_REF = "password-local-network"

        /** The host and port of a host on a private subnet behind a router. */
        const val ROUTED_HOST_PORT = "192.168.2.10:22"

        /** A bastion name that the guard of the test resolves to [PUBLIC_BASTION_ADDRESS]. */
        const val PUBLIC_BASTION = "bastion.example"
        val PUBLIC_BASTION_ADDRESS: InetAddress = InetAddress.getByName(TestSshServer.HOST)
        val AWAIT_TIMEOUT: Duration = 20.seconds
    }
}
