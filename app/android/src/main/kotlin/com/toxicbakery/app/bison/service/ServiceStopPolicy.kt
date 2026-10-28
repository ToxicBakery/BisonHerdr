package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.model.ConnectionProgress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest

/**
 * Decides when [BisonSessionService] stops itself.
 *
 * The service is idle when no machine is connected, no connection is in progress, and no activity
 * is started. The service stops when it stays idle for [idleTimeout]. A connection, a connect step,
 * or a started activity before the end of the timeout cancels the stop. The next idle period
 * starts a new timeout.
 */
class ServiceStopPolicy(
    private val idleTimeout: Duration = IDLE_TIMEOUT,
) {

    /**
     * Suspends until the service stays idle for [idleTimeout].
     *
     * @param connectedMachines The IDs of the connected machines.
     * @param connectionProgress The connection progress of each machine. A machine in a connect
     *   step keeps the service busy, because a stop cancels the connect.
     * @param anyActivityStarted Is true while one or more activities are started.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitIdleTimeout(
        connectedMachines: Flow<Set<String>>,
        connectionProgress: Flow<Map<String, ConnectionProgress>>,
        anyActivityStarted: Flow<Boolean>,
    ) {
        combine(connectedMachines, connectionProgress, anyActivityStarted) { connected, progress, started ->
            connected.isEmpty() && progress.values.none { step -> step.isConnectStep() } && !started
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

    companion object {
        /** The time that the service stays idle before it stops. */
        val IDLE_TIMEOUT: Duration = 30.seconds

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
