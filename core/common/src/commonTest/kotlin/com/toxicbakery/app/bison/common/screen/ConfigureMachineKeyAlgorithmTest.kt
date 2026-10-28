package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.InMemorySecretStore
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Tests the key type choices of [ConfigureMachineViewModel] on devices with and without Ed25519. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureMachineKeyAlgorithmTest {

    /** The key types of an Android 12 device: no Ed25519. */
    private val noEd25519 = object : KeystoreKeySupport {
        override val supportedAlgorithms = listOf(KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256)
    }

    @Test
    fun newMachineUsesEd25519WhenSupported() {
        val state = ConfigureMachineViewModel.createInitialState("", null, KeystoreKeySupport.AllAlgorithms)
        assertEquals(KeyAlgorithm.Ed25519, state.keyAlgorithm)
        assertEquals("bison_default_ed25519", state.keystoreAlias)
        assertEquals(KeyAlgorithm.entries.toList(), state.availableKeyAlgorithms)
    }

    @Test
    fun newMachineUsesEcdsaWithoutEd25519() {
        val state = ConfigureMachineViewModel.createInitialState("", null, noEd25519)
        assertEquals(KeyAlgorithm.EcdsaP256, state.keyAlgorithm)
        assertEquals("bison_default_ecdsa_p256", state.keystoreAlias)
        assertFalse(KeyAlgorithm.Ed25519 in state.availableKeyAlgorithms)
    }

    @Test
    fun existingEd25519MachineChangesToEcdsaWithoutEd25519() {
        val existing = HostMachine(
            id = "m1",
            name = "Devbox",
            hostname = "devbox",
            username = "ian",
            authMethod = SshAuthMethod.KeystoreKey(algorithm = KeyAlgorithm.Ed25519),
        )
        val state = ConfigureMachineViewModel.createInitialState("m1", existing, noEd25519)
        assertEquals(KeyAlgorithm.EcdsaP256, state.keyAlgorithm)
        assertEquals("bison_default_ecdsa_p256", state.keystoreAlias)

        val custom = existing.copy(authMethod = SshAuthMethod.KeystoreKey(keyAlias = "work", algorithm = KeyAlgorithm.Ed25519))
        assertEquals("work", ConfigureMachineViewModel.createInitialState("m1", custom, noEd25519).keystoreAlias)

        val supported = ConfigureMachineViewModel.createInitialState("m1", existing, KeystoreKeySupport.AllAlgorithms)
        assertEquals(KeyAlgorithm.Ed25519, supported.keyAlgorithm)
        assertEquals("bison_default_ed25519", supported.keystoreAlias)
    }

    @Test
    fun defaultAliasFollowsTheKeyTypeAndCustomAliasStays() {
        assertEquals(
            "bison_default_rsa4096",
            ConfigureMachineViewModel.aliasForAlgorithm("bison_default_ed25519", KeyAlgorithm.Ed25519, KeyAlgorithm.Rsa4096),
        )
        assertEquals(
            "bison_default_ecdsa_p256",
            ConfigureMachineViewModel.aliasForAlgorithm(" ", KeyAlgorithm.Ed25519, KeyAlgorithm.EcdsaP256),
        )
        assertEquals("work", ConfigureMachineViewModel.aliasForAlgorithm("work", KeyAlgorithm.Ed25519, KeyAlgorithm.Rsa4096))
    }

    @Test
    fun unsupportedKeyTypeIsIgnoredAndSaveUsesASupportedType() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            val viewModel = ConfigureMachineViewModel(
                machineId = "",
                machineStore = machineStore,
                machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, SshRequestManager(), EndpointSnapshotBus()),
                secretStore = InMemorySecretStore(),
                keystoreKeySupport = noEd25519,
            )
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHostname("devbox"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateUsername("ian"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeyAlgorithm(KeyAlgorithm.Ed25519))
            assertEquals(KeyAlgorithm.EcdsaP256, viewModel.state.value.keyAlgorithm)

            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeyAlgorithm(KeyAlgorithm.Rsa4096))
            assertEquals("bison_default_rsa4096", viewModel.state.value.keystoreAlias)

            viewModel.onEvent(ConfigureMachineViewModel.Event.SaveMachine)
            val saved = machineStore.knownMachines.value.single().authMethod as SshAuthMethod.KeystoreKey
            assertEquals(KeyAlgorithm.Rsa4096, saved.algorithm)
            assertEquals("bison_default_rsa4096", saved.keyAlias)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
