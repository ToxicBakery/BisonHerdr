package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import java.net.ConnectException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests [RealSshBackend] and [RealHerdrSshClient] against a real SSH server ([TestSshServer]).
 */
class RealSshClientTest {

    private val debugLog = SshDebugLog()
    private val server = TestSshServer().start()
    private val backend = RealSshBackend(
        sshStateManager = SshStateManager(),
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
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

    private fun machine(id: String, username: String, authMethod: SshAuthMethod): HostMachine =
        server.machine(id = id, username = username, authMethod = authMethod).also { machines += it }

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        /** JSch starts the message of a failed login with this text. */
        const val AUTH_FAIL = "Auth fail"
    }
}
