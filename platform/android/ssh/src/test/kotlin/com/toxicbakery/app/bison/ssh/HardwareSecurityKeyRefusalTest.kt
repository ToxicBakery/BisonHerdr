package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests that the app does not connect a machine with [SshAuthMethod.HardwareSecurityKey]. This version does
 * not support that method. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 keys.
 */
class HardwareSecurityKeyRefusalTest {

    private val debugLog = SshDebugLog()
    private val stateManager = SshStateManager()

    @Test
    fun connectFailsAndDoesNotCallTheBackend() = runTest {
        val backend = RecordingSshBackend()
        val surfaceBus = PaneSurfaceBus()
        val manager = SshTransportManager(
            backend = backend,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
        val machine = hardwareKeyMachine(port = 22)

        assertFalse(manager.connect(machine))

        assertTrue(backend.calls.isEmpty(), "The backend got ${backend.calls}")
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[machine.id])
        assertFalse(stateManager.isMachineConnected(machine.id))
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.machineId == machine.id &&
                    it.message.contains(SshTransportManager.HARDWARE_KEY_NOT_SUPPORTED)
            },
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" },
        )
    }

    @Test
    fun realBackendFailsWithoutANetworkConnection() = runTest {
        // The socket gets the connection in its backlog, also when the test does not call accept.
        ServerSocket(0, 1, InetAddress.getByName(TestSshServer.HOST)).use { listener ->
            val backend = RealSshBackend(
                sshStateManager = stateManager,
                sshDebugLog = debugLog,
                endpointSnapshotBus = EndpointSnapshotBus(),
                paneSurfaceBus = PaneSurfaceBus(),
                semanticNotificationBus = SemanticNotificationBus(),
                keystoreKeyManager = AndroidKeystoreKeyManager(),
            )
            val machine = hardwareKeyMachine(port = listener.localPort)

            val result = assertIs<SshAuthResult.Failed>(
                backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine)),
            )

            val cause = assertIs<UnsupportedOperationException>(result.cause)
            assertEquals(SshTransportManager.HARDWARE_KEY_NOT_SUPPORTED, cause.message)
            listener.soTimeout = NO_CONNECTION_WAIT_MS
            assertFailsWith<SocketTimeoutException> { listener.accept() }
            backend.disconnect(machine.id)
        }
    }

    private fun hardwareKeyMachine(port: Int): HostMachine = HostMachine(
        id = "yubikey-box",
        name = "YubiKey Box",
        hostname = TestSshServer.HOST,
        port = port,
        username = "ian",
        authMethod = SshAuthMethod.HardwareSecurityKey(),
    )

    private companion object {
        /** The time that the test waits for a connection that must not come. */
        const val NO_CONNECTION_WAIT_MS: Int = 250
    }
}
