package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Tests the ProxyJump path of [RealSshBackend] and [RealHerdrSshClient] with two real SSH servers: a [bastion] that
 * opens `direct-tcpip` channels, and a [target].
 *
 * Each server puts its name in the environment of its commands, so a test can tell which server ran a command.
 * The two servers know the same key, so a key machine can log in to the two servers with one key.
 */
class ProxyJumpTest {

    private val debugLog = SshDebugLog()
    private val keyManager = AndroidKeystoreKeyManager(allowSoftwareKeys = true)
    private val bastion = TestSshServer(keyManager = keyManager, allowTcpForwarding = true, name = BASTION).start()
    private val target = TestSshServer(keyManager = keyManager, name = TARGET).start()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider())
    private val secretStore = InMemorySecretStore().apply {
        runBlocking { put(PASSWORD_REF, TestSshServer.PASSWORD.encodeToByteArray()) }
    }
    private val stateManager = SshStateManager()
    private val backend = RealSshBackend(
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = secretStore,
    )
    private val machines = mutableListOf<HostMachine>()

    @AfterTest
    fun tearDown() {
        machines.forEach { backend.disconnect(it.id) }
        bastion.close()
        target.close()
    }

    @Test
    fun passwordLoginThroughTheBastionRunsCommandsOnTheTarget() = runTest {
        acceptBothHostKeys()
        val machine = passwordMachine("bastion-password")

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertEquals(TARGET, serverNameOf(machine))
        assertTrue(bastion.credentialChecks > 0, "The client did not log in to the bastion")
        assertTrue(target.credentialChecks > 0, "The client did not log in to the target")
        val connected = "Connected to ${route(machine).targetAddress} via bastion ${route(machine).bastionAddress}"
        assertTrue(debugLog.entries.value.any { it.message.startsWith(connected) }, debugLogDump())
    }

    @Test
    fun keyLoginThroughTheBastionUsesTheKeyOfTheMachineForTheTwoLogins() = runTest {
        acceptBothHostKeys()
        val machine = machine(
            id = "bastion-key",
            username = TestSshServer.KEY_USER,
            authMethod = bastion.keyAuthMethod,
            bastionUser = TestSshServer.KEY_USER,
        )

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertEquals(TARGET, serverNameOf(machine))
    }

    @Test
    fun aFailedBastionLoginIsAnAuthFailureThatNamesTheBastion() = runTest {
        acceptBothHostKeys()
        val machine = passwordMachine("bastion-wrong-user", bastionUser = "nobody")
        val bastionAddress = "nobody@${TestSshServer.HOST}:${bastion.port}"

        val result = assertIs<SshAuthResult.AuthFailed>(authenticate(machine), debugLogDump())

        assertTrue(result.message.contains("bastion $bastionAddress"), result.message)
        assertEquals(0, target.credentialChecks, "The client sent credentials to the target")
        assertTrue(
            debugLog.entries.value.any { it.isError && it.message.contains("failed to bastion $bastionAddress") },
            debugLogDump(),
        )
        assertFalse(backend.client.hasLiveSession(machine.id))
    }

    @Test
    fun unknownKeysOfTheBastionAndTheTargetPromptInTurnForTheirOwnHostAndPort() = runTest {
        val transport = SshTransportManager(
            backend = backend,
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = PaneSurfaceBus()),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = PaneSurfaceBus(),
        )
        val machine = passwordMachine("bastion-prompts")

        assertFalse(transport.connect(machine))
        val bastionPrompt = assertIs<HostKeyPrompt.Unknown>(stateManager.hostKeyPrompts.value[machine.id])
        assertEquals(bastion.knownHost(), bastionPrompt.presented)
        assertEquals(0, bastion.credentialChecks, "The client sent credentials to an unknown bastion")

        // The user accepts the bastion key. The app saves it and connects again.
        knownHostsStore.put(bastionPrompt.presented)
        assertFalse(transport.connect(machine))
        val targetPrompt = assertIs<HostKeyPrompt.Unknown>(stateManager.hostKeyPrompts.value[machine.id])
        assertEquals(target.knownHost(), targetPrompt.presented)
        assertEquals(0, target.credentialChecks, "The client sent credentials to an unknown target")

        knownHostsStore.put(targetPrompt.presented)
        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())
        assertEquals(TARGET, serverNameOf(machine))
    }

    @Test
    fun aChangedBastionKeyGivesHostKeyChangedForTheBastion() = runTest {
        acceptBothHostKeys()
        val saved = bastion.knownHost()
        bastion.hostKey = TestSshServer.newHostKey()
        val machine = passwordMachine("bastion-changed-key")

        val result = assertIs<SshAuthResult.HostKeyChanged>(authenticate(machine), debugLogDump())

        assertEquals(saved, result.known)
        assertEquals(bastion.knownHost(), result.presented)
        assertEquals(0, bastion.credentialChecks, "The client sent credentials after a key change")
    }

    @Test
    fun aBastionThatRefusesForwardingGivesAClearError() = runTest {
        TestSshServer(keyManager = keyManager, allowTcpForwarding = false, name = "closed-bastion").start().use {
            knownHostsStore.put(it.knownHost())
            knownHostsStore.put(target.knownHost())
            val machine = passwordMachine("bastion-no-forwarding", bastionPort = it.port)

            val result = assertIs<SshAuthResult.Failed>(authenticate(machine), debugLogDump())

            val message = result.cause.message.orEmpty()
            val bastionAddress = "${TestSshServer.PASSWORD_USER}@${TestSshServer.HOST}:${it.port}"
            assertTrue(message.contains("The bastion $bastionAddress did not open a channel to"), message)
            assertTrue(message.contains(route(machine).targetAddress), message)
            assertTrue(message.contains("TCP forwarding"), message)
            assertEquals(0, target.credentialChecks, "The client got to the target")
        }
    }

    @Test
    fun eachChannelOpenFailureGetsTheKindOfItsReason() {
        val proxy = BastionChannelProxy(JSch().getSession("jump", "bastion.example", 22), "jump@bastion.example:22")
        val cause = JSchException("channel is not opened.")
        val expected = mapOf(
            BastionChannelProxy.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED to SshAuthResult.Failed::class,
            BastionChannelProxy.SSH_OPEN_CONNECT_FAILED to SshAuthResult.NetworkError::class,
            BastionChannelProxy.SSH_OPEN_UNKNOWN_CHANNEL_TYPE to SshAuthResult.Failed::class,
            BastionChannelProxy.SSH_OPEN_RESOURCE_SHORTAGE to SshAuthResult.NetworkError::class,
            BastionChannelProxy.NO_REASON to SshAuthResult.NetworkError::class,
        )

        expected.forEach { (reason, kind) ->
            val failure = proxy.openFailure(reason, "10.0.0.42:22", cause)
            val result = RealSshBackend.toAuthResult(asSessionConnectError(failure))

            assertEquals(kind, result::class, "Reason $reason gave $result")
            assertTrue(failure.message.orEmpty().contains("jump@bastion.example:22"), failure.message)
            assertTrue(failure.message.orEmpty().contains("10.0.0.42:22"), failure.message)
        }
    }

    @Test
    fun aStoppedTargetBehindTheBastionGivesANetworkError() = runTest {
        acceptBothHostKeys()
        val machine = passwordMachine("bastion-target-down")
        target.stop()

        val result = assertIs<SshAuthResult.NetworkError>(authenticate(machine), debugLogDump())

        val message = result.cause.message.orEmpty()
        assertTrue(message.contains("did not open a channel to ${route(machine).targetAddress}"), message)
    }

    @Test
    fun aTargetThatSendsNothingTimesOutWithANetworkError() = runTest {
        knownHostsStore.put(bastion.knownHost())
        // The system accepts the TCP connection for the backlog, but nothing reads or writes.
        ServerSocket(0, 1, InetAddress.getByName(TestSshServer.HOST)).use { silent ->
            val machine = passwordMachine("bastion-silent-target").copy(port = silent.localPort)

            val result = assertIs<SshAuthResult.NetworkError>(authenticate(machine), debugLogDump())

            assertTrue(
                generateSequence(result.cause) { it.cause }.any { it is SocketTimeoutException },
                result.cause.stackTraceToString(),
            )
        }
    }

    @Test
    fun disconnectClosesTheTargetSessionAndTheBastionSession() = runTest {
        acceptBothHostKeys()
        val machine = passwordMachine("bastion-disconnect")
        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())
        assertTrue(backend.client.hasLiveSession(machine.id))
        awaitCondition("one session on each server") {
            bastion.activeSessionCount == 1 && target.activeSessionCount == 1
        }

        backend.disconnect(machine.id)

        assertFalse(backend.client.hasLiveSession(machine.id))
        awaitCondition("no session on the two servers") {
            bastion.activeSessionCount == 0 && target.activeSessionCount == 0
        }
    }

    @Test
    fun aStoppedBastionStopsTheTargetSessionAndTheNextCommandConnectsAgain() = runTest {
        acceptBothHostKeys()
        val machine = passwordMachine("bastion-death")
        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())
        assertEquals(TARGET, serverNameOf(machine))

        bastion.stop()

        awaitCondition("the client marks the session dead") { !backend.client.hasLiveSession(machine.id) }
        val error = assertFailsWith<BastionConnectException> { serverNameOf(machine) }
        assertTrue(error.message.orEmpty().contains("bastion ${route(machine).bastionAddress}"), error.message)

        bastion.start()
        assertEquals(TARGET, serverNameOf(machine))
        assertTrue(backend.client.hasLiveSession(machine.id))
    }

    private suspend fun acceptBothHostKeys() {
        knownHostsStore.put(bastion.knownHost())
        knownHostsStore.put(target.knownHost())
    }

    private fun passwordMachine(
        id: String,
        bastionUser: String = TestSshServer.PASSWORD_USER,
        bastionPort: Int = bastion.port,
    ): HostMachine = machine(
        id = id,
        username = TestSshServer.PASSWORD_USER,
        authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(PASSWORD_REF)),
        bastionUser = bastionUser,
        bastionPort = bastionPort,
    )

    /** Returns a machine on [target] that connects through [bastion] as [bastionUser]. */
    private fun machine(
        id: String,
        username: String,
        authMethod: SshAuthMethod,
        bastionUser: String,
        bastionPort: Int = bastion.port,
    ): HostMachine = target.machine(id = id, username = username, authMethod = authMethod)
        .copy(
            proxyJump = ProxyJumpConfig(
                bastionHost = TestSshServer.HOST,
                bastionPort = bastionPort,
                bastionUser = bastionUser,
            ),
        )
        .also { machines += it }

    private fun route(machine: HostMachine): ResolvedSshRoute = ProxyJumpTunnel().resolveRoute(machine)

    private suspend fun authenticate(machine: HostMachine): SshAuthResult =
        backend.authenticate(machine, route(machine))

    /** Runs a command on [machine] and returns the name of the server that ran it. */
    private suspend fun serverNameOf(machine: HostMachine): String =
        backend.client.runCommand(machine, route(machine), "echo \$${TestSshServer.NAME_VARIABLE}").trim()

    /** Returns the error that JSch `Session.connect` throws when `Proxy.connect` throws [error]. */
    private fun asSessionConnectError(error: Exception): Exception =
        when (error) {
            is RuntimeException, is JSchException -> error
            else -> JSchException("Session.connect: $error", error)
        }

    /** Waits a maximum of [AWAIT_TIMEOUT_MS] for [condition]. */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timeout: $description\n${debugLogDump()}")
            Thread.sleep(AWAIT_STEP_MS)
        }
    }

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val BASTION = "bastion"
        const val TARGET = "target"
        const val PASSWORD_REF = "password-proxy-jump"
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val AWAIT_STEP_MS = 20L
    }
}
