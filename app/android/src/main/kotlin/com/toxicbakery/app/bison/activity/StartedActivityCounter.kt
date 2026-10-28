package com.toxicbakery.app.bison.activity

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
 */
class StartedActivityCounter {

    private val startedCount = MutableStateFlow(0)

    /** Is true while one or more activities are started. */
    val anyStarted: Flow<Boolean> = startedCount
        .map { count -> count > 0 }
        .distinctUntilChanged()

    fun onActivityStarted() {
        startedCount.update { count -> count + 1 }
    }

    fun onActivityStopped() {
        startedCount.update { count -> (count - 1).coerceAtLeast(0) }
    }
}
