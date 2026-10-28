package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests the user authentication prompts of [SshStateManager]: [SshStateManager.requestUserAuth] and
 * [SshStateManager.hostUserAuthPrompts].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserAuthPromptTest {

    private val stateManager = SshStateManager()

    /** The prompts that the UI of [startUi] got, in order. */
    private val shown = mutableListOf<UserAuthPrompt>()

    /** Starts a UI that records each prompt and answers with [answer]. Returns the job of the UI. */
    private fun TestScope.startUi(answer: suspend (UserAuthPrompt) -> UserAuthOutcome) =
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.hostUserAuthPrompts { prompt ->
                shown += prompt
                answer(prompt)
            }
        }

    private suspend fun request(machineId: String = MACHINE_ID): UserAuthOutcome =
        stateManager.requestUserAuth(
            machineId = machineId,
            machineName = MACHINE_NAME,
            keyAlias = KEY_ALIAS,
            timeoutMs = TIMEOUT_MS,
        )

    @Test
    fun confirmedPromptGivesConfirmedAndClosesThePrompt() = runTest {
        startUi { UserAuthOutcome.Confirmed }

        assertEquals(UserAuthOutcome.Confirmed, request())

        val prompt = shown.single()
        assertEquals(MACHINE_ID, prompt.machineId)
        assertEquals(MACHINE_NAME, prompt.machineName)
        assertEquals(KEY_ALIAS, prompt.keyAlias)
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun cancelledPromptGivesCancelled() = runTest {
        startUi { UserAuthOutcome.Cancelled }

        assertEquals(UserAuthOutcome.Cancelled, request())
        assertEquals(1, shown.size)
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun failedPromptGivesTheReason() = runTest {
        startUi { UserAuthOutcome.Failed("Too many attempts") }

        assertEquals(UserAuthOutcome.Failed("Too many attempts"), request())
    }

    @Test
    fun anErrorInTheUiGivesFailed() = runTest {
        startUi { error("No fragment manager") }

        assertEquals(UserAuthOutcome.Failed("No fragment manager"), request())
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun withoutAUiTheRequestReturnsNoUiAtOnceAndPutsNoPrompt() = runTest {
        val seen = mutableListOf<List<UserAuthPrompt>>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateManager.userAuthPrompts.collect { seen += it }
        }

        assertEquals(UserAuthOutcome.NoUi, request())

        assertEquals(0L, currentTime, "The request waited")
        assertEquals(listOf(emptyList()), seen)
    }

    @Test
    fun aStoppedUiIsTheSameAsNoUi() = runTest {
        val ui = startUi { UserAuthOutcome.Confirmed }
        ui.cancel()
        runCurrent()

        assertEquals(UserAuthOutcome.NoUi, request())
        assertTrue(shown.isEmpty())
    }

    @Test
    fun noAnswerTimesOutAndCancelsTheShownPrompt() = runTest {
        val uiCancelled = CompletableDeferred<Unit>()
        startUi {
            try {
                awaitCancellation()
            } finally {
                uiCancelled.complete(Unit)
            }
        }

        assertEquals(UserAuthOutcome.TimedOut, request())

        assertEquals(TIMEOUT_MS, currentTime)
        runCurrent()
        assertTrue(uiCancelled.isCompleted, "The UI still shows the prompt")
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun aUiThatStopsDuringThePromptGivesCancelled() = runTest {
        val ui = startUi { awaitCancellation() }
        val outcome = async { request() }
        runCurrent()
        assertEquals(1, shown.size)

        ui.cancel()

        assertEquals(UserAuthOutcome.Cancelled, outcome.await())
        assertTrue(currentTime < TIMEOUT_MS, "The request waited for the time-out")
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun cancelUserAuthEndsTheWaitOfThatMachineAtOnce() = runTest {
        startUi { awaitCancellation() }
        val workbox = async { request(machineId = "workbox") }
        val gpuRig = async { request(machineId = "gpu-rig") }
        runCurrent()

        stateManager.cancelUserAuth("workbox")

        assertEquals(UserAuthOutcome.Cancelled, workbox.await())
        assertEquals(0L, currentTime, "The request waited")
        assertEquals(listOf("gpu-rig"), stateManager.userAuthPrompts.value.map { it.machineId })
        assertTrue(gpuRig.isActive)
        gpuRig.cancel()
    }

    @Test
    fun cancelAllUserAuthEndsAllWaitsAtOnce() = runTest {
        startUi { awaitCancellation() }
        val workbox = async { request(machineId = "workbox") }
        val gpuRig = async { request(machineId = "gpu-rig") }
        runCurrent()

        stateManager.cancelAllUserAuth()

        assertEquals(UserAuthOutcome.Cancelled, workbox.await())
        assertEquals(UserAuthOutcome.Cancelled, gpuRig.await())
        assertEquals(0L, currentTime, "The requests waited")
        assertTrue(stateManager.userAuthPrompts.value.isEmpty())
    }

    @Test
    fun aNewPromptForTheSameMachineCancelsTheOldPrompt() = runTest {
        val answers = mutableMapOf<UserAuthPrompt, CompletableDeferred<UserAuthOutcome>>()
        startUi { prompt -> CompletableDeferred<UserAuthOutcome>().also { answers[prompt] = it }.await() }
        val first = async { request() }
        runCurrent()

        val second = async { request() }
        runCurrent()

        assertEquals(UserAuthOutcome.Cancelled, first.await())
        assertEquals(2, shown.size)
        assertSame(shown.last(), stateManager.userAuthPrompts.value.single())
        answers.getValue(shown.last()).complete(UserAuthOutcome.Confirmed)
        assertEquals(UserAuthOutcome.Confirmed, second.await())
    }

    @Test
    fun promptsOfTwoMachinesShowOneAfterTheOther() = runTest {
        val answers = mutableListOf<CompletableDeferred<UserAuthOutcome>>()
        startUi { CompletableDeferred<UserAuthOutcome>().also { answers += it }.await() }
        val workbox = async { request(machineId = "workbox") }
        val gpuRig = async { request(machineId = "gpu-rig") }
        runCurrent()
        assertEquals(listOf("workbox"), shown.map { it.machineId })

        answers.single().complete(UserAuthOutcome.Confirmed)
        runCurrent()

        assertEquals(UserAuthOutcome.Confirmed, workbox.await())
        assertEquals(listOf("workbox", "gpu-rig"), shown.map { it.machineId })
        answers.last().complete(UserAuthOutcome.Cancelled)
        assertEquals(UserAuthOutcome.Cancelled, gpuRig.await())
    }

    private companion object {
        const val MACHINE_ID = "workbox"
        const val MACHINE_NAME = "Workbox"
        const val KEY_ALIAS = "bison_default_ed25519"
        const val TIMEOUT_MS = 60_000L
    }
}
