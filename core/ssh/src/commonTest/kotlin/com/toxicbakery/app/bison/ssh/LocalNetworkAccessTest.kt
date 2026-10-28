package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests the requests for the local network permission: [LocalNetworkAccess.request] and
 * [LocalNetworkAccess.hostRequests].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkAccessTest {

    private val access = LocalNetworkAccess()

    /** The host and port of each request that the UI of [startUi] got, in order. */
    private val shown = mutableListOf<String>()

    /** Starts a UI that records each request and answers with [answer]. Returns the job of the UI. */
    private fun TestScope.startUi(answer: suspend (LocalNetworkRequest) -> LocalNetworkOutcome) =
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            access.hostRequests { request ->
                shown += request.hostPort
                answer(request)
            }
        }

    @Test
    fun withNoUiTheRequestReturnsNoUiAtOnceAndPutsNoRequest() = runTest {
        assertEquals(LocalNetworkOutcome.NoUi, access.request(MACHINE_ID, HOST_PORT, TIMEOUT))
        assertNull(access.openRequest.value)
    }

    @Test
    fun theUiGivesTheAnswerAndTheRequestCloses() = runTest {
        startUi { LocalNetworkOutcome.Granted }

        assertEquals(LocalNetworkOutcome.Granted, access.request(MACHINE_ID, HOST_PORT, TIMEOUT))
        assertEquals(listOf(HOST_PORT), shown)
        assertNull(access.openRequest.value)
    }

    @Test
    fun aDenialIsTheAnswerOfTheRequest() = runTest {
        startUi { LocalNetworkOutcome.Denied }

        assertEquals(LocalNetworkOutcome.Denied, access.request(MACHINE_ID, HOST_PORT, TIMEOUT))
    }

    @Test
    fun twoRequestsAtTheSameTimeShareOneDialogAndOneAnswer() = runTest {
        val answer = CompletableDeferred<LocalNetworkOutcome>()
        startUi { answer.await() }

        val first = async { access.request(MACHINE_ID, HOST_PORT, TIMEOUT) }
        val second = async { access.request("gpu-rig", "10.0.0.7:22", TIMEOUT) }
        runCurrent()
        answer.complete(LocalNetworkOutcome.Granted)

        assertEquals(LocalNetworkOutcome.Granted, first.await())
        assertEquals(LocalNetworkOutcome.Granted, second.await())
        assertEquals(listOf(HOST_PORT), shown)
    }

    @Test
    fun aRequestWithNoAnswerTimesOutAndTheUiStopsTheDialog() = runTest {
        var dialogCancelled = false
        startUi {
            try {
                awaitCancellation()
            } finally {
                dialogCancelled = true
            }
        }

        assertEquals(LocalNetworkOutcome.TimedOut, access.request(MACHINE_ID, HOST_PORT, TIMEOUT))
        runCurrent()
        assertTrue(dialogCancelled)
        assertNull(access.openRequest.value)
    }

    @Test
    fun theStopOfTheLastUiCancelsTheOpenRequest() = runTest {
        val ui = startUi { awaitCancellation() }
        val result = async { access.request(MACHINE_ID, HOST_PORT, TIMEOUT) }
        runCurrent()

        ui.cancel()

        assertEquals(LocalNetworkOutcome.Cancelled, result.await())
        // With no UI, the next request does not ask.
        assertEquals(LocalNetworkOutcome.NoUi, access.request(MACHINE_ID, HOST_PORT, TIMEOUT))
    }

    @Test
    fun aCancelEndsTheWaitOfOneMachineAndTheOtherMachineGetsTheAnswer() = runTest {
        val answer = CompletableDeferred<LocalNetworkOutcome>()
        startUi { answer.await() }
        val first = async { access.request(MACHINE_ID, HOST_PORT, TIMEOUT) }
        val second = async { access.request("gpu-rig", "10.0.0.7:22", TIMEOUT) }
        runCurrent()

        access.cancel(MACHINE_ID)

        assertEquals(LocalNetworkOutcome.Cancelled, first.await())
        // The dialog stays open for the other machine.
        assertEquals(HOST_PORT, access.openRequest.value?.hostPort)
        answer.complete(LocalNetworkOutcome.Granted)
        assertEquals(LocalNetworkOutcome.Granted, second.await())
    }

    @Test
    fun aCancelOfTheLastMachineClosesTheDialog() = runTest {
        var dialogCancelled = false
        startUi {
            try {
                awaitCancellation()
            } finally {
                dialogCancelled = true
            }
        }
        val result = async { access.request(MACHINE_ID, HOST_PORT, TIMEOUT) }
        runCurrent()

        access.cancel("unknown-box")
        access.cancelAll()

        assertEquals(LocalNetworkOutcome.Cancelled, result.await())
        runCurrent()
        assertTrue(dialogCancelled)
        assertNull(access.openRequest.value)
    }

    @Test
    fun aNewWaitOfTheSameMachineEndsItsOlderWait() = runTest {
        val answer = CompletableDeferred<LocalNetworkOutcome>()
        startUi { answer.await() }
        val older = async { access.request(MACHINE_ID, HOST_PORT, TIMEOUT) }
        runCurrent()

        val newer = async { access.request(MACHINE_ID, HOST_PORT, TIMEOUT) }
        runCurrent()

        assertEquals(LocalNetworkOutcome.Cancelled, older.await())
        answer.complete(LocalNetworkOutcome.Denied)
        assertEquals(LocalNetworkOutcome.Denied, newer.await())
    }

    @Test
    fun aUiThatThrowsGivesADenial() = runTest {
        startUi { throw IllegalStateException("No activity") }

        assertEquals(LocalNetworkOutcome.Denied, access.request(MACHINE_ID, HOST_PORT, TIMEOUT))
    }

    private companion object {
        const val MACHINE_ID = "lan-box"
        const val HOST_PORT = "192.168.1.42:22"
        val TIMEOUT = 60.seconds
    }
}
