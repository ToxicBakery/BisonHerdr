package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests the parts of [SshTransportManager] that do not depend on the backend type.
 */
class SshTransportManagerTest {

    private val debugLog = SshDebugLog()
    private val stateManager = SshStateManager()

    private fun manager(backend: SshBackend): SshTransportManager {
        val surfaceBus = PaneSurfaceBus()
        return SshTransportManager(
            backend = backend,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
    }

    @Test
    fun connectWithoutAProfileFailsAndDoesNotCallTheBackend() = runTest {
        val backend = RecordingSshBackend()

        assertFalse(manager(backend).connect("unknown-box"))

        assertTrue(backend.calls.isEmpty(), "The backend got ${backend.calls}")
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["unknown-box"])
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.machineId == "unknown-box" && it.message.contains("No machine profile")
            },
        )
    }

    @Test
    fun failedLoginLogsTheKindOfTheFailure() = runTest {
        val backend = RecordingSshBackend(
            authResult = SshAuthResult.NetworkError(IOException("Connection refused")),
        )
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertEquals(listOf("authenticate lan-box"), backend.calls)
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["lan-box"])
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.tag == "SSHAuth" && it.message.contains("NetworkError (Connection refused)")
            },
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" },
        )
    }
}
