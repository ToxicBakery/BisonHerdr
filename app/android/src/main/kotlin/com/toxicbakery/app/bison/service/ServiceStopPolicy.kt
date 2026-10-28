package com.toxicbakery.app.bison.service

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
 * The service is idle when no machine is connected and no activity is started. The service stops
 * when it stays idle for [idleTimeout]. A connection or a started activity before the end of the
 * timeout cancels the stop. The next idle period starts a new timeout.
 */
class ServiceStopPolicy(
    private val idleTimeout: Duration = IDLE_TIMEOUT,
) {

    /**
     * Suspends until the service stays idle for [idleTimeout].
     *
     * @param connectedMachines The IDs of the connected machines.
     * @param anyActivityStarted Is true while one or more activities are started.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitIdleTimeout(
        connectedMachines: Flow<Set<String>>,
        anyActivityStarted: Flow<Boolean>,
    ) {
        combine(connectedMachines, anyActivityStarted) { connected, started ->
            connected.isEmpty() && !started
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
    }
}
