package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSch
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
import kotlinx.coroutines.test.runTest

/**
 * Tests the login with a FIDO2 security key against a real SSH server ([TestSshServer], Apache MINA SSHD). The key
 * is a software FIDO2 key behind a fake CTAPHID connection ([FakeSecurityKey]), so the test runs the real yubikit
 * `Ctap2Session`, [YubiKeyFido2Signer], [HardwareKeyJschIdentity], and JSch. The server parses the `authorized_keys`
 * line of the enrollment and verifies the `sk` signature with its own code.
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
    private val backend = RealSshBackend(
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = softwareKeySecretStore(),
        hardwareKeySigner = signer,
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
    fun anEnrolledEd25519KeyLogsInAndRunsACommand() = runTest {
        loginWithAnEnrolledKey(HardwareKeyType.SkSshEd25519)
    }

    @Test
    fun anEnrolledP256KeyLogsInAndRunsACommand() = runTest {
        loginWithAnEnrolledKey(HardwareKeyType.SkEcdsaSha2Nistp256)
    }

    @Test
    fun aKeyThatTheServerDoesNotKnowNeedsNoTouch() = runTest {
        startHost()
        val enrolled = signer.enroll("unknown-key", HardwareKeyType.SkSshEd25519, HardwareKeyTransport.UsbAndNfc, "ssh:")
        val machine = machine("unknown-key", enrolled)

        val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

        assertIs<SshAuthResult.AuthFailed>(result, debugLogDump())
        // JSch asks the server first, and the server refuses the key. Thus the key gets no getAssertion request.
        assertTrue(key.commands.none { it == CTAP_GET_ASSERTION }, "The key got ${key.commands}")
    }

    @Test
    fun withNoActivityTheLoginFailsAndTheReconnectLoopCanStop() = runTest {
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
    fun aMachineWithNoEnrolledKeyFailsBeforeTheConnection() = runTest {
        val machine = machine("not-enrolled", SshAuthMethod.HardwareSecurityKey())

        val result = backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

        val failed = assertIs<SshAuthResult.Failed>(result, debugLogDump())
        assertEquals(HardwareKeyException.Reason.NotEnrolled, assertIs<HardwareKeyException>(failed.cause).reason)
        assertEquals(0, server.credentialChecks, "The client sent credentials")
    }

    @Test
    fun theSessionAcceptsTheSkAlgorithmsAndThePlaceholderLoads() {
        val session = JSch().getSession("user", TestSshServer.HOST, server.port)
        val defaults = session.getConfig("PubkeyAcceptedAlgorithms")

        JschAndroidCrypto.enableSecurityKeyAlgorithms(session)
        JschAndroidCrypto.enableSecurityKeyAlgorithms(session)

        val accepted = session.getConfig("PubkeyAcceptedAlgorithms").split(',')
        assertEquals(defaults.split(',') + JschAndroidCrypto.SECURITY_KEY_ALGORITHMS, accepted)
        for (name in JschAndroidCrypto.SECURITY_KEY_ALGORITHMS) {
            // The same check as Session.checkSignatures.
            val signature = Class.forName(JSch.getConfig(name)).asSubclass(com.jcraft.jsch.Signature::class.java)
                .getDeclaredConstructor()
                .newInstance()
            signature.init()
            assertFailsWith<UnsupportedOperationException> { signature.sign() }
        }
        assertEquals(
            listOf("sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com"),
            JschAndroidCrypto.SECURITY_KEY_ALGORITHMS,
        )
    }

    private suspend fun loginWithAnEnrolledKey(keyType: HardwareKeyType) {
        startHost()
        val enrolled = signer.enroll(keyType.name, keyType, HardwareKeyTransport.UsbAndNfc, "ssh:")
        server.authorizeKey(SecurityKeyPublicKeys.authorizedKeyLine(enrolled))
        val machine = machine("security-key-${keyType.name}", enrolled)
        val route = ProxyJumpTunnel().resolveRoute(machine)

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route), debugLogDump())
        assertEquals("sk login ok", backend.client.runCommand(machine, route, "echo sk login ok").trim())
        assertTrue(key.commands.count { it == CTAP_GET_ASSERTION } == 1, "Expected one touch: ${key.commands}")
        assertTrue(debugLogDump().contains("The security key signed"), debugLogDump())
    }

    /** Starts a host of [hub] that gives the USB key of [key], as the activity does in the foreground. */
    private fun startHost(): Job {
        val discovery = object : SecurityKeyDiscovery {
            override fun start() = hub.onUsbDevice(key.device())

            override fun stop() = Unit
        }
        return hostScope.launch(start = CoroutineStart.UNDISPATCHED) { hub.hostDiscovery(discovery) }
    }

    private fun machine(id: String, authMethod: SshAuthMethod): HostMachine =
        server.machine(id = id, username = TestSshServer.KEY_USER, authMethod = authMethod).also { machines += it }

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val CTAP_GET_ASSERTION = 0x02
    }
}
