package com.toxicbakery.app.bison.util

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * Pure, side-effect-free reducer applied atomically inside [MutableStateFlow.update].
 */
fun interface StateMutation<State> {
    fun reduce(state: State): State
}

/**
 * Base MVI [ViewModel] providing unidirectional [State], [Event], and one-shot [Action] streams.
 */
abstract class BaseViewModel<State, Event, Action>(
    initialState: State,
) : ViewModel() {

    protected val mutableState: MutableStateFlow<State> = MutableStateFlow(initialState)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val actionChannel = Channel<Action>(
        capacity = ACTION_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The one-time actions, in order. Each action goes to one collector. An action that the view model sends before
     * the screen collects waits in a buffer of [ACTION_CAPACITY] actions, so that the screen does not lose it. When
     * the buffer is full, the oldest action goes away.
     */
    val actions: Flow<Action> = actionChannel.receiveAsFlow()

    abstract fun onEvent(event: Event)

    protected fun mutateState(mutation: StateMutation<State>) {
        mutableState.update { current -> mutation.reduce(current) }
    }

    protected fun updateState(transform: (State) -> State) {
        mutableState.update(transform)
    }

    protected fun emitAction(action: Action) {
        actionChannel.trySend(action)
    }

    protected suspend fun sendAction(action: Action) {
        actionChannel.send(action)
    }

    private companion object {
        /** The maximum number of actions that wait for a collector. */
        const val ACTION_CAPACITY = 64
    }
}
