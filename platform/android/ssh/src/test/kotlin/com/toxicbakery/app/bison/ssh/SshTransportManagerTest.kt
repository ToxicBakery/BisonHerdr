package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun unknownHostKeyStopsTheConnectAndPutsAPrompt() = runTest {
        val presented = knownHost(fingerprint = "SHA256:new")
        val backend = RecordingSshBackend(authResult = SshAuthResult.HostKeyUnknown(presented))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")
        val manager = manager(backend)

        assertFalse(manager.connect(machine))

        assertEquals(listOf("authenticate lan-box"), backend.calls)
        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value["lan-box"])
        assertEquals(
            mapOf("lan-box" to HostKeyPrompt.Unknown(machineId = "lan-box", presented = presented)),
            stateManager.hostKeyPrompts.value,
        )

        // The user accepts the key in the UI and connects again. The new connect closes the prompt.
        backend.authResult = SshAuthResult.Success
        assertTrue(manager.connect(machine))
        assertNull(stateManager.hostKeyPrompts.value["lan-box"])
    }

    @Test
    fun changedHostKeyPutsAPromptWithBothKeys() = runTest {
        val known = knownHost(fingerprint = "SHA256:old")
        val presented = knownHost(fingerprint = "SHA256:new")
        val backend = RecordingSshBackend(authResult = SshAuthResult.HostKeyChanged(known = known, presented = presented))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertEquals(
            HostKeyPrompt.Changed(machineId = "lan-box", known = known, presented = presented),
            stateManager.hostKeyPrompts.value["lan-box"],
        )
        assertTrue(
            debugLog.entries.value.any {
                it.isError && it.message.contains("HostKeyChanged") && it.message.contains("SHA256:new")
            },
            debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" },
        )
    }

    @Test
    fun disconnectClosesTheHostKeyPrompt() = runTest {
        val backend = RecordingSshBackend(authResult = SshAuthResult.HostKeyUnknown(knownHost(fingerprint = "SHA256:new")))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")
        val manager = manager(backend)
        assertFalse(manager.connect(machine))

        manager.disconnect("lan-box")

        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
    }

    @Test
    fun otherLoginFailuresPutNoHostKeyPrompt() = runTest {
        val backend = RecordingSshBackend(authResult = SshAuthResult.AuthFailed("Auth fail"))
        val machine = HostMachine(id = "lan-box", name = "lan-box", hostname = "192.168.1.42", username = "ian")

        assertFalse(manager(backend).connect(machine))

        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
    }

    private fun knownHost(fingerprint: String): KnownHost = KnownHost(
        host = "192.168.1.42",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprint = fingerprint,
        publicKey = "AAAA",
    )
}
