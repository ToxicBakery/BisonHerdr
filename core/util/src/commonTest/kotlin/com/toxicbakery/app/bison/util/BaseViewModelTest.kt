package com.toxicbakery.app.bison.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BaseViewModelTest {

    private data class CounterState(val count: Int = 0)

    private sealed interface CounterEvent {
        data object Increment : CounterEvent
        data class Add(val amount: Int) : CounterEvent
        data object NotifyDone : CounterEvent
    }

    private sealed interface CounterAction {
        data class Done(val finalCount: Int) : CounterAction
    }

    private class CounterViewModel : BaseViewModel<CounterState, CounterEvent, CounterAction>(CounterState()) {
        override fun onEvent(event: CounterEvent) {
            when (event) {
                CounterEvent.Increment -> mutateState { current -> current.copy(count = current.count + 1) }
                is CounterEvent.Add -> updateState { current -> current.copy(count = current.count + event.amount) }
                CounterEvent.NotifyDone -> emitAction(CounterAction.Done(state.value.count))
            }
        }
    }

    @Test
    fun `mutateState and updateState update state atomically`() {
        val viewModel = CounterViewModel()
        assertEquals(0, viewModel.state.value.count)

        viewModel.onEvent(CounterEvent.Increment)
        assertEquals(1, viewModel.state.value.count)

        viewModel.onEvent(CounterEvent.Add(4))
        assertEquals(5, viewModel.state.value.count)
    }

    @Test
    fun `emitAction publishes action to actions flow`() = runTest {
        val viewModel = CounterViewModel()
        val deferredAction = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.actions.first()
        }
        viewModel.onEvent(CounterEvent.Add(7))
        viewModel.onEvent(CounterEvent.NotifyDone)

        val action = deferredAction.await()
        assertEquals(CounterAction.Done(7), action)
    }

    @Test
    fun `an action before the first collector is not lost`() = runTest {
        val viewModel = CounterViewModel()
        viewModel.onEvent(CounterEvent.Add(3))
        viewModel.onEvent(CounterEvent.NotifyDone)

        // For example, the screen starts to collect after the view model sent the action.
        assertEquals(CounterAction.Done(3), viewModel.actions.first())
    }
}
