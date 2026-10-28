package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class ControlRequestRunnerTest {

    private val stateManager = SshStateManager()
    private val connect = SshRequest.Connect("workbox")

    private fun progress(): ConnectionProgress? = stateManager.connectionProgress.value["workbox"]

    @Test
    fun aConnectThatThrowsAtAConnectStepEndsAsFailed() = runTest {
        val error = stateManager.runControlRequest(connect) {
            stateManager.setConnectionProgress("workbox", ConnectionProgress.SyncingHerdrSnapshot)
            throw IOException("The network is gone")
        }

        assertIs<IOException>(error)
        assertEquals(ConnectionProgress.Failed, progress())
    }

    @Test
    fun aCancelledConnectAtAConnectStepEndsAsFailed() = runTest {
        val atStep = CompletableDeferred<Unit>()
        val job = launch {
            stateManager.runControlRequest(connect) {
                stateManager.setConnectionProgress("workbox", ConnectionProgress.AuthenticatingSsh)
                atStep.complete(Unit)
                awaitCancellation()
            }
        }
        atStep.await()

        // For example, the service stops and cancels its scope.
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(ConnectionProgress.Failed, progress())
    }

    @Test
    fun aTimeoutInsideARequestIsAFailedRequestAndTheLoopContinues() = runTest {
        val requests = listOf(connect, SshRequest.Disconnect("workbox"))
        val handled = mutableListOf<SshRequest>()
        val errors = mutableListOf<Throwable>()

        // The same loop as the control queue of the service.
        for (request in requests) {
            stateManager.runControlRequest(request) {
                if (it is SshRequest.Connect) {
                    stateManager.setConnectionProgress("workbox", ConnectionProgress.AuthenticatingSsh)
                    withTimeout(TIMEOUT_MS) { awaitCancellation() }
                }
                handled += it
            }?.let(errors::add)
        }

        assertIs<TimeoutCancellationException>(errors.single())
        assertEquals(listOf<SshRequest>(SshRequest.Disconnect("workbox")), handled)
        assertEquals(ConnectionProgress.Failed, progress())
    }

    @Test
    fun aCancellationExceptionWhileTheCallerIsActiveIsAFailedRequest() = runTest {
        val error = stateManager.runControlRequest(SshRequest.Disconnect("workbox")) {
            throw CancellationException("A request cancelled its own work")
        }

        assertIs<CancellationException>(error)
    }

    @Test
    fun rethrowIfCancelledThrowsOnlyForACancelledCaller() = runTest {
        val error = CancellationException("stop")
        // The caller is active, so the function does not throw.
        rethrowIfCancelled(error)
        rethrowIfCancelled(IOException("not a cancellation"))

        var thrown: Throwable? = null
        val cancelledCaller = launch {
            try {
                awaitCancellation()
            } finally {
                thrown = runCatching { rethrowIfCancelled(error) }.exceptionOrNull()
            }
        }
        runCurrent()
        cancelledCaller.cancel()
        runCurrent()

        assertSame(error, thrown)
    }

    @Test
    fun aConnectThatCompletesKeepsItsProgress() = runTest {
        val error = stateManager.runControlRequest(connect) {
            stateManager.markMachineConnected("workbox", ActiveBackbone.SshEndpoint(rttMs = 10))
        }

        assertNull(error)
        assertEquals(ConnectionProgress.Connected, progress())
    }

    @Test
    fun aConnectThatThrowsAfterTheConnectKeepsItsProgress() = runTest {
        stateManager.runControlRequest(connect) {
            stateManager.markMachineConnected("workbox", ActiveBackbone.SshEndpoint(rttMs = 10))
            throw IOException("A later step failed")
        }

        assertEquals(ConnectionProgress.Connected, progress())
    }

    @Test
    fun anotherRequestThatThrowsDoesNotChangeTheProgress() = runTest {
        stateManager.setConnectionProgress("workbox", ConnectionProgress.VerifyingUdpOrHello)

        val error = stateManager.runControlRequest(SshRequest.Disconnect("workbox")) {
            throw IOException("The disconnect failed")
        }

        assertIs<IOException>(error)
        assertEquals(ConnectionProgress.VerifyingUdpOrHello, progress())
    }

    private companion object {
        const val TIMEOUT_MS = 10L
    }
}
