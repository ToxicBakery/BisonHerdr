package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SshPublicKeyProvider
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Tests the public key display of [ConfigureMachineViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigureMachinePublicKeyTest {

    private class RecordingProvider(
        private val reply: suspend (String, KeyAlgorithm) -> String,
    ) : SshPublicKeyProvider {
        val requests = mutableListOf<Triple<String, KeyAlgorithm, Boolean>>()

        override suspend fun authorizedKeyLine(keyAlias: String, algorithm: KeyAlgorithm, requireBiometric: Boolean): String {
            requests += Triple(keyAlias, algorithm, requireBiometric)
            return reply(keyAlias, algorithm)
        }
    }

    private suspend fun TestScope.viewModel(provider: SshPublicKeyProvider?): ConfigureMachineViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
        machineStore.awaitLoaded()
        return ConfigureMachineViewModel(
            machineId = "",
            machineStore = machineStore,
            machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, SshRequestManager(), EndpointSnapshotBus()),
            sshPublicKeyProvider = provider,
        )
    }

    @Test
    fun withoutAProviderThePublicKeyIsNotOffered() = runTest {
        try {
            assertFalse(viewModel(provider = null).state.value.canShowPublicKey)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun showPublicKeyLoadsTheLineForTheCurrentKey() = runTest {
        try {
            val provider = RecordingProvider { alias, algorithm -> "${algorithm.sshKeyType} AAAA $alias" }
            val viewModel = viewModel(provider)
            assertTrue(viewModel.state.value.canShowPublicKey)

            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeyAlgorithm(KeyAlgorithm.EcdsaP256))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateRequireBiometric(true))
            viewModel.onEvent(ConfigureMachineViewModel.Event.ShowPublicKey)

            assertEquals(
                listOf(Triple("bison_default_ecdsa_p256", KeyAlgorithm.EcdsaP256, true)),
                provider.requests,
            )
            assertEquals("ecdsa-sha2-nistp256 AAAA bison_default_ecdsa_p256", viewModel.state.value.publicKeyLine)
            assertFalse(viewModel.state.value.isLoadingPublicKey)
            assertNull(viewModel.state.value.publicKeyError)

            // A different key has a different public key, so the screen must load it again.
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeystoreAlias("work"))
            assertNull(viewModel.state.value.publicKeyLine)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failureShowsAnErrorAndNoKey() = runTest {
        try {
            val viewModel = viewModel(RecordingProvider { _, _ -> error("The key alias is in use") })
            viewModel.onEvent(ConfigureMachineViewModel.Event.ShowPublicKey)

            assertNull(viewModel.state.value.publicKeyLine)
            assertFalse(viewModel.state.value.isLoadingPublicKey)
            assertEquals("Could not get the public key: The key alias is in use", viewModel.state.value.publicKeyError)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aResultForAnOldKeyIsDropped() = runTest {
        try {
            val pending = CompletableDeferred<String>()
            val viewModel = viewModel(RecordingProvider { _, _ -> pending.await() })
            viewModel.onEvent(ConfigureMachineViewModel.Event.ShowPublicKey)
            assertTrue(viewModel.state.value.isLoadingPublicKey)

            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeystoreAlias("work"))
            pending.complete("ssh-ed25519 AAAA bison_default_ed25519")

            assertNull(viewModel.state.value.publicKeyLine)
            assertFalse(viewModel.state.value.isLoadingPublicKey)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
