package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.net.ConnectException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Tests [RealSshBackend] and [RealHerdrSshClient] against a real SSH server ([TestSshServer]).
 *
 * The known hosts have the host key of the server, so each login gets to the authentication step.
 * [HostKeyCheckTest] tests the host key check.
 */
class RealSshClientTest {

    private val debugLog = SshDebugLog()
    private val server = TestSshServer().start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val backend = RealSshBackend(
        sshStateManager = SshStateManager(),
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
    )
    private val machines = mutableListOf<HostMachine>()

    @AfterTest
    fun tearDown() {
        machines.forEach { backend.disconnect(it.id) }
        server.close()
    }

    @Test
    fun passwordLoginRunsACommand() = runTest {
        val machine = machine(
            id = "password-login",
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = TestSshServer.PASSWORD),
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route), debugLogDump())
        assertEquals("password login ok", backend.client.runCommand(machine, route, "echo password login ok").trim())
    }

    @Test
    fun publicKeyLoginWithAKeyManagerKeyRunsACommand() = runTest {
        val machine = machine(
            id = "key-login",
            username = TestSshServer.KEY_USER,
            authMethod = server.keyAuthMethod,
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route), debugLogDump())
        assertEquals("key login ok", backend.client.runCommand(machine, route, "echo key login ok").trim())
    }

    @Test
    fun wrongPasswordFailsWithAnAuthError() = runTest {
        val machine = machine(
            id = "wrong-password",
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = "not the password"),
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)

        val result = assertIs<SshAuthResult.AuthFailed>(backend.authenticate(machine, route))
        assertTrue(result.message.startsWith(AUTH_FAIL), result.message)
        assertTrue(
            debugLog.entries.value.any { it.isError && it.message.contains(AUTH_FAIL) },
            debugLogDump(),
        )
        val error = assertFailsWith<JSchException> {
            backend.client.runCommand(machine, route, "echo not reached")
        }
        assertTrue(error.message.orEmpty().startsWith(AUTH_FAIL), error.message)
    }

    @Test
    fun stoppedServerGivesANetworkError() = runTest {
        val machine = machine(
            id = "stopped-server",
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = TestSshServer.PASSWORD),
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)
        server.stop()

        val result = assertIs<SshAuthResult.NetworkError>(backend.authenticate(machine, route), debugLogDump())
        assertIs<ConnectException>(generateSequence(result.cause) { it.cause }.last())
    }

    @Test
    fun otherLoginErrorsAreFailed() {
        val error = JSchException("Algorithm negotiation fail")

        assertEquals(SshAuthResult.Failed(error), RealSshBackend.toAuthResult(error))
    }

    @Test
    fun keyLoginWithAMissingKeyGivesKeyMissingAndMakesNoKey() = runTest {
        val machine = machine(
            id = "missing-key",
            username = TestSshServer.KEY_USER,
            authMethod = SshAuthMethod.KeystoreKey(keyAlias = MISSING_ALIAS, algorithm = server.keyAlgorithm),
        )
        val route = ProxyJumpTunnel().resolveRoute(machine)

        val result = assertIs<SshAuthResult.KeyMissing>(backend.authenticate(machine, route), debugLogDump())

        assertEquals(MISSING_ALIAS, result.keyAlias)
        assertEquals(
            "The key $MISSING_ALIAS does not exist. Open the machine settings and copy the public key.",
            result.message,
        )
        assertTrue(debugLog.entries.value.any { it.isError && it.message.contains(result.message) }, debugLogDump())
        assertFailsWith<MissingKeyException> {
            server.keyManager.getExistingPublicKey(MISSING_ALIAS, server.keyAlgorithm)
        }
    }

    @Test
    fun aMissingKeyInsideAJschErrorIsKeyMissing() {
        val error = JSchException("Session.connect: key error", MissingKeyException(MISSING_ALIAS))

        assertEquals(SshAuthResult.KeyMissing(MISSING_ALIAS), RealSshBackend.toAuthResult(error))
    }

    @Test
    fun jschPassesAMissingKeyErrorFromTheSignatureThrough() {
        // The server knows the public key, so JSch asks the identity for a signature.
        val knownKey = AndroidKeystoreJschIdentity(server.keyManager, TestSshServer.KEY_ALIAS, server.keyAlgorithm)
        val session = JSch().apply { addIdentity(SignFailsIdentity(knownKey), null) }
            .getSession(TestSshServer.KEY_USER, TestSshServer.HOST, server.port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
        JschAndroidCrypto.configureSession(session)

        val error = assertFailsWith<MissingKeyException> { session.connect(CONNECT_TIMEOUT_MS) }

        assertEquals(TestSshServer.KEY_ALIAS, error.keyAlias)
        assertFalse(session.isConnected)
    }

    /** Gives the public key of [publicKey], but its sign fails as if the key store lost the private key. */
    private class SignFailsIdentity(private val publicKey: Identity) : Identity {
        override fun setPassphrase(passphrase: ByteArray?): Boolean = true

        override fun getPublicKeyBlob(): ByteArray = publicKey.publicKeyBlob

        override fun getSignature(data: ByteArray?): ByteArray = throw MissingKeyException(TestSshServer.KEY_ALIAS)

        override fun getSignature(data: ByteArray?, alg: String?): ByteArray =
            throw MissingKeyException(TestSshServer.KEY_ALIAS)

        @Deprecated("Deprecated in JSch Identity")
        override fun decrypt(): Boolean = true

        override fun getAlgName(): String = publicKey.algName

        override fun getName(): String = "sign-fails"

        override fun isEncrypted(): Boolean = false

        override fun clear() = Unit
    }

    private fun machine(id: String, username: String, authMethod: SshAuthMethod): HostMachine =
        server.machine(id = id, username = username, authMethod = authMethod).also { machines += it }

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        /** JSch starts the message of a failed login with this text. */
        const val AUTH_FAIL = "Auth fail"

        /** A key alias that the key manager does not have. */
        const val MISSING_ALIAS = "real_ssh_client_test_missing_key"
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
