package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchHostKeyException
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests the host key check of [RealSshBackend] with [KnownHostsRepository] against a real SSH server
 * ([TestSshServer]).
 */
class HostKeyCheckTest {

    private val debugLog = SshDebugLog()
    private val server = TestSshServer().start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider())
    private val backend = RealSshBackend(
        localNetworkGuard = permittedLocalNetwork(),
        sshStateManager = SshStateManager(),
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = InMemorySecretStore(),
    )
    private val passwordMachine = server.machine(
        id = "host-key-password",
        username = TestSshServer.PASSWORD_USER,
        authMethod = SshAuthMethod.Password(encryptedPasswordRef = TestSshServer.PASSWORD),
    )
    private val machines = mutableListOf(passwordMachine)

    @AfterTest
    fun tearDown() {
        machines.forEach { backend.disconnect(it.id) }
        server.close()
    }

    @Test
    fun firstConnectGivesHostKeyUnknownAndSavesNothing() = runTest {
        val result = assertIs<SshAuthResult.HostKeyUnknown>(authenticate(passwordMachine), debugLogDump())

        assertEquals(server.knownHost(), result.presented)
        assertEquals("ecdsa-sha2-nistp256", result.presented.keyType)
        assertTrue(result.presented.fingerprint.startsWith("SHA256:"), result.presented.fingerprint)
        assertTrue(knownHostsStore.knownHosts.value.isEmpty(), "The store has ${knownHostsStore.knownHosts.value}")
        assertEquals(0, server.credentialChecks, "The client sent credentials to an unknown host")
        assertTrue(
            debugLog.entries.value.any { it.isError && it.message.contains("Unknown host key") },
            debugLogDump(),
        )
    }

    @Test
    fun acceptedKeyConnectsAndTheNextConnectDoesNotAsk() = runTest {
        val unknown = assertIs<SshAuthResult.HostKeyUnknown>(authenticate(passwordMachine), debugLogDump())
        knownHostsStore.put(unknown.presented)

        assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
        assertEquals("accepted", backend.client.runCommand(passwordMachine, route(passwordMachine), "echo accepted").trim())

        // A new session does the key exchange again and finds the saved key.
        backend.disconnect(passwordMachine.id)
        assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
        assertEquals(listOf(unknown.presented), knownHostsStore.knownHosts.value)
    }

    @Test
    fun changedKeyGivesHostKeyChangedAndSendsNoCredentials() = runTest {
        val original = server.knownHost()
        knownHostsStore.put(original)
        assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
        backend.disconnect(passwordMachine.id)

        server.hostKey = TestSshServer.newHostKey()
        val checksBefore = server.credentialChecks
        val result = assertIs<SshAuthResult.HostKeyChanged>(authenticate(passwordMachine), debugLogDump())

        assertEquals(original, result.known)
        assertEquals(server.knownHost(), result.presented)
        assertTrue(result.known.fingerprint != result.presented.fingerprint)
        assertEquals(checksBefore, server.credentialChecks, "The client sent credentials after a key change")
        assertEquals(listOf(original), knownHostsStore.knownHosts.value, "The check changed the store")
    }

    @Test
    fun changedKeyAlsoStopsAKeyLoginBeforeTheKeyQuery() = runTest {
        val keyMachine = server.machine(
            id = "host-key-key",
            username = TestSshServer.KEY_USER,
            authMethod = server.keyAuthMethod,
        ).also { machines += it }
        knownHostsStore.put(server.knownHost())
        server.hostKey = TestSshServer.newHostKey()

        assertIs<SshAuthResult.HostKeyChanged>(authenticate(keyMachine), debugLogDump())
        assertEquals(0, server.credentialChecks, "The client sent a public key query after a key change")
    }

    @Test
    fun removedKeyAsksAgainAndTheNewKeyConnectsAfterAccept() = runTest {
        knownHostsStore.put(server.knownHost())
        server.hostKey = TestSshServer.newHostKey()
        val changed = assertIs<SshAuthResult.HostKeyChanged>(authenticate(passwordMachine), debugLogDump())

        assertTrue(knownHostsStore.remove(changed.known.host, changed.known.port))
        val unknown = assertIs<SshAuthResult.HostKeyUnknown>(authenticate(passwordMachine), debugLogDump())
        assertEquals(changed.presented, unknown.presented)

        knownHostsStore.put(unknown.presented)
        assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
    }

    @Test
    fun deletedKeyAsksAgain() = runTest {
        knownHostsStore.put(server.knownHost())
        assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
        backend.disconnect(passwordMachine.id)

        knownHostsStore.remove(TestSshServer.HOST, server.port)

        assertIs<SshAuthResult.HostKeyUnknown>(authenticate(passwordMachine), debugLogDump())
    }

    @Test
    fun aNewKeyTypeOnTheServerDoesNotChangeTheAcceptedKey() = runTest {
        server.hostKey = TestSshServer.newRsaHostKey()
        val unknown = assertIs<SshAuthResult.HostKeyUnknown>(authenticate(passwordMachine), debugLogDump())
        assertEquals("ssh-rsa", unknown.presented.keyType)
        knownHostsStore.put(unknown.presented)
        assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
        backend.disconnect(passwordMachine.id)

        // The server now also offers an ecdsa-sha2-nistp256 key. The JSch list has ECDSA before RSA.
        server.extraHostKeys = listOf(TestSshServer.newHostKey())
        val globalPreference = JSch.getConfig(PREFER_KNOWN_HOST_KEY_TYPES)
        // A system property can set this JSch default to "no". The value of the session must have priority.
        JSch.setConfig(PREFER_KNOWN_HOST_KEY_TYPES, "no")
        try {
            assertFailsWith<JSchHostKeyException>("Without the preference, JSch selects the new key type") {
                connectWithoutTheSessionPreference()
            }

            assertEquals(SshAuthResult.Success, authenticate(passwordMachine), debugLogDump())
        } finally {
            JSch.setConfig(PREFER_KNOWN_HOST_KEY_TYPES, globalPreference)
        }
        assertEquals(listOf(unknown.presented), knownHostsStore.knownHosts.value)
    }

    @Test
    fun keyOfADifferentPortIsUnknown() = runTest {
        knownHostsStore.put(server.knownHost().copy(port = server.port + 1))

        assertIs<SshAuthResult.HostKeyUnknown>(authenticate(passwordMachine), debugLogDump())
    }

    @Test
    fun fingerprintHasTheOpenSshForm() {
        val known = server.knownHost()
        val blob = Base64.getDecoder().decode(known.publicKey)

        assertEquals(known.fingerprint, KnownHostsRepository.fingerprint(blob))
        assertEquals(known, KnownHostsRepository.knownHostOf(known.host, known.port, known.keyType, blob))
    }

    @Test
    fun repositoryComparesTheKeyOfTheHostAndPort() = runTest {
        val known = server.knownHost()
        knownHostsStore.put(known)
        val repository = KnownHostsRepository(knownHostsStore)
        val blob = Base64.getDecoder().decode(known.publicKey)
        server.hostKey = TestSshServer.newHostKey()
        val otherBlob = Base64.getDecoder().decode(server.knownHost().publicKey)
        val jschHost = "[${known.host}]:${known.port}"

        assertEquals(HostKeyRepository.OK, repository.check(jschHost, blob))
        assertEquals(HostKeyRepository.CHANGED, repository.check(jschHost, otherBlob))
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("[${known.host}]:1", blob))
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check(known.host, blob))

        val hostKeys = repository.getHostKey(jschHost, null)
        assertEquals(listOf(known.keyType), hostKeys.map { it.type })
        assertEquals(listOf(known.publicKey), hostKeys.map { it.key })
        assertEquals(1, repository.getHostKey(jschHost, known.keyType).size)
        assertEquals(0, repository.getHostKey(jschHost, "ssh-ed25519").size)
        assertEquals(1, repository.getHostKey().size)
    }

    @Test
    fun repositoryNeverChangesTheStore() = runTest {
        val known = server.knownHost()
        knownHostsStore.put(known)
        val repository = KnownHostsRepository(knownHostsStore)
        val hostKey = repository.getHostKey().single()

        repository.remove(hostKey.host, hostKey.type)
        repository.remove(hostKey.host, hostKey.type, null)
        repository.add(hostKey, null)

        assertEquals(listOf(known), knownHostsStore.knownHosts.value)
    }

    @Test
    fun parseJschHostReadsBothForms() {
        assertEquals("devbox.local" to 22, KnownHostsRepository.parseJschHost("devbox.local"))
        assertEquals("devbox.local" to 2222, KnownHostsRepository.parseJschHost("[devbox.local]:2222"))
        assertEquals("fd7a:115c:a1e0::1" to 2222, KnownHostsRepository.parseJschHost("[fd7a:115c:a1e0::1]:2222"))
        assertEquals("[devbox.local]" to 22, KnownHostsRepository.parseJschHost("[devbox.local]"))
        assertEquals("[devbox.local]:2222", KnownHostsRepository.jschHost("devbox.local", 2222))
        assertEquals("devbox.local", KnownHostsRepository.jschHost("devbox.local", 22))
    }

    @Test
    fun toAuthResultMapsAHostKeyRejection() {
        val rejection = SshAuthResult.HostKeyUnknown(sampleKnownHost())
        val error = HostKeyRejectedException(rejection, JSchException("reject HostKey: devbox"))

        assertSame(rejection, RealSshBackend.toAuthResult(error))
    }

    private suspend fun authenticate(machine: HostMachine): SshAuthResult =
        backend.authenticate(machine, route(machine))

    /**
     * Connects with the same host key check as [KnownHostsRepository.connect], but with the global JSch value of
     * `prefer_known_host_key_types`.
     */
    private fun connectWithoutTheSessionPreference() {
        val session = JSch().getSession(TestSshServer.PASSWORD_USER, TestSshServer.HOST, server.port)
        session.hostKeyRepository = KnownHostsRepository(knownHostsStore)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setPassword(TestSshServer.PASSWORD.encodeToByteArray())
        JschAndroidCrypto.configureSession(session)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } finally {
            session.disconnect()
        }
    }

    private fun route(machine: HostMachine): ResolvedSshRoute = ProxyJumpTunnel().resolveRoute(machine)

    private fun sampleKnownHost(): KnownHost = KnownHost(
        host = "devbox",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprint = "SHA256:old",
        publicKey = "AAAA",
    )

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val PREFER_KNOWN_HOST_KEY_TYPES = "prefer_known_host_key_types"
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
