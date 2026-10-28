package com.toxicbakery.app.bison.ssh

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Supervises the session of one connected machine and runs its reconnect loop. [RealHerdrSshClient] makes one
 * supervisor for each machine that completed a connect, and no other part of the client opens a session for it.
 *
 * The supervisor starts in [State.Live]. When the session closes ([sessionLost]) or a liveness check fails
 * ([reconnectNow]), the supervisor starts the loop in [scope]:
 * 1. It calls [attempt] at once. The attempt closes the old session and opens a new session.
 * 2. [SshAuthResult.Success] ends the loop in [State.Live].
 * 3. [SshAuthResult.NetworkError] starts the next attempt after the delay from [delayAfter]. The loop has no upper
 *    count, because the machine stays connected.
 * 4. Each other result ends the loop in [State.NeedsUser]: a host key rejection, a failed login, a missing key, and
 *    each result that a later version adds. A new attempt gives the same result until the user does an action.
 *    Only a new connect from the user makes a new supervisor.
 *
 * [events] gets each step of the loop. [isSessionLive] tells if the client has an open session, so that
 * [sessionLost] ignores a report about a session that closed before the last reconnect.
 */
internal class SessionSupervisor(
    private val machineId: String,
    private val scope: CoroutineScope,
    private val delayAfter: (failures: Int) -> Duration,
    private val isSessionLive: () -> Boolean,
    private val attempt: suspend () -> SshAuthResult,
    private val events: SessionEvents,
) {

    /** The state of the session of the machine. */
    sealed interface State {
        /** The session is open. */
        data object Live : State

        /**
         * The loop runs. [failures] is the number of failed attempts since the start of the loop or since the last
         * [reconnectNow]. [attemptRunning] is true while an attempt runs, and false while the loop waits.
         */
        data class Reconnecting(val failures: Int, val attemptRunning: Boolean) : State

        /** The loop stopped, because [result] needs the user. */
        data class NeedsUser(val result: SshAuthResult) : State

        /** [stop] ended the supervisor. */
        data object Stopped : State
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow<State>(State.Live)

    /** The current state. Only the supervisor changes it. */
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** A signal from [reconnectNow] that stops the delay between two attempts. */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    // Only a block that holds [lock] reads and writes this field.
    private var loop: Job? = null

    /**
     * Tells the supervisor that the session closed. [cause] is the error that showed the loss, or null.
     *
     * In [State.Live], this starts the loop with an attempt at once, if [isSessionLive] agrees that the session is
     * closed. In all other states, this does nothing.
     */
    fun sessionLost(cause: Throwable? = null) {
        val started = synchronized(lock) {
            mutableState.value == State.Live && !isSessionLive() && startLoopLocked()
        }
        if (started) events.onReconnecting(machineId, cause)
    }

    /**
     * Starts an attempt now, with no delay. A failed liveness check calls this function after a network change.
     *
     * In [State.Live], this starts the loop, also when the client still thinks that the session is open, because
     * the check showed that it does not work. While the loop waits, this stops the delay, and the delays start again
     * from the first delay. In [State.NeedsUser] and [State.Stopped], this does nothing.
     */
    fun reconnectNow() {
        val started = synchronized(lock) {
            when (mutableState.value) {
                State.Live -> startLoopLocked()
                is State.Reconnecting -> {
                    wakeUp.trySend(Unit)
                    false
                }
                is State.NeedsUser, State.Stopped -> false
            }
        }
        if (started) events.onReconnecting(machineId, null)
    }

    /**
     * Suspends while the loop runs. Returns true when the session is live, and false when the loop stopped for the
     * user or [stop] ended the supervisor.
     */
    suspend fun awaitLive(): Boolean = state.first { it !is State.Reconnecting } == State.Live

    /**
     * Waits only for the attempt that runs now, for a user input. Returns true when the session is live.
     *
     * While the loop waits between two attempts, this function returns false at once. Thus a user input does not
     * wait for a long delay of the loop, and it does not block the requests that come after it.
     */
    suspend fun awaitCurrentAttempt(): Boolean {
        val current = state.value
        if (current is State.Reconnecting && !current.attemptRunning) return false
        return state.first { it !is State.Reconnecting || !it.attemptRunning } == State.Live
    }

    /** Stops the loop and ends the supervisor. The state then stays [State.Stopped]. */
    fun stop() {
        synchronized(lock) {
            loop?.cancel()
            loop = null
            mutableState.value = State.Stopped
        }
    }

    /** Starts the loop and returns true. Call it only while the state is [State.Live] and [lock] is held. */
    private fun startLoopLocked(): Boolean {
        mutableState.value = State.Reconnecting(failures = 0, attemptRunning = true)
        // Remove a signal of an earlier loop.
        wakeUp.tryReceive()
        loop = scope.launch { runLoop() }
        return true
    }

    private suspend fun runLoop() {
        var failures = 0
        while (true) {
            if (!setState(State.Reconnecting(failures, attemptRunning = true))) return
            when (val result = runAttempt()) {
                SshAuthResult.Success -> {
                    if (setState(State.Live)) events.onReconnected(machineId)
                    return
                }

                is SshAuthResult.NetworkError -> {
                    failures++
                    val nextDelay = delayAfter(failures)
                    if (!setState(State.Reconnecting(failures, attemptRunning = false))) return
                    events.onReconnectFailed(machineId, failures, result, nextDelay)
                    // A signal from reconnectNow stops the delay, and the delays start again from the first delay.
                    if (withTimeoutOrNull(nextDelay) { wakeUp.receive() } != null) failures = 0
                }

                // All other results need the user. This includes each result that a later version adds.
                else -> {
                    if (setState(State.NeedsUser(result))) events.onNeedsUser(machineId, result)
                    return
                }
            }
        }
    }

    /** Runs [attempt]. An unexpected error from the attempt is [SshAuthResult.Failed], so that the loop stops. */
    private suspend fun runAttempt(): SshAuthResult =
        try {
            attempt()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SshAuthResult.Failed(error)
        }

    /** Sets [next] and returns true. After [stop], this changes nothing and returns false. */
    private fun setState(next: State): Boolean = synchronized(lock) {
        if (mutableState.value == State.Stopped) {
            false
        } else {
            mutableState.value = next
            true
        }
    }
}
