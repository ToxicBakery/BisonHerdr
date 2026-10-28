package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KnownHost
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests the reconnect loop of [SessionSupervisor] in virtual time. The attempts are a script of results, so the
 * tests open no connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSupervisorTest {

    private val events = RecordingEvents()
    private val networkError = SshAuthResult.NetworkError(ConnectException("Connection refused"))

    /** The answer of `isSessionLive` for the supervisor. */
    @Volatile
    private var sessionLive = false

    @Test
    fun networkErrorsRetryWithTheBackoffDelaysUntilASuccess() = runTest {
        val attempts = ScriptedAttempts(this, List(7) { networkError } + SshAuthResult.Success)
        val supervisor = supervisor(attempts)

        supervisor.sessionLost(IOException("closed"))
        assertEquals(SessionSupervisor.State.Reconnecting(failures = 0, attemptRunning = true), supervisor.state.value)
        // The loop runs in the background scope, and advanceUntilIdle does not run background work.
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(SessionSupervisor.State.Live, supervisor.state.value)
        // Delays of 1, 2, 4, 8, 16, 30, and 30 s between the attempts.
        assertEquals(listOf(0L, 1, 3, 7, 15, 31, 61, 91).map { it * 1_000 }, attempts.times)
        assertEquals(
            listOf("reconnecting") +
                listOf(1, 2, 4, 8, 16, 30, 30).mapIndexed { index, delay -> "failed ${index + 1} ${delay.seconds}" } +
                "reconnected",
            events.log,
        )
    }

    @Test
    fun aFailedLoginStopsTheLoopAfterOneAttempt() = runTest {
        val refused = SshAuthResult.AuthFailed("Auth fail")
        val attempts = ScriptedAttempts(this, listOf(refused))
        val supervisor = supervisor(attempts)

        supervisor.sessionLost()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(SessionSupervisor.State.NeedsUser(refused), supervisor.state.value)
        assertEquals(1, attempts.times.size)
        assertEquals(listOf("reconnecting", "needs user $refused"), events.log)
    }

    @Test
    fun eachResultThatIsNotANetworkErrorNeedsTheUser() = runTest {
        val key = KnownHost(
            host = "devbox",
            port = 22,
            keyType = "ssh-ed25519",
            fingerprint = "SHA256:a",
            publicKey = "AAAA",
        )
        val results = listOf(
            SshAuthResult.HostKeyUnknown(key),
            SshAuthResult.HostKeyChanged(known = key, presented = key.copy(fingerprint = "SHA256:b")),
            SshAuthResult.KeyMissing("work_key"),
            SshAuthResult.Failed(IllegalStateException("Algorithm negotiation fail")),
        )

        results.forEach { result ->
            val attempts = ScriptedAttempts(this, listOf(result))
            val supervisor = supervisor(attempts)

            supervisor.sessionLost()
            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals(SessionSupervisor.State.NeedsUser(result), supervisor.state.value)
            assertEquals(1, attempts.times.size, "The loop tried $result again")
        }
    }

    @Test
    fun anAttemptThatThrowsNeedsTheUser() = runTest {
        val supervisor = supervisor { throw IllegalStateException("A bug in the attempt") }

        supervisor.sessionLost()
        runCurrent()

        val state = assertIs<SessionSupervisor.State.NeedsUser>(supervisor.state.value)
        assertIs<SshAuthResult.Failed>(state.result)
    }

    @Test
    fun reconnectNowStopsTheDelayAndStartsTheDelaysAgain() = runTest {
        val attempts = ScriptedAttempts(this, List(5) { networkError } + SshAuthResult.Success)
        val supervisor = supervisor(attempts)
        supervisor.sessionLost()
        // Attempts at 0, 1, 3, and 7 s. The loop then waits 8 s.
        advanceTimeBy(8.seconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000, 3_000, 7_000), attempts.times)

        supervisor.reconnectNow()
        runCurrent()

        assertEquals(8_000L, attempts.times.last())
        // The next failure waits 1 s again, not 16 s.
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(0L, 1_000, 3_000, 7_000, 8_000, 9_000), attempts.times)
        assertEquals(SessionSupervisor.State.Live, supervisor.state.value)
    }

    @Test
    fun reconnectNowStartsTheLoopAlsoWhenTheClientThinksTheSessionIsOpen() = runTest {
        sessionLive = true
        val attempts = ScriptedAttempts(this, listOf(SshAuthResult.Success))
        val supervisor = supervisor(attempts)

        supervisor.reconnectNow()
        runCurrent()

        assertEquals(listOf(0L), attempts.times)
        assertEquals(listOf("reconnecting", "reconnected"), events.log)
        assertEquals(SessionSupervisor.State.Live, supervisor.state.value)
    }

    @Test
    fun aLossReportForALiveSessionDoesNothing() = runTest {
        sessionLive = true
        val attempts = ScriptedAttempts(this, listOf(SshAuthResult.Success))
        val supervisor = supervisor(attempts)

        // For example, a command saw the old session after the loop opened a new session.
        supervisor.sessionLost()
        runCurrent()

        assertTrue(attempts.times.isEmpty())
        assertTrue(events.log.isEmpty())
    }

    @Test
    fun onlyANewSupervisorLeavesTheNeedsUserState() = runTest {
        val refused = SshAuthResult.AuthFailed("Auth fail")
        val attempts = ScriptedAttempts(this, listOf(refused, SshAuthResult.Success))
        val supervisor = supervisor(attempts)
        supervisor.sessionLost()
        runCurrent()

        supervisor.sessionLost()
        supervisor.reconnectNow()
        runCurrent()

        assertEquals(1, attempts.times.size)
        assertEquals(SessionSupervisor.State.NeedsUser(refused), supervisor.state.value)
    }

    @Test
    fun aUserInputWaitsOnlyForTheAttemptThatRunsNow() = runTest {
        val gate = CompletableDeferred<SshAuthResult>()
        var calls = 0
        val supervisor = supervisor {
            calls++
            if (calls == 1) gate.await() else networkError
        }
        supervisor.sessionLost()
        runCurrent()

        // The first attempt runs, so the input waits for its result.
        val duringAttempt = async { supervisor.awaitCurrentAttempt() }
        runCurrent()
        assertFalse(duringAttempt.isCompleted)
        gate.complete(networkError)
        runCurrent()
        assertFalse(duringAttempt.await())

        // The loop now waits 1 s, so the input fails at once.
        assertEquals(SessionSupervisor.State.Reconnecting(failures = 1, attemptRunning = false), supervisor.state.value)
        assertFalse(supervisor.awaitCurrentAttempt())
    }

    @Test
    fun aUserInputGetsTheSessionWhenTheAttemptPasses() = runTest {
        val gate = CompletableDeferred<SshAuthResult>()
        val supervisor = supervisor { gate.await() }
        supervisor.sessionLost()
        runCurrent()

        val input = async { supervisor.awaitCurrentAttempt() }
        runCurrent()
        gate.complete(SshAuthResult.Success)

        assertTrue(input.await())
    }

    @Test
    fun thePollWaitsForTheWholeLoop() = runTest {
        val attempts = ScriptedAttempts(this, List(3) { networkError } + SshAuthResult.Success)
        val supervisor = supervisor(attempts)
        supervisor.sessionLost()

        val poll = async { supervisor.awaitLive() }
        advanceTimeBy(6.seconds)
        runCurrent()
        assertFalse(poll.isCompleted, "The poll did not wait for the loop")
        advanceTimeBy(2.seconds)
        runCurrent()

        assertTrue(poll.await())
        assertEquals(listOf(0L, 1_000, 3_000, 7_000), attempts.times)
    }

    @Test
    fun thePollStopsWhenTheLoopNeedsTheUser() = runTest {
        val supervisor = supervisor(ScriptedAttempts(this, listOf(networkError, SshAuthResult.KeyMissing("work_key"))))
        supervisor.sessionLost()

        val poll = async { supervisor.awaitLive() }
        advanceTimeBy(10.minutes)
        runCurrent()

        assertFalse(poll.await())
    }

    @Test
    fun stopEndsTheLoopAndThePoll() = runTest {
        val attempts = ScriptedAttempts(this, emptyList())
        val supervisor = supervisor(attempts)
        supervisor.sessionLost()
        advanceTimeBy(2.seconds)
        runCurrent()
        val poll = async { supervisor.awaitLive() }

        supervisor.stop()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertFalse(poll.await())
        assertEquals(listOf(0L, 1_000), attempts.times)
        assertEquals(SessionSupervisor.State.Stopped, supervisor.state.value)
    }

    private fun TestScope.supervisor(attempts: ScriptedAttempts): SessionSupervisor = supervisor(attempts::next)

    private fun TestScope.supervisor(attempt: suspend () -> SshAuthResult): SessionSupervisor =
        SessionSupervisor(
            machineId = MACHINE_ID,
            scope = backgroundScope,
            delayAfter = ::noJitterDelay,
            isSessionLive = { sessionLive },
            attempt = attempt,
            events = events,
        )

    /** The delays of [ReconnectBackoff] with no jitter. */
    private fun noJitterDelay(failures: Int): Duration =
        ReconnectBackoff.BASE_DELAYS[(failures - 1).coerceAtMost(ReconnectBackoff.BASE_DELAYS.lastIndex)]

    /** Gives [results] in turn, and then network errors. Records the virtual time of each attempt in [times]. */
    private inner class ScriptedAttempts(private val testScope: TestScope, results: List<SshAuthResult>) {
        val times: MutableList<Long> = CopyOnWriteArrayList()
        private val remaining = ArrayDeque(results)

        fun next(): SshAuthResult {
            times += testScope.testScheduler.currentTime
            return remaining.removeFirstOrNull() ?: networkError
        }
    }

    /** Records each event as a short text. */
    private class RecordingEvents : SessionEvents {
        val log: MutableList<String> = CopyOnWriteArrayList()

        override fun onReconnecting(machineId: String, cause: Throwable?) {
            log += "reconnecting"
        }

        override fun onReconnectFailed(
            machineId: String,
            failures: Int,
            result: SshAuthResult.NetworkError,
            nextDelay: Duration,
        ) {
            log += "failed $failures $nextDelay"
        }

        override suspend fun onReconnected(machineId: String) {
            log += "reconnected"
        }

        override suspend fun onNeedsUser(machineId: String, result: SshAuthResult) {
            log += "needs user $result"
        }
    }

    private companion object {
        const val MACHINE_ID = "workbox"
    }
}
