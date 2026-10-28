package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.model.ConnectionProgress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Decides when [BisonSessionService] stops itself.
 *
 * The service is idle when no machine is connected, no connection is in progress, and no activity
 * is started. The service stops when it stays idle for [idleTimeout]. A connection, a connect step,
 * or a started activity before the end of the timeout cancels the stop. The next idle period
 * starts a new timeout.
 *
 * A connect step counts as busy for [connectStepLimit] from the start of that step. After that
 * time, the policy ignores the step. A connect that fails with no `Failed` progress thus cannot
 * keep the service open.
 */
class ServiceStopPolicy(
    private val idleTimeout: Duration = IDLE_TIMEOUT,
    private val connectStepLimit: Duration = CONNECT_STEP_LIMIT,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /**
     * Suspends until the service stays idle for [idleTimeout].
     *
     * @param connectedMachines The IDs of the connected machines.
     * @param connectionProgress The connection progress of each machine. A machine at a connect
     *   step keeps the service busy, because a stop cancels the connect.
     * @param anyActivityStarted Is true while one or more activities are started.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitIdleTimeout(
        connectedMachines: Flow<Set<String>>,
        connectionProgress: Flow<Map<String, ConnectionProgress>>,
        anyActivityStarted: Flow<Boolean>,
    ) {
        combine(
            connectedMachines,
            connectStepBusy(connectionProgress),
            anyActivityStarted,
        ) { connected, connecting, started ->
            connected.isEmpty() && !connecting && !started
        }
            .distinctUntilChanged()
            .transformLatest { idle ->
                if (idle) {
                    delay(idleTimeout)
                    emit(Unit)
                }
            }
            .first()
    }

    /**
     * Emits true while one or more machines are at a connect step that started less than
     * [connectStepLimit] ago.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun connectStepBusy(connectionProgress: Flow<Map<String, ConnectionProgress>>): Flow<Boolean> = flow {
        // The start of the current connect step of each machine. Only this collection uses the map,
        // and transformLatest runs one block at a time.
        val stepStarts = HashMap<String, StepStart>()
        emitAll(
            connectionProgress.transformLatest { progress ->
                stepStarts.keys.retainAll(progress.keys)
                progress.forEach { (machineId, step) ->
                    if (!step.isConnectStep()) {
                        stepStarts.remove(machineId)
                    } else if (stepStarts[machineId]?.step != step) {
                        stepStarts[machineId] = StepStart(step, timeSource.markNow())
                    }
                }
                while (true) {
                    val nextExpiry = stepStarts.values
                        .map { start -> connectStepLimit - start.mark.elapsedNow() }
                        .filter { remaining -> remaining.isPositive() }
                        .minOrNull()
                    if (nextExpiry == null) {
                        emit(false)
                        break
                    }
                    emit(true)
                    delay(nextExpiry)
                }
            }
        )
    }

    private data class StepStart(val step: ConnectionProgress, val mark: TimeMark)

    companion object {
        /** The time that the service stays idle before it stops. */
        val IDLE_TIMEOUT: Duration = 30.seconds

        /** The maximum time that one connect step keeps the service busy. */
        val CONNECT_STEP_LIMIT: Duration = 2.minutes

        /**
         * Is true for a step between the start and the end of a connect. The `when` has no `else`
         * branch, so a new [ConnectionProgress] value needs a decision here.
         */
        internal fun ConnectionProgress.isConnectStep(): Boolean = when (this) {
            ConnectionProgress.AuthenticatingSsh,
            ConnectionProgress.BootstrappingMoshOrBridge,
            ConnectionProgress.VerifyingUdpOrHello,
            ConnectionProgress.SyncingHerdrSnapshot,
            -> true

            ConnectionProgress.Idle,
            ConnectionProgress.Connected,
            ConnectionProgress.Failed,
            -> false
        }
    }
}
