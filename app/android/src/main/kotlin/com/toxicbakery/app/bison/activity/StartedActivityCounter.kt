package com.toxicbakery.app.bison.activity

import com.toxicbakery.app.bison.ssh.ForegroundState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Counts the app activities that are in the started state.
 *
 * [MainActivity] calls [onActivityStarted] and [onActivityStopped] from its lifecycle.
 * `BisonSessionService` reads [anyStarted] to decide when it can stop itself. The count is a state,
 * not a request, so a service that starts after the activity still reads the correct value.
 *
 * `SshTransportManager` reads [isInForeground] when a connect completes and for each lease request. Thus the
 * surface lease stays off while no activity is started. [MainActivity] must change the count before it sends
 * the lease requests of `onStart` and `onStop`.
 */
class StartedActivityCounter : ForegroundState {

    private val startedCount = MutableStateFlow(0)

    /** Is true while one or more activities are started. */
    val anyStarted: Flow<Boolean> = startedCount
        .map { count -> count > 0 }
        .distinctUntilChanged()

    /** Is true while one or more activities are started. It reads the count at the time of the call. */
    override fun isInForeground(): Boolean = startedCount.value > 0

    fun onActivityStarted() {
        startedCount.update { count -> count + 1 }
    }

    fun onActivityStopped() {
        startedCount.update { count -> (count - 1).coerceAtLeast(0) }
    }
}
