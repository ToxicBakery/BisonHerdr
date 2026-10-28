package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSch
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.yubico.yubikit.core.Transport
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Tests the login with a FIDO2 security key against a real SSH server ([TestSshServer], Apache MINA SSHD). The key
 * is a software FIDO2 key behind a fake CTAPHID connection ([FakeSecurityKey]), so the test runs the real yubikit
 * `Ctap2Session`, [YubiKeyFido2Signer], [HardwareKeyJschIdentity], and JSch. The server parses the `authorized_keys`
 * line of the enrollment and verifies the `sk` signature with its own code.
 *
 * The tests use [runBlocking], not `runTest`. The key comes from a host on a real thread, so each time limit of the
 * signer must use real time. With virtual time, a late host thread lets the time limit pass at once.
 */
class HardwareKeyLoginTest {

    private val debugLog = SshDebugLog()
    private val stateManager = SshStateManager()
    private val server = TestSshServer().start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val hub = SecurityKeyHub()
    private val key = FakeSecurityKey()
    private val signer = YubiKeyFido2Signer(hub = hub, sshStateManager = stateManager, sshDebugLog = debugLog)
    private val checkingSigner = CheckingSigner(signer)
    private val backend = RealSshBackend(
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = softwareKeySecretStore(),
        localNetworkGuard = permittedLocalNetwork(),
        hardwareKeySigner = checkingSigner,
    )
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machines = mutableListOf<HostMachine>()

    @AfterTest
    fun tearDown() {
        machines.forEach { backend.disconnect(it.id) }
        hostScope.cancel()
        server.close()
    }

    @Test
    fun anEnrolledEd25519KeyLogsInAndRunsACommand() = runBlocking<Unit> {
        loginWithAnEnrolledKey(HardwareKeyType.SkSshEd25519)
    }

    @Test
    fun anEnrolledP256KeyLogsInAndRunsACommand() = runBlocking<Unit> {
        loginWithAnEnrolledKey(HardwareKeyType.SkEcdsaSha2Nistp256)
    }

