package com.toxicbakery.app.bison.ssh

import android.security.keystore.UserNotAuthenticatedException
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import java.io.IOException
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * Tests the login with a key that needs a user authentication (`requireBiometric`) against a real SSH
 * server ([TestSshServer]), and the map of a locked key to [SshAuthResult.UserAuthRequired].
 *
 * The bastion tests start a second server that knows the same key, and log in to [server] through it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserAuthLoginTest {

    private val debugLog = SshDebugLog()
    private val stateManager = SshStateManager()

    /** The error of each sign with the [lockedKeyManager] key, or null when the key signs. */
    @Volatile
    private var signError: Exception? = null

    /** The user authentication that the key store tells for the [lockedKeyManager] key. */
    @Volatile
    private var storedKeyAuth = KeyUserAuth.NotRequired
    private val lockedKeyManager = AndroidKeystoreKeyManager(
        allowSoftwareKeys = true,
        signatureFactory = { algorithm ->
            signError?.let { FailingSignature(it) } ?: Signature.getInstance(algorithm)
        },
        keyUserAuthReader = { storedKeyAuth },
    )
    private val server = TestSshServer(keyManager = lockedKeyManager).start()

    /** The ProxyJump bastion of the bastion tests, or null. */
    private var bastion: TestSshServer? = null
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val backend = RealSshBackend(
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = InMemorySecretStore(),
    )
    private val machines = mutableListOf<HostMachine>()

    /** The prompts that the UI of [startUi] got, with the number of credential checks of all servers at that time. */
    private val shown = mutableListOf<Pair<UserAuthPrompt, Int>>()

    @AfterTest
    fun tearDown() {
        machines.forEach { backend.disconnect(it.id) }
        server.close()
        bastion?.close()
    }

    /** Starts a UI that records each prompt and answers with [answer]. */
    private fun TestScope.startUi(answer: suspend (UserAuthPrompt) -> UserAuthOutcome) {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.hostUserAuthPrompts { prompt ->
                shown += prompt to server.credentialChecks + (bastion?.credentialChecks ?: 0)
                answer(prompt)
            }
        }
    }

    @Test
    fun aKeyWithoutRequireBiometricLogsInWithNoPrompt() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        val machine = machine("no-biometric", server.keyAuthMethod)

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route(machine)), debugLogDump())
        assertTrue(shown.isEmpty(), "The app asked for a user authentication: $shown")
    }

    @Test
    fun aBiometricKeyAsksTheUserBeforeTheLoginAndThenLogsIn() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        val machine = machine("biometric", biometricKey())

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route(machine)), debugLogDump())

        val (prompt, credentialChecksAtPrompt) = shown.single()
        assertEquals(machine.id, prompt.machineId)
        assertEquals(machine.name, prompt.machineName)
        assertEquals(TestSshServer.KEY_ALIAS, prompt.keyAlias)
        assertEquals(0, credentialChecksAtPrompt, "The app sent credentials before the prompt")
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun aCancelledPromptStopsTheLoginBeforeTheConnection() = runTest {
        startUi { UserAuthOutcome.Cancelled }
        val machine = machine("cancelled", biometricKey())

        val result = backend.authenticate(machine, route(machine))

        assertEquals(SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS, UserAuthOutcome.Cancelled), result)
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun aFailedPromptGivesTheReason() = runTest {
        startUi { UserAuthOutcome.Failed("Too many attempts") }
        val machine = machine("failed", biometricKey())

        val result = assertIs<SshAuthResult.UserAuthRequired>(backend.authenticate(machine, route(machine)))

        assertEquals(UserAuthOutcome.Failed("Too many attempts"), result.promptOutcome)
        assertTrue(result.message.endsWith("failed: Too many attempts"), result.message)
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun inTheBackgroundTheLoginStopsAtOnceWithNoPrompt() = runTest {
        val machine = machine("background", biometricKey())

        val result = backend.authenticate(machine, route(machine))

        assertEquals(SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS, UserAuthOutcome.NoUi), result)
        assertEquals(0L, currentTime, "The login waited")
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun aPromptWithNoAnswerTimesOut() = runTest {
        startUi { awaitCancellation() }
        val machine = machine("no-answer", biometricKey())

        val result = backend.authenticate(machine, route(machine))

        assertEquals(SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS, UserAuthOutcome.TimedOut), result)
        assertEquals(RealSshBackend.USER_AUTH_TIMEOUT_MS, currentTime)
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun theStoredKeyAndNotTheMachineSettingDecidesThePrompt() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        storedKeyAuth = KeyUserAuth.TimeBound
        val sharedKey = machine("shared-key", server.keyAuthMethod.copy(requireBiometric = false))

        assertEquals(SshAuthResult.Success, backend.authenticate(sharedKey, route(sharedKey)), debugLogDump())
        assertEquals(listOf(sharedKey.id), shown.map { it.first.machineId })

        storedKeyAuth = KeyUserAuth.NotRequired
        val plainKey = machine("plain-key", server.keyAuthMethod.copy(requireBiometric = true))

        assertEquals(SshAuthResult.Success, backend.authenticate(plainKey, route(plainKey)), debugLogDump())
        assertEquals(1, shown.size, "The app asked for a key that needs no user authentication")
    }

    @Test
    fun whenTheKeyStoreDoesNotTellTheMachineSettingDecides() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        storedKeyAuth = KeyUserAuth.Unknown
        val withSetting = machine("unknown-with-setting", server.keyAuthMethod.copy(requireBiometric = true))
        val withoutSetting = machine("unknown-without-setting", server.keyAuthMethod)

        assertEquals(SshAuthResult.Success, backend.authenticate(withSetting, route(withSetting)), debugLogDump())
        assertEquals(SshAuthResult.Success, backend.authenticate(withoutSetting, route(withoutSetting)), debugLogDump())

        assertEquals(listOf(withSetting.id), shown.map { it.first.machineId })
    }

    @Test
    fun aKeyThatNeedsTheUserForEachSignStopsWithNoPromptAndTellsTheUserToMakeANewKey() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        storedKeyAuth = KeyUserAuth.EachSign
        val machine = machine("each-sign", server.keyAuthMethod.copy(requireBiometric = true))

        val result = assertIs<SshAuthResult.UserAuthRequired>(backend.authenticate(machine, route(machine)))

        assertEquals(TestSshServer.KEY_ALIAS, result.keyAlias)
        assertTrue(result.keyNeedsUserForEachSign)
        assertTrue(result.message.contains("Make a new key"), result.message)
        assertTrue(shown.isEmpty(), "The app showed a prompt that cannot unlock the key")
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun aMissingKeyGetsNoPrompt() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        val missingKey = SshAuthMethod.KeystoreKey(
            keyAlias = "user_auth_test_missing",
            algorithm = server.keyAlgorithm,
            requireBiometric = true,
        )
        val machine = machine("missing-key", missingKey)

        assertIs<SshAuthResult.KeyMissing>(backend.authenticate(machine, route(machine)), debugLogDump())
        assertTrue(shown.isEmpty(), "The app asked to unlock a key that does not exist")
    }

    @Test
    fun aCheckedSignErrorIsNotHiddenAsAuthFail() = runTest {
        // A key that needs a user authentication for each sign fails in this way on a device.
        signError = SignatureException(KeyStoreException("Key user not authenticated"))
        val machine = machine("checked-sign-error", server.keyAuthMethod)

        val result = assertIs<SshAuthResult.Failed>(backend.authenticate(machine, route(machine)), debugLogDump())

        val signFailure = assertIs<KeySignException>(result.cause)
        assertEquals(TestSshServer.KEY_ALIAS, signFailure.keyAlias)
        assertTrue(signFailure.message.orEmpty().contains("Key user not authenticated"), signFailure.message)
    }

    @Test
    fun aLockedKeyGivesUserAuthRequiredThroughJsch() = runTest {
        signError = UserNotAuthenticatedException()
        val machine = machine("locked", server.keyAuthMethod)

        val result = backend.authenticate(machine, route(machine))

        assertEquals(SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS), result, debugLogDump())
    }

    @Test
    fun aKeyThatStaysLockedAfterTheUserAuthenticationTellsTheUserToMakeANewKey() = runTest {
        // The key store says that a prompt unlocks the key, but the key stays locked.
        signError = UserNotAuthenticatedException()
        startUi { UserAuthOutcome.Confirmed }
        val machine = machine("old-key", biometricKey())

        val result = assertIs<SshAuthResult.UserAuthRequired>(backend.authenticate(machine, route(machine)))

        assertEquals(TestSshServer.KEY_ALIAS, result.keyAlias)
        assertEquals(UserAuthOutcome.Confirmed, result.promptOutcome)
        assertTrue(result.message.contains("Make a new key"), result.message)
    }

    @Test
    fun oneConfirmedPromptUnlocksTheBastionLoginAndTheTargetLogin() = runTest {
        startUi { UserAuthOutcome.Confirmed }
        val bastion = startBastion()
        val machine = machineThroughBastion("bastion-biometric", biometricKey())

        assertEquals(SshAuthResult.Success, backend.authenticate(machine, route(machine)), debugLogDump())

        val (prompt, credentialChecksAtPrompt) = shown.single()
        assertEquals(TestSshServer.KEY_ALIAS, prompt.keyAlias)
        assertEquals(0, credentialChecksAtPrompt, "The app sent credentials before the prompt")
        assertTrue(bastion.credentialChecks > 0, "The client did not log in to the bastion")
        assertTrue(server.credentialChecks > 0, "The client did not log in to the target")
    }

    @Test
    fun aCancelledPromptStopsTheLoginBeforeTheBastion() = runTest {
        startUi { UserAuthOutcome.Cancelled }
        val bastion = startBastion()
        val machine = machineThroughBastion("bastion-cancelled", biometricKey())

        val result = backend.authenticate(machine, route(machine))

        assertEquals(SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS, UserAuthOutcome.Cancelled), result)
        assertEquals(0, bastion.credentialChecks)
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun aLockedKeyAtTheBastionGivesUserAuthRequired() = runTest {
        signError = UserNotAuthenticatedException()
        startBastion()
        val machine = machineThroughBastion("bastion-locked", server.keyAuthMethod)

        val result = backend.authenticate(machine, route(machine))

        assertEquals(SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS), result, debugLogDump())
    }

    @Test
    fun jschPassesAUserAuthErrorFromTheSignatureThrough() {
        val signError = UserAuthRequiredException(TestSshServer.KEY_ALIAS, UserNotAuthenticatedException())

        val error = assertFailsWith<UserAuthRequiredException> { connectWithSignError(signError) }

        assertSame(signError, error)
        assertEquals(
            SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS),
            RealSshBackend.toAuthResult(JSchException("Session.connect: locked", error)),
        )
    }

    @Test
    fun jschHidesACheckedSignErrorSoTheKeyManagerWrapsIt() {
        // This is why signSshChallenge throws a UserAuthRequiredException and not the checked exception.
        val error = assertFailsWith<JSchException> { connectWithSignError(UserNotAuthenticatedException()) }

        assertTrue(error.message.orEmpty().startsWith("Auth fail"), error.message)
        assertFalse(generateSequence<Throwable>(error) { it.cause }.any { it is UserNotAuthenticatedException })
    }

    @Test
    fun aUserNotAuthenticatedErrorAtAnyLevelIsUserAuthRequiredAndNotANetworkError() {
        val error = IOException("Session.connect", IllegalStateException("sign", UserNotAuthenticatedException()))

        assertEquals(SshAuthResult.UserAuthRequired(keyAlias = null), RealSshBackend.toAuthResult(error))
    }

    @Test
    fun theTransportLogsWhatTheUserMustDoAndFails() = runTest {
        val result = SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS, UserAuthOutcome.NoUi)
        val surfaceBus = PaneSurfaceBus()
        val transport = SshTransportManager(
            backend = RecordingSshBackend(authResult = result),
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
        val machine = machine("transport", biometricKey())

        assertFalse(transport.connect(machine))

        assertEquals(ConnectionProgress.Failed, stateManager.connectionProgress.value[machine.id])
        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
        assertTrue(
            debugLog.entries.value.any { it.isError && it.message.contains("UserAuthRequired (${result.message})") },
            debugLogDump(),
        )
        assertTrue(result.message.contains("Open the app and connect again"), result.message)
        assertEquals(NeedsUserCause.UserAuthRequired(TestSshServer.KEY_ALIAS), stateManager.needsUser.value[machine.id])
    }

    @Test
    fun aKeyFromAnEarlierVersionGivesItsOwnCause() = runTest {
        val result = SshAuthResult.UserAuthRequired(TestSshServer.KEY_ALIAS, keyNeedsUserForEachSign = true)
        val surfaceBus = PaneSurfaceBus()
        val transport = SshTransportManager(
            backend = RecordingSshBackend(authResult = result),
            moshUdpEngine = MoshUdpEngine(sshDebugLog = debugLog, paneSurfaceBus = surfaceBus),
            sshStateManager = stateManager,
            sshDebugLog = debugLog,
            paneSurfaceBus = surfaceBus,
        )
        val machine = machine("earlier-version", server.keyAuthMethod)

        assertFalse(transport.connect(machine))

        assertEquals(
            NeedsUserCause.KeyFromEarlierVersion(TestSshServer.KEY_ALIAS),
            stateManager.needsUser.value[machine.id],
        )
    }

    /** Connects to [server] with an identity whose sign throws [signError], and returns no result. */
    private fun connectWithSignError(signError: Exception) {
        // The server knows the public key, so JSch asks the identity for a signature.
        val knownKey = AndroidKeystoreJschIdentity(server.keyManager, TestSshServer.KEY_ALIAS, server.keyAlgorithm)
        val session = JSch().apply { addIdentity(SignFailsIdentity(knownKey, signError), null) }
            .getSession(TestSshServer.KEY_USER, TestSshServer.HOST, server.port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey")
        JschAndroidCrypto.configureSession(session)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } finally {
            session.disconnect()
        }
    }

    /** Gives the public key of [publicKey], but its sign throws [signError]. */
    private class SignFailsIdentity(private val publicKey: Identity, private val signError: Exception) : Identity {
        override fun setPassphrase(passphrase: ByteArray?): Boolean = true

        override fun getPublicKeyBlob(): ByteArray = publicKey.publicKeyBlob

        override fun getSignature(data: ByteArray?): ByteArray = throw signError

        override fun getSignature(data: ByteArray?, alg: String?): ByteArray = throw signError

        @Deprecated("Deprecated in JSch Identity")
        override fun decrypt(): Boolean = true

        override fun getAlgName(): String = publicKey.algName

        override fun getName(): String = "sign-fails"

        override fun isEncrypted(): Boolean = false

        override fun clear() = Unit
    }

    /** A JCA [Signature] whose init throws [error], as AndroidKeyStore does for a locked key. */
    private class FailingSignature(private val error: Exception) : Signature("failing") {
        override fun engineInitSign(privateKey: PrivateKey?) {
            throw error
        }

        override fun engineInitVerify(publicKey: PublicKey?) {
            throw UnsupportedOperationException()
        }

        override fun engineUpdate(b: Byte) = Unit

        override fun engineUpdate(b: ByteArray?, off: Int, len: Int) = Unit

        override fun engineSign(): ByteArray = throw UnsupportedOperationException()

        override fun engineVerify(sigBytes: ByteArray?): Boolean = false

        @Deprecated("Deprecated in SignatureSpi")
        override fun engineSetParameter(param: String?, value: Any?) = Unit

        @Deprecated("Deprecated in SignatureSpi")
        override fun engineGetParameter(param: String?): Any? = null
    }

    /** Returns the key of [server] as a key that the app made with `requireBiometric`. */
    private fun biometricKey(): SshAuthMethod.KeystoreKey {
        storedKeyAuth = KeyUserAuth.TimeBound
        return server.keyAuthMethod.copy(requireBiometric = true)
    }

    /** Starts a bastion that knows the key of [server], and accepts its host key. */
    private suspend fun startBastion(): TestSshServer {
        val started = TestSshServer(keyManager = lockedKeyManager, allowTcpForwarding = true, name = "bastion").start()
        bastion = started
        knownHostsStore.put(started.knownHost())
        return started
    }

    /** Returns a machine on [server] that connects through [bastion] with [authMethod]. */
    private fun machineThroughBastion(id: String, authMethod: SshAuthMethod): HostMachine =
        machine(id, authMethod).copy(
            proxyJump = ProxyJumpConfig(
                bastionHost = TestSshServer.HOST,
                bastionPort = checkNotNull(bastion).port,
                bastionUser = TestSshServer.KEY_USER,
            ),
        ).also { machines[machines.lastIndex] = it }

    private fun machine(id: String, authMethod: SshAuthMethod): HostMachine =
        server.machine(id = id, username = TestSshServer.KEY_USER, authMethod = authMethod).also { machines += it }

    private fun route(machine: HostMachine): ResolvedSshRoute = ProxyJumpTunnel().resolveRoute(machine)

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
