package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.Event
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.InMemorySecretStore
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Tests the check of the Herdr session name in [ConfigureMachineViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureMachineHerdrSessionTest {

    private val machineState = ConfigureMachineViewModel.createInitialState("", null)
        .copy(hostname = "devbox", username = "ian")

    @Test
    fun anEmptyOrAValidSessionNameLetsTheUserSave() {
        listOf("", "   ", "work", "  work  ", "default", "a.b_c-d").forEach { name ->
            val state = machineState.copy(herdrSessionName = name)
            assertTrue(state.isHerdrSessionNameValid, name)
            assertTrue(state.canSave, name)
            assertTrue(state.canConnect, name)
        }
    }

    @Test
    fun aSessionNameThatHerdrRefusesStopsTheSave() {
        listOf(".", "..", "my work", "it's", "work;id", "a".repeat(65)).forEach { name ->
            val state = machineState.copy(herdrSessionName = name)
            assertFalse(state.isHerdrSessionNameValid, name)
            assertFalse(state.canSave, name)
            assertFalse(state.canConnect, name)
        }
    }

    @Test
    fun saveKeepsNoMachineWithASessionNameThatHerdrRefuses() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            val viewModel = ConfigureMachineViewModel(
                machineId = "",
                machineStore = machineStore,
                machineViewModelDelegate = MachineViewModelDelegateImpl(
                    machineStore,
                    SshRequestManager(),
                    EndpointSnapshotBus(),
                ),
                secretStore = InMemorySecretStore(),
            )
            viewModel.onEvent(Event.UpdateHostname("devbox"))
            viewModel.onEvent(Event.UpdateUsername("ian"))
            viewModel.onEvent(Event.UpdateHerdrSessionName("my work"))

            viewModel.onEvent(Event.SaveMachine)
            assertNull(machineStore.getMachine(MACHINE_ID))

            viewModel.onEvent(Event.UpdateHerdrSessionName(" work "))
            viewModel.onEvent(Event.SaveMachine)
            assertEquals("work", machineStore.getMachine(MACHINE_ID)?.herdrSessionName)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        /** The ID that the view model gives to a new machine for `ian@devbox`. */
        const val MACHINE_ID = "host-devbox-ian"
    }
}
