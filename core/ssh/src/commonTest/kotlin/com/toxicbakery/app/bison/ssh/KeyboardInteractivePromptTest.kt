package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Tests the keyboard-interactive prompts of [SshStateManager]. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardInteractivePromptTest {

    private val stateManager = SshStateManager()

    @Test
    fun withNoUiARequestReturnsNoUiAtOnceAndPutsNoPrompt() = runTest {
        val outcome = stateManager.requestKeyboardInteractive(prompt(), TIMEOUT)

        assertSame(KeyboardInteractiveOutcome.NoUi, outcome)
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
    }

    @Test
    fun theAnswerOfTheUiGoesToTheRequestAndRemovesThePrompt() = runTest {
        val host = startUi()
        val prompt = prompt()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(prompt, TIMEOUT)
        }

        assertEquals(listOf(prompt), stateManager.keyboardInteractivePrompts.value)
        stateManager.answerKeyboardInteractive(prompt, KeyboardInteractiveOutcome.Answered(listOf("hunter2", "123456")))

        val answered = assertIs<KeyboardInteractiveOutcome.Answered>(request.await())
        assertEquals(listOf("hunter2", "123456"), answered.answers)
        assertFalse(answered.toString().contains("hunter2"), "The text of an answer shows the answers")
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
        host.cancel()
    }

    @Test
    fun noAnswerInTheTimeLimitGivesTimedOutAndALateAnswerHasNoEffect() = runTest {
        val host = startUi()
        val prompt = prompt()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(prompt, TIMEOUT)
        }

        advanceTimeBy(TIMEOUT + 1.seconds)
        runCurrent()

        assertSame(KeyboardInteractiveOutcome.TimedOut, request.await())
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
        stateManager.answerKeyboardInteractive(prompt, KeyboardInteractiveOutcome.Cancelled)
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
        host.cancel()
    }

    @Test
    fun anOpenPromptStaysOpenWhenTheUiStopsAndANewRequestThenGetsNoUi() = runTest {
        val host = startUi()
        val prompt = prompt()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(prompt, TIMEOUT)
        }

        // The user opens a different app, for example to read a one-time code.
        host.cancel()
        runCurrent()

        // The stop of the UI ends the wait of a biometric prompt, but the questions stay open.
        assertEquals(listOf(prompt), stateManager.keyboardInteractivePrompts.value)
        assertFalse(request.isCompleted)
        assertSame(KeyboardInteractiveOutcome.NoUi, stateManager.requestKeyboardInteractive(prompt(), TIMEOUT))

        // The user comes back and answers.
        val answer = KeyboardInteractiveOutcome.Answered(listOf("123456"))
        stateManager.answerKeyboardInteractive(prompt, answer)
        assertSame(answer, request.await())
    }

    @Test
    fun aCancelledRequestRemovesItsPrompt() = runTest {
        val host = startUi()
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(prompt(), TIMEOUT)
        }
        assertEquals(1, stateManager.keyboardInteractivePrompts.value.size)

        request.cancel()
        runCurrent()

        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
        host.cancel()
    }

    @Test
    fun aCancelOfTheUserAuthOfAMachineCancelsItsQuestionsOnly() = runTest {
        val host = startUi()
        val workbox = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(prompt(machineId = "workbox"), TIMEOUT)
        }
        val gpuRig = prompt(machineId = "gpu-rig")
        val gpuRigRequest = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(gpuRig, TIMEOUT)
        }

        // A disconnect of workbox arrives before its request goes into the control queue.
        stateManager.cancelUserAuth("workbox")

        assertSame(KeyboardInteractiveOutcome.Cancelled, workbox.await())
        assertEquals(listOf(gpuRig), stateManager.keyboardInteractivePrompts.value)

        // A disconnect of all machines cancels the other prompts.
        stateManager.cancelAllUserAuth()

        assertSame(KeyboardInteractiveOutcome.Cancelled, gpuRigRequest.await())
        assertTrue(stateManager.keyboardInteractivePrompts.value.isEmpty())
        host.cancel()
    }

    @Test
    fun thePromptsWaitInTheSequenceOfTheRequests() = runTest {
        val host = startUi()
        val first = prompt(machineId = "workbox")
        val second = prompt(machineId = "gpu-rig")
        val firstRequest = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(first, TIMEOUT)
        }
        val secondRequest = async(start = CoroutineStart.UNDISPATCHED) {
            stateManager.requestKeyboardInteractive(second, TIMEOUT)
        }

        assertEquals(listOf(first, second), stateManager.keyboardInteractivePrompts.value)
        stateManager.answerKeyboardInteractive(first, KeyboardInteractiveOutcome.Cancelled)
        assertEquals(listOf(second), stateManager.keyboardInteractivePrompts.value)
        stateManager.answerKeyboardInteractive(second, KeyboardInteractiveOutcome.Cancelled)

        assertSame(KeyboardInteractiveOutcome.Cancelled, firstRequest.await())
        assertSame(KeyboardInteractiveOutcome.Cancelled, secondRequest.await())
        host.cancel()
    }

    /** Acts as the started activity: it runs [SshStateManager.hostUserAuthPrompts] until the test cancels it. */
    private fun TestScope.startUi(): Job =
        launch(start = CoroutineStart.UNDISPATCHED) { stateManager.hostUserAuthPrompts { UserAuthOutcome.Cancelled } }

    private fun prompt(machineId: String = "workbox") = KeyboardInteractivePrompt(
        machineId = machineId,
        machineName = machineId,
        hostPort = "$machineId.lan:22",
        name = "",
        instruction = "",
        questions = listOf(
            KeyboardInteractiveQuestion(text = "Password: ", echo = false),
            KeyboardInteractiveQuestion(text = "Code: ", echo = true),
        ),
    )

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
