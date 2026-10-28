package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.AuthType
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests the SSH login method choices of [ConfigureMachineViewModel]. This version does not support hardware
 * security keys. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureMachineAuthTypeTest {

    private val hardwareKeyMachine = HostMachine(
        id = "gpu-rig",
        name = "GPU Rig",
        hostname = "100.64.0.25",
        username = "ian",
        authMethod = SshAuthMethod.HardwareSecurityKey(
            keyType = HardwareKeyType.SkEcdsaSha2Nistp256,
            transport = HardwareKeyTransport.NfcOnly,
        ),
    )

    @Test
    fun authChoicesHaveTwoEntries() {
        val state = ConfigureMachineViewModel.createInitialState("", null)

        assertEquals(listOf(AuthType.KeystoreKey, AuthType.Password), state.authChoices)
        assertEquals(2, state.authChoices.size)
        assertFalse(AuthType.HardwareSecurityKey in state.authChoices)
    }

    @Test
    fun loadedHardwareKeyMachineCanSaveButCannotConnect() {
        val state = ConfigureMachineViewModel.createInitialState(hardwareKeyMachine.id, hardwareKeyMachine)

        assertEquals(AuthType.HardwareSecurityKey, state.authType)
        assertEquals(2, state.authChoices.size)
        assertTrue(state.canSave)
        assertFalse(state.canConnect)
    }

    @Test
    fun newMachineCanConnectWithEachChoice() {
        val state = ConfigureMachineViewModel.createInitialState("", null)
            .copy(hostname = "devbox", username = "ian")

        for (choice in state.authChoices) {
            assertTrue(state.copy(authType = choice).canConnect, "$choice must connect")
        }
    }

    @Test
    fun saveAndConnectSendsNoConnectForAHardwareKeyMachine() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            machineStore.upsertMachine(hardwareKeyMachine)
            val requestManager = SshRequestManager()
            val requests = mutableListOf<SshRequest>()
            val collector = launch(dispatcher) { requestManager.bus.collect { requests += it } }
            val viewModel = ConfigureMachineViewModel(
                machineId = hardwareKeyMachine.id,
                machineStore = machineStore,
                machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, requestManager, EndpointSnapshotBus()),
            )

            viewModel.onEvent(ConfigureMachineViewModel.Event.SaveAndConnect)

            assertTrue(requests.isEmpty(), "The view model sent $requests")
            assertEquals(ConfigureMachineViewModel.UNSUPPORTED_AUTH_ERROR, viewModel.state.value.validationError)

            // The screen does not offer the method, and the view model does not select it.
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateAuthType(AuthType.Password))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateAuthType(AuthType.HardwareSecurityKey))
            assertEquals(AuthType.Password, viewModel.state.value.authType)
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun saveKeepsTheHardwareKeyOfALoadedMachine() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            machineStore.upsertMachine(hardwareKeyMachine)
            val viewModel = ConfigureMachineViewModel(
                machineId = hardwareKeyMachine.id,
                machineStore = machineStore,
                machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, SshRequestManager(), EndpointSnapshotBus()),
            )

            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateName("GPU Rig 2"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.SaveMachine)

            val saved = machineStore.getMachine(hardwareKeyMachine.id)
            assertEquals("GPU Rig 2", saved?.name)
            assertEquals(hardwareKeyMachine.authMethod, saved?.authMethod)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