    @Test
    fun aProxyJumpLoginTellsTheBastionForTheFirstTouch() = runBlocking<Unit> {
        TestSshServer(allowTcpForwarding = true, name = "bastion").start().use { bastion ->
            knownHostsStore.put(bastion.knownHost())
            startHost()
            val enrolled = signer.enroll("jump", HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
            val line = SecurityKeyPublicKeys.authorizedKeyLine(enrolled)
            server.authorizeKey(line)
            bastion.authorizeKey(line)
            val machine = machine("jump", enrolled).copy(
                proxyJump = ProxyJumpConfig(
                    bastionHost = TestSshServer.HOST,
                    bastionPort = bastion.port,
                    bastionUser = TestSshServer.KEY_USER,
                ),
            ).also { machines += it }

            val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

            assertEquals(SshAuthResult.Success, result, debugLogDump())
            // Each session has its own identity: the first touch is for the bastion, the second for the machine.
            assertEquals(
                listOf(KnownHost.formatHostPort(TestSshServer.HOST, bastion.port), null),
                checkingSigner.bastionHostPorts.toList(),
            )
            backend.disconnect(machine.id)
        }
    }

    @Test
    fun aKeyThatTheServerDoesNotKnowNeedsNoTouch() = runBlocking<Unit> {
        startHost()
        val enrolled = signer.enroll("unknown-key", HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        val machine = machine("unknown-key", enrolled)

        val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

        assertIs<SshAuthResult.AuthFailed>(result, debugLogDump())
        // JSch asks the server first, and the server refuses the key. Thus the key gets no getAssertion request.
        assertTrue(key.commands.none { it == CTAP_GET_ASSERTION }, "The key got ${key.commands}")
    }

    @Test
    fun withNoActivityTheLoginFailsAndTheReconnectLoopCanStop() = runBlocking<Unit> {
        val host = startHost()
        val enrolled = signer.enroll("no-ui", HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        server.authorizeKey(SecurityKeyPublicKeys.authorizedKeyLine(enrolled))
        // The activity goes to the background.
        host.cancelAndJoin()
        val machine = machine("no-ui", enrolled)

        val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

        // Failed is not a NetworkError, so the reconnect loop stops and waits for the user.
        val failed = assertIs<SshAuthResult.Failed>(result, debugLogDump())
        assertEquals(HardwareKeyException.Reason.NoUi, assertIs<HardwareKeyException>(failed.cause).reason)
    }

    @Test
    fun aMachineWithNoEnrolledKeyFailsBeforeTheConnection() = runBlocking<Unit> {
        val machine = machine("not-enrolled", SshAuthMethod.HardwareSecurityKey())

        val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

        val failed = assertIs<SshAuthResult.Failed>(result, debugLogDump())
        assertEquals(HardwareKeyException.Reason.NotEnrolled, assertIs<HardwareKeyException>(failed.cause).reason)
        assertEquals(0, server.credentialChecks, "The client sent credentials")
    }

    @Test
    fun onlyTheSecurityKeySessionAcceptsTheSkAlgorithms() {
        val globalDefaults = JSch.getConfig("PubkeyAcceptedAlgorithms")
        val session = JSch().getSession("user", TestSshServer.HOST, server.port)
        val defaults = session.getConfig("PubkeyAcceptedAlgorithms")

        JschAndroidCrypto.enableSecurityKeyAlgorithms(session)
        JschAndroidCrypto.enableSecurityKeyAlgorithms(session)

        val accepted = session.getConfig("PubkeyAcceptedAlgorithms").split(',')
        assertEquals(defaults.split(',') + JschAndroidCrypto.SECURITY_KEY_ALGORITHMS, accepted)
        assertEquals(
            listOf("sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com"),
            JschAndroidCrypto.SECURITY_KEY_ALGORITHMS,
        )
        // The global configuration and the other sessions keep the JSch defaults.
        assertEquals(globalDefaults, JSch.getConfig("PubkeyAcceptedAlgorithms"))
        val otherSession = JSch().getSession("user", TestSshServer.HOST, server.port)
        assertEquals(defaults, otherSession.getConfig("PubkeyAcceptedAlgorithms"))
        for (name in JschAndroidCrypto.SECURITY_KEY_ALGORITHMS) {
            assertNull(JSch.getConfig(name), "JSch has a global class for $name")
        }
    }

    /**
     * Enrolls a key of [keyType] and logs in [LOGIN_COUNT] times. Each login gets a new signature, so that some P-256
     * signatures have an `r` or an `s` with the high bit set.
     */
    private suspend fun loginWithAnEnrolledKey(keyType: HardwareKeyType) {
        startHost()
        val enrolled = signer.enroll(keyType.name, keyType, HardwareKeyTransport.UsbAndNfc, "ssh:")
        server.authorizeKey(SecurityKeyPublicKeys.authorizedKeyLine(enrolled))
        val machine = machine("security-key-${keyType.name}", enrolled)
        val route = ProxyJumpTunnel().resolveRoute(machine)

        repeat(LOGIN_COUNT) { index ->
            val result = backend.authenticate(machine, route)
            assertTrue(checkingSigner.errors.isEmpty(), "login $index: ${checkingSigner.errors}")
            assertEquals(SshAuthResult.Success, result, "login $index\n${debugLogDump()}")
            assertEquals("sk login ok", backend.client.runCommand(machine, route, "echo sk login ok").trim())
            backend.disconnect(machine.id)
        }
        assertEquals(LOGIN_COUNT, key.commands.count { it == CTAP_GET_ASSERTION }, "Expected one touch for each login")
        assertTrue(debugLogDump().contains("The security key signed"), debugLogDump())
    }

    /** Starts a host of [hub] that gives the USB key of [key], as the visible activity does. */
    private fun startHost(): Job {
        val discovery = object : SecurityKeyDiscovery {
            override val transport: Transport = Transport.USB

            override fun start() = hub.onUsbDevice(key.device())

            override fun stop() = Unit
        }
        return hostScope.launch(start = CoroutineStart.UNDISPATCHED) { hub.hostDiscovery(discovery) }
    }

    /**
     * Checks each blob of [delegate] as an OpenSSH server does ([OpenSshSkSignatureCheck]), before JSch sends it.
     * MINA SSHD accepts some blobs that OpenSSH refuses, for example a negative `mpint`.
     */
    private class CheckingSigner(private val delegate: HardwareKeySigner) : HardwareKeySigner by delegate {
        val errors: MutableList<AssertionError> = CopyOnWriteArrayList()

        /** The bastion `host:port` of each sign call, in order. Null is a sign call for the machine. */
        val bastionHostPorts: MutableList<String?> = CopyOnWriteArrayList()

        override suspend fun sign(
            machineId: String,
            key: SshAuthMethod.HardwareSecurityKey,
            data: ByteArray,
            cancelMark: Long?,
            bastionHostPort: String?,
        ): ByteArray {
            bastionHostPorts += bastionHostPort
            val blob = delegate.sign(machineId, key, data, cancelMark, bastionHostPort)
            try {
                OpenSshSkSignatureCheck.check(key, data, blob)
            } catch (error: AssertionError) {
                errors += error
                throw HardwareKeyException(HardwareKeyException.Reason.Failed, error.message, error)
            }
            return blob
        }
    }

    private fun machine(id: String, authMethod: SshAuthMethod): HostMachine =
        server.machine(id = id, username = TestSshServer.KEY_USER, authMethod = authMethod).also { machines += it }

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val CTAP_GET_ASSERTION = 0x02
        const val LOGIN_COUNT = 8
    }
}
