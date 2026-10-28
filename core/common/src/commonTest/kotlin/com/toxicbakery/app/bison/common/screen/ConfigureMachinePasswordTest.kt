package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.AuthType
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.Event
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.StoredPassword
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.InMemorySecretStore
import com.toxicbakery.app.bison.ssh.SecretRef
import com.toxicbakery.app.bison.ssh.SecretStore
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests that [ConfigureMachineViewModel] keeps each password in the [SecretStore] and only a [SecretRef] value
 * in `machines.json`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureMachinePasswordTest {

    private val io = InMemoryStoreIOProvider()
    private val secretStore = InMemorySecretStore()
    private lateinit var machineStore: MachineStore

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aNewPasswordGoesIntoTheSecretStoreAndNotIntoMachinesJson() = runTest {
        val viewModel = viewModel(machineId = "")
        viewModel.onEvent(Event.UpdateHostname("devbox"))
        viewModel.onEvent(Event.UpdateUsername("ian"))
        viewModel.onEvent(Event.UpdateAuthType(AuthType.Password))
        viewModel.onEvent(Event.UpdatePasswordRef(PASSWORD))
        viewModel.onEvent(Event.SaveMachine)

        val saved = machineStore.knownMachines.value.single()
        val ref = assertNotNull(SecretRef.parse(passwordValue(saved)))
        assertContentEquals(PASSWORD.encodeToByteArray(), secretStore.get(ref))
        assertFalse(PASSWORD in machinesJson(), machinesJson())
        assertEquals("", viewModel.state.value.passwordRef)
        assertEquals(StoredPassword.Stored, viewModel.state.value.storedPassword)
    }

    @Test
    fun anEditShowsAHintAndNotTheStoredPassword() = runTest {
        val machine = storedPasswordMachine()

        val state = viewModel(machine.id).state.value

        assertEquals("", state.passwordRef)
        assertEquals(StoredPassword.Stored, state.storedPassword)
        assertFalse(PASSWORD in state.toString())
    }

    @Test
    fun anEditWithAnEmptyPasswordFieldKeepsTheStoredSecret() = runTest {
        val machine = storedPasswordMachine()
        val viewModel = viewModel(machine.id)

        viewModel.onEvent(Event.UpdateName("Devbox 2"))
        viewModel.onEvent(Event.SaveMachine)

        val saved = assertNotNull(machineStore.getMachine(machine.id))
        assertEquals("Devbox 2", saved.name)
        assertEquals(passwordValue(machine), passwordValue(saved))
        assertContentEquals(PASSWORD.encodeToByteArray(), secretStore.get(REF))
        assertEquals(setOf(REF), secretStore.refs)
    }

    @Test
    fun aNewPasswordReplacesTheStoredSecret() = runTest {
        val machine = storedPasswordMachine()
        val viewModel = viewModel(machine.id)

        viewModel.onEvent(Event.UpdatePasswordRef("new password"))
        viewModel.onEvent(Event.SaveMachine)

        val newRef = assertNotNull(SecretRef.parse(passwordValue(assertNotNull(machineStore.getMachine(machine.id)))))
        assertNotEquals(REF, newRef)
        assertContentEquals("new password".encodeToByteArray(), secretStore.get(newRef))
        assertEquals(setOf(newRef), secretStore.refs, "The old secret is still in the store")
    }

    @Test
    fun aMachineDeleteRemovesItsSecret() = runTest {
        val machine = storedPasswordMachine()
        val actions = mutableListOf<ConfigureMachineViewModel.Action>()
        val viewModel = viewModel(machine.id)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.actions.collect { actions += it } }

        viewModel.onEvent(Event.DeleteMachine)

        assertNull(machineStore.getMachine(machine.id))
        assertNull(secretStore.get(REF))
        assertTrue(secretStore.refs.isEmpty())
        assertEquals(listOf<ConfigureMachineViewModel.Action>(ConfigureMachineViewModel.Action.Deleted), actions)
    }

    @Test
    fun aChangeToAKeyRemovesTheStoredSecret() = runTest {
        val machine = storedPasswordMachine()
        val viewModel = viewModel(machine.id)

        viewModel.onEvent(Event.UpdateAuthType(AuthType.KeystoreKey))
        viewModel.onEvent(Event.SaveMachine)

        assertTrue(machineStore.getMachine(machine.id)?.authMethod is SshAuthMethod.KeystoreKey)
        assertTrue(secretStore.refs.isEmpty())
    }

    @Test
    fun aMissingSecretShowsTheMissingHint() = runTest {
        val machine = storedPasswordMachine()
        secretStore.remove(REF)

        assertEquals(StoredPassword.Missing, viewModel(machine.id).state.value.storedPassword)
    }

    @Test
    fun aStoreFailureShowsAnErrorAndSavesNothing() = runTest {
        val failing = object : SecretStore by secretStore {
            override suspend fun put(ref: String, secret: ByteArray) = throw IllegalStateException("no key store")
        }
        val viewModel = viewModel(machineId = "", store = failing)
        viewModel.onEvent(Event.UpdateHostname("devbox"))
        viewModel.onEvent(Event.UpdateUsername("ian"))
        viewModel.onEvent(Event.UpdateAuthType(AuthType.Password))
        viewModel.onEvent(Event.UpdatePasswordRef(PASSWORD))
        viewModel.onEvent(Event.SaveMachine)

        assertTrue(machineStore.knownMachines.value.isEmpty())
        assertEquals(
            "${ConfigureMachineViewModel.PASSWORD_SAVE_ERROR} no key store",
            viewModel.state.value.validationError,
        )
        assertFalse(PASSWORD in viewModel.state.value.validationError.orEmpty())
    }

    private suspend fun TestScope.storedPasswordMachine(): HostMachine {
        secretStore.put(REF, PASSWORD.encodeToByteArray())
        val machine = HostMachine(
            id = "devbox-ian",
            name = "Devbox",
            hostname = "devbox",
            username = "ian",
            authMethod = SshAuthMethod.Password(encryptedPasswordRef = SecretRef.format(REF)),
        )
        createMachineStore().upsertMachine(machine)
        return machine
    }

    private suspend fun TestScope.createMachineStore(): MachineStore {
        if (!::machineStore.isInitialized) {
            machineStore = MachineStore(io, dispatcher = UnconfinedTestDispatcher(testScheduler))
            machineStore.awaitLoaded()
        }
        return machineStore
    }

    private suspend fun TestScope.viewModel(
        machineId: String,
        store: SecretStore = secretStore,
    ): ConfigureMachineViewModel {
        val machines = createMachineStore()
        return ConfigureMachineViewModel(
            machineId = machineId,
            machineStore = machines,
            machineViewModelDelegate = MachineViewModelDelegateImpl(
                machineStore = machines,
                sshRequestManager = SshRequestManager(),
                endpointSnapshotBus = EndpointSnapshotBus(),
            ),
            secretStore = store,
        )
    }

    private fun passwordValue(machine: HostMachine): String =
        (machine.authMethod as SshAuthMethod.Password).encryptedPasswordRef

    private fun machinesJson(): String = io.textAt(MachineStore.MACHINES_FILE_NAME).orEmpty()

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val REF = "password-test"
    }
}
