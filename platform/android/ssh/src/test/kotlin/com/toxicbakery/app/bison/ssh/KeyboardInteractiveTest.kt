package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Tests the `keyboard-interactive` questions and the prompt for a missing password of [RealSshBackend] against real
 * SSH servers ([TestSshServer]).
 *
 * [startUi] acts as the UI: it runs [SshStateManager.hostUserAuthPrompts], records each prompt in
 * [prompts], and answers it. A test with no [startUi] has no UI.
 */
class KeyboardInteractiveTest {

    private val debugLog = SshDebugLog()
    private val server = TestSshServer().start()
    private val bastions = mutableListOf<TestSshServer>()
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider()).apply {
        runBlocking { put(server.knownHost()) }
    }
    private val secretStore = InMemorySecretStore().apply {
        runBlocking { put(PASSWORD_REF, TestSshServer.PASSWORD.encodeToByteArray()) }
    }
    private val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = Dispatchers.Unconfined)
    private val stateManager = SshStateManager()
    private var backend = backend(timeout = 1.minutes)
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The prompts that the UI of [startUi] got, in order. */
    private val prompts = CopyOnWriteArrayList<KeyboardInteractivePrompt>()
    private val machines = mutableListOf<HostMachine>()

    @AfterTest
    fun tearDown() {
        ui.cancel()
        machines.forEach { backend.disconnect(it.id) }
        server.close()
        bastions.forEach(TestSshServer::close)
    }

    @Test
    fun aLoginWithTwoQuestionsAsksTheUserAndUsesTheAnswers() = runTest {
        server.interactivePrompts = PASSWORD_AND_CODE
        startUi { answered(TestSshServer.PASSWORD, ONE_TIME_CODE) }
        val machine = interactiveMachine("two-questions")

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        val prompt = prompts.single()
        assertEquals(
            listOf(
                KeyboardInteractiveQuestion(text = "Password: ", echo = false),
                KeyboardInteractiveQuestion(text = "One-time code: ", echo = true),
            ),
            prompt.questions,
        )
        assertEquals(machine.id, prompt.machineId)
        assertEquals(server.knownHost().hostPort, prompt.hostPort)
        assertEquals("TestSshServer", prompt.name)
        assertFalse(prompt.passwordMissing)
        assertEquals("ok", runCommand(machine, "echo ok"))
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
        assertTrue(debugLog.entries.value.none { ONE_TIME_CODE in it.message }, debugLogDump())
    }

    @Test
    fun theStoredPasswordAnswersASinglePasswordQuestionWithNoUi() = runTest {
        // The text has no colon, so JSch does not answer it on its own. The user info answers it.
        server.interactivePrompts = listOf(
            TestSshServer.InteractivePrompt("Password for bison", TestSshServer.PASSWORD),
        )
        val machine = interactiveMachine("stored-password")

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertTrue(prompts.isEmpty())
        assertTrue(
            debugLog.entries.value.any { it.message.contains("gave the password of the machine") },
            debugLogDump(),
        )
        assertTrue(debugLog.entries.value.none { TestSshServer.PASSWORD in it.message }, debugLogDump())
    }

    @Test
    fun theStoredPasswordDoesNotAnswerAVisibleOrANewPasswordQuestion() = runTest {
        val questions = listOf(
            TestSshServer.InteractivePrompt("Password: ", TestSshServer.PASSWORD, echo = true),
            // The text has no colon, so JSch does not answer it on its own.
            TestSshServer.InteractivePrompt("Type a new password", TestSshServer.PASSWORD),
        )
        questions.forEach { question ->
            server.interactivePrompts = listOf(question)
            val machine = interactiveMachine("not-the-password-${questions.indexOf(question)}")

            val result = assertIs<SshAuthResult.InputRequired>(authenticate(machine), debugLogDump())

            assertFalse(result.timedOut)
        }
    }

    @Test
    fun aCancelGivesInputCancelledForTheHostThatAsked() = runTest {
        server.interactivePrompts = PASSWORD_AND_CODE
        startUi { KeyboardInteractiveOutcome.Cancelled }
        val machine = interactiveMachine("cancelled")

        val result = authenticate(machine)

        assertEquals(SshAuthResult.InputCancelled(server.knownHost().hostPort), result, debugLogDump())
        assertEquals(1, prompts.size)
        assertFalse(backend.client.hasLiveSession(machine.id))
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
    }

    @Test
    fun withNoUiTheLoginStopsAtOnceWithInputRequired() = runTest {
        server.interactivePrompts = PASSWORD_AND_CODE
        val machine = interactiveMachine("no-ui")

        val (result, time) = measureTimedValue { authenticate(machine) }

        assertEquals(SshAuthResult.InputRequired(server.knownHost().hostPort, timedOut = false), result, debugLogDump())
        assertTrue(time < NO_WAIT, "The login waited for $time")
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
    }

    @Test
    fun noAnswerInTheTimeLimitGivesATimedOutInputRequired() = runTest {
        backend = backend(timeout = 300.milliseconds)
        server.interactivePrompts = PASSWORD_AND_CODE
        // The UI shows the prompts, but the user does not answer.
        ui.launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.hostUserAuthPrompts { UserAuthOutcome.Cancelled }
        }
        val machine = interactiveMachine("timed-out")

        val result = authenticate(machine)

        assertEquals(SshAuthResult.InputRequired(server.knownHost().hostPort, timedOut = true), result, debugLogDump())
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
    }

    @Test
    fun theQuestionsOfABastionNameTheBastion() = runTest {
        val bastion = TestSshServer(allowTcpForwarding = true, name = "bastion").start().also { bastions += it }
        bastion.interactivePrompts = PASSWORD_AND_CODE
        knownHostsStore.put(bastion.knownHost())
        startUi { answered(TestSshServer.PASSWORD, ONE_TIME_CODE) }
        val machine = server.machine(
            id = "bastion-questions",
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(PASSWORD_REF)),
        ).copy(
            proxyJump = ProxyJumpConfig(
                bastionHost = TestSshServer.HOST,
                bastionPort = bastion.port,
                bastionUser = TestSshServer.INTERACTIVE_USER,
            ),
        ).also { machines += it }

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertEquals(bastion.knownHost().hostPort, prompts.single().hostPort)
        assertNotEquals(server.knownHost().hostPort, prompts.single().hostPort)
        assertEquals(server.name, runCommand(machine, "echo \$${TestSshServer.NAME_VARIABLE}"))
    }

    @Test
    fun aMissingPasswordIsAskedSavedAndUsedForTheLogin() = runTest {
        startUi { prompt ->
            if (prompt.passwordMissing) answered(TestSshServer.PASSWORD, savePassword = true) else cancelled()
        }
        val machine = passwordMachine("missing-password", SecretRef.format(ABSENT_REF))

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        val prompt = prompts.single()
        assertTrue(prompt.passwordMissing)
        assertEquals(listOf(false), prompt.questions.map { it.echo })
        assertEquals(server.knownHost().hostPort, prompt.hostPort)
        assertContentEquals(TestSshServer.PASSWORD.encodeToByteArray(), secretStore.get(ABSENT_REF))
        assertEquals("ok", runCommand(machine, "echo ok"))

        // The next login reads the saved password and does not ask.
        stopUi()
        backend.disconnect(machine.id)
        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())
        assertEquals(1, prompts.size)
    }

    @Test
    fun aSavedPasswordOfAMachineWithNoReferenceGetsANewReference() = runTest {
        startUi { answered(TestSshServer.PASSWORD, savePassword = true) }
        val machine = passwordMachine("no-reference", passwordValue = "")
        machineStore.upsertMachine(machine)

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        val value = (machineStore.getMachine(machine.id)?.authMethod as? SshAuthMethod.Password)?.encryptedPasswordRef
        val ref = assertNotNull(SecretRef.parse(value.orEmpty()), "The machine has no new reference: $value")
        assertContentEquals(TestSshServer.PASSWORD.encodeToByteArray(), secretStore.get(ref))

        // A reconnect uses the machine from before the save, and it finds the new reference.
        stopUi()
        backend.disconnect(machine.id)
        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())
    }

    @Test
    fun aMissingPasswordThatTheUserDoesNotSaveIsNotKept() = runTest {
        startUi { answered(TestSshServer.PASSWORD) }
        val machine = passwordMachine("not-saved", SecretRef.format(ABSENT_REF))

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertNull(secretStore.get(ABSENT_REF))
        stopUi()
        backend.disconnect(machine.id)
        val result = assertIs<SshAuthResult.Failed>(authenticate(machine), debugLogDump())
        assertIs<MissingSecretException>(result.cause)
    }

    @Test
    fun aWrongTypedPasswordIsNotSavedWhenALaterAnswerOfTheUserPassesTheLogin() = runTest {
        server.interactivePrompts = listOf(TestSshServer.InteractivePrompt("Password: ", TestSshServer.PASSWORD))
        startUi { prompt ->
            if (prompt.passwordMissing) {
                answered(WRONG_PASSWORD, savePassword = true)
            } else {
                answered(TestSshServer.PASSWORD)
            }
        }
        val machine = server.machine(
            id = "typed-then-asked",
            username = TestSshServer.INTERACTIVE_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(ABSENT_REF)),
        ).also { machines += it }

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertTrue(prompts.any { !it.passwordMissing }, "The host did not ask the user")
        assertNull(secretStore.get(ABSENT_REF), "The client saved the wrong typed password")
        assertTrue(
            debugLog.entries.value.any { it.message.contains("did not save the typed password") },
            debugLogDump(),
        )
        assertTrue(debugLog.entries.value.none { WRONG_PASSWORD in it.message }, debugLogDump())
    }

    @Test
    fun jschGivesTheSessionPasswordToANewPasswordQuestionBeforeTheUserInfo() = runTest {
        // This test documents a JSch behavior that the user info cannot stop. JSch gives the session password to a
        // single hidden question that contains "password:", also when the question asks for a new password.
        server.offerPasswordMethod = false
        server.stop()
        server.start()
        server.interactivePrompts = listOf(TestSshServer.InteractivePrompt("New password: ", TestSshServer.PASSWORD))
        var asked = 0
        startUi {
            asked++
            cancelled()
        }
        val machine = interactiveMachine("jsch-new-password")

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertEquals(0, asked, "The question went to the user")
        assertTrue(
            debugLog.entries.value.none { it.message.contains("gave the password of the machine") },
            debugLogDump(),
        )
    }

    @Test
    fun aSavedPasswordOfAMachineThatChangedDuringTheLoginIsRemoved() = runTest {
        val machine = passwordMachine("changed-during-login", passwordValue = "")
        machineStore.upsertMachine(machine)
        startUi {
            // The user removes the machine while the login waits for the password.
            machineStore.removeMachine(machine.id)
            answered(TestSshServer.PASSWORD, savePassword = true)
        }

        assertEquals(SshAuthResult.Success, authenticate(machine), debugLogDump())

        assertNull(machineStore.getMachine(machine.id), "The save added the removed machine again")
        assertEquals(setOf(PASSWORD_REF), secretStore.refs, "A secret with no machine stays in the store")
        assertTrue(debugLog.entries.value.any { it.message.contains("did not save the password") }, debugLogDump())
    }

    @Test
    fun aCancelOfTheMissingPasswordOpensNoConnection() = runTest {
        startUi { cancelled() }
        val machine = passwordMachine("missing-password-cancelled", SecretRef.format(ABSENT_REF))

        val result = authenticate(machine)

        assertEquals(SshAuthResult.InputCancelled(server.knownHost().hostPort), result, debugLogDump())
        assertEquals(0, server.credentialChecks, "The client sent credentials with no password")
        assertNull(secretStore.get(ABSENT_REF))
    }

    @Test
    fun theUserInfoNeverAcceptsAnUnknownHostKey() {
        val userInfo = KeyboardInteractiveUserInfo(
            machineId = "host-key",
            machineName = "host-key",
            hostPort = server.knownHost().hostPort,
            password = TestSshServer.PASSWORD.encodeToByteArray(),
            callerJob = null,
            sshDebugLog = debugLog,
            ask = { answered(TestSshServer.PASSWORD) },
        )
        val emptyHosts = KnownHostsStore(InMemoryStoreIOProvider())
        val session = JSch().getSession(TestSshServer.PASSWORD_USER, TestSshServer.HOST, server.port)
        session.setPassword(TestSshServer.PASSWORD.encodeToByteArray())
        session.userInfo = userInfo
        session.hostKeyRepository = KnownHostsRepository(emptyHosts)
        // With "ask", JSch asks the user info if it accepts the unknown key.
        session.setConfig("StrictHostKeyChecking", "ask")

        assertFailsWith<JSchException> { session.connect(CONNECT_TIMEOUT_MS) }

        assertFalse(session.isConnected)
        assertFalse(userInfo.promptYesNo("Accept the host key?"))
        assertFalse(userInfo.promptPassword("Password for ${TestSshServer.PASSWORD_USER}"))
        assertNull(userInfo.password)
        assertTrue(emptyHosts.knownHosts.value.isEmpty())
        assertEquals(0, server.credentialChecks, "The client sent credentials to an unknown host")
    }

    @Test
    fun anUnknownHostKeyStopsTheLoginBeforeAQuestion() = runTest {
        server.interactivePrompts = PASSWORD_AND_CODE
        startUi { answered(TestSshServer.PASSWORD, ONE_TIME_CODE) }
        knownHostsStore.remove(TestSshServer.HOST, server.port)
        val machine = interactiveMachine("unknown-host-key")

        assertIs<SshAuthResult.HostKeyUnknown>(authenticate(machine), debugLogDump())

        assertTrue(prompts.isEmpty())
        assertTrue(knownHostsStore.knownHosts.value.isEmpty())
        assertEquals(0, server.credentialChecks)
    }

    @Test
    fun inputErrorsInsideAJschErrorKeepTheirResult() {
        val cancelled = UserInputException.of("box.lan:22", KeyboardInteractiveOutcome.Cancelled)
        val noUi = UserInputException.of("box.lan:22", KeyboardInteractiveOutcome.NoUi)

        assertEquals(SshAuthResult.InputCancelled("box.lan:22"), RealSshBackend.toAuthResult(cancelled))
        assertEquals(
            SshAuthResult.InputRequired("box.lan:22", timedOut = false),
            RealSshBackend.toAuthResult(JSchException("Session.connect: $noUi", noUi)),
        )
    }

    /** Acts as the UI: it hosts the prompts, records each new prompt in [prompts], and answers it with [answer]. */
    private fun startUi(answer: suspend (KeyboardInteractivePrompt) -> KeyboardInteractiveOutcome) {
        ui.launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.hostUserAuthPrompts { UserAuthOutcome.Cancelled }
        }
        ui.launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.keyboardInteractivePrompts.collect { open ->
                open.filter(prompts::addIfAbsent).forEach { prompt ->
                    stateManager.answerKeyboardInteractive(prompt, answer(prompt))
                }
            }
        }
    }

    /** Stops the UI of [startUi], and waits until [SshStateManager] knows that no UI shows the prompts. */
    private suspend fun stopUi() {
        ui.coroutineContext.job.cancelAndJoin()
    }

    private fun backend(timeout: Duration) = RealSshBackend(
        localNetworkGuard = permittedLocalNetwork(),
        sshStateManager = stateManager,
        sshDebugLog = debugLog,
        endpointSnapshotBus = EndpointSnapshotBus(),
        paneSurfaceBus = PaneSurfaceBus(),
        semanticNotificationBus = SemanticNotificationBus(),
        keystoreKeyManager = server.keyManager,
        knownHostsStore = knownHostsStore,
        secretStore = secretStore,
        machineStore = machineStore,
        keyboardInteractiveTimeout = timeout,
    )

    /** Returns a machine that logs in as [TestSshServer.INTERACTIVE_USER] with the stored password. */
    private fun interactiveMachine(id: String): HostMachine =
        server.machine(
            id = id,
            username = TestSshServer.INTERACTIVE_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(PASSWORD_REF)),
        ).also { machines += it }

    /** Returns a machine that logs in as [TestSshServer.PASSWORD_USER] with [passwordValue]. */
    private fun passwordMachine(id: String, passwordValue: String): HostMachine =
        server.machine(
            id = id,
            username = TestSshServer.PASSWORD_USER,
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = passwordValue),
        ).also { machines += it }

    private suspend fun authenticate(machine: HostMachine): SshAuthResult =
        backend.authenticate(machine, ProxyJumpTunnel().resolveRoute(machine))

    private suspend fun runCommand(machine: HostMachine, command: String): String =
        backend.client.runCommand(machine, ProxyJumpTunnel().resolveRoute(machine), command).trim()

    private fun debugLogDump(): String =
        debugLog.entries.value.joinToString("\n") { "[${it.tag}] ${it.message}" }

    private companion object {
        const val PASSWORD_REF = "password-keyboard-interactive"

        /** A reference that the secret store does not have, for example after a restore. */
        const val ABSENT_REF = "password-absent"
        const val ONE_TIME_CODE = "246810"
        const val WRONG_PASSWORD = "wrong-typed"
        const val CONNECT_TIMEOUT_MS = 10_000

        /** A login with no UI must end much faster than this. */
        val NO_WAIT: Duration = 10.seconds

        val PASSWORD_AND_CODE = listOf(
            TestSshServer.InteractivePrompt("Password: ", TestSshServer.PASSWORD),
            TestSshServer.InteractivePrompt("One-time code: ", ONE_TIME_CODE, echo = true),
        )

        fun answered(vararg answers: String, savePassword: Boolean = false) =
            KeyboardInteractiveOutcome.Answered(answers.toList(), savePassword)

        fun cancelled(): KeyboardInteractiveOutcome = KeyboardInteractiveOutcome.Cancelled
    }
}
