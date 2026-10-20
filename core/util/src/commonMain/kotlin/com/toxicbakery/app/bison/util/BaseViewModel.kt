package com.toxicbakery.app.bison.util

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val mutableActions: MutableSharedFlow<Action> = MutableSharedFlow(extraBufferCapacity = 64)
    val actions: SharedFlow<Action> = mutableActions.asSharedFlow()

    abstract fun onEvent(event: Event)

    protected fun mutateState(mutation: StateMutation<State>) {
        mutableState.update { current -> mutation.reduce(current) }
    }

    protected fun updateState(transform: (State) -> State) {
        mutableState.update(transform)
    }

    protected fun emitAction(action: Action) {
        mutableActions.tryEmit(action)
    }

    protected suspend fun sendAction(action: Action) {
        mutableActions.emit(action)
    }
}
