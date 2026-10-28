package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.AuthType
import com.toxicbakery.app.bison.model.FeatureFlags
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.HardwareKeyException
import com.toxicbakery.app.bison.ssh.HardwareKeySigner
import com.toxicbakery.app.bison.ssh.InMemorySecretStore
import com.toxicbakery.app.bison.ssh.SecurityKeyPublicKeys
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.encoding.Base64
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests the SSH login method choices of [ConfigureMachineViewModel]. Without [FeatureFlags.hardwareSecurityKeys], the
 * screen does not offer hardware security keys. With the flag and a [HardwareKeySigner], the screen offers FIDO2 keys
 * and enrolls them. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 keys.
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

    private val signer = FakeHardwareKeySigner()

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
                secretStore = InMemorySecretStore(),
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
                secretStore = InMemorySecretStore(),
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

    @Test
    fun saveKeepsACustomApplicationIdOfALoadedHardwareKey() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val customKey = SshAuthMethod.HardwareSecurityKey(
                keyType = HardwareKeyType.PivSmartCard,
                transport = HardwareKeyTransport.UsbOnly,
                applicationId = "ssh:bison-custom",
            )
            val machine = hardwareKeyMachine.copy(authMethod = customKey)
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            machineStore.upsertMachine(machine)
            val viewModel = ConfigureMachineViewModel(
                machineId = machine.id,
                machineStore = machineStore,
                machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, SshRequestManager(), EndpointSnapshotBus()),
                secretStore = InMemorySecretStore(),
            )
            assertEquals(customKey, viewModel.state.value.hardwareKey)

            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdatePort("2222"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.SaveMachine)

            val saved = machineStore.getMachine(machine.id)
            assertEquals(2222, saved?.port)
            assertEquals(customKey, saved?.authMethod)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun withTheFlagAndASignerTheChoicesHaveTheSecurityKey() {
        val state = ConfigureMachineViewModel.createInitialState("", null, hardwareKeysEnabled = true)
            .copy(hostname = "devbox", username = "ian")

        assertEquals(listOf(AuthType.KeystoreKey, AuthType.Password, AuthType.HardwareSecurityKey), state.authChoices)
        val hardwareKeyState = state.copy(authType = AuthType.HardwareSecurityKey)
        assertTrue(hardwareKeyState.isAuthTypeSupported)
        // The key has no enrolled credential, so the machine cannot connect.
        assertFalse(hardwareKeyState.canConnect)
        assertNull(hardwareKeyState.hardwareKeyPublicKeyLine)
        assertTrue(hardwareKeyState.copy(hardwareKey = enrolledKey).canConnect)
    }

    @Test
    fun withTheFlagButNoSignerTheScreenDoesNotOfferTheSecurityKey() = withViewModel(
        machine = null,
        signer = null,
    ) { viewModel, _, _ ->
        assertFalse(viewModel.state.value.hardwareKeysEnabled)
        assertEquals(ConfigureMachineViewModel.AUTH_CHOICES, viewModel.state.value.authChoices)

        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateAuthType(AuthType.HardwareSecurityKey))
        viewModel.onEvent(ConfigureMachineViewModel.Event.EnrollHardwareKey)

        assertEquals(AuthType.KeystoreKey, viewModel.state.value.authType)
    }

    @Test
    fun enrollShowsThePublicKeyLineAndSaveAndConnectKeepsTheCredential() = withViewModel(
        machine = null,
    ) { viewModel, machineStore, requests ->
        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHostname("devbox"))
        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateUsername("ian"))
        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateAuthType(AuthType.HardwareSecurityKey))
        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHardwareKeyTransport(HardwareKeyTransport.UsbOnly))

        viewModel.onEvent(ConfigureMachineViewModel.Event.SaveAndConnect)
        assertEquals(ConfigureMachineViewModel.HARDWARE_KEY_NOT_ENROLLED_ERROR, viewModel.state.value.validationError)

        viewModel.onEvent(ConfigureMachineViewModel.Event.EnrollHardwareKey)

        assertEquals(
            listOf(
                EnrollCall(
                    machineId = ConfigureMachineViewModel.NEW_MACHINE_ID,
                    keyType = HardwareKeyType.SkSshEd25519,
                    transport = HardwareKeyTransport.UsbOnly,
                    applicationId = "ssh:",
                ),
            ),
            signer.enrollCalls,
        )
        val state = viewModel.state.value
        assertFalse(state.isEnrollingKey)
        assertNull(state.enrollError)
        assertEquals(SecurityKeyPublicKeys.authorizedKeyLine(enrolledKey), state.hardwareKeyPublicKeyLine)
        assertTrue(state.canConnect)

        viewModel.onEvent(ConfigureMachineViewModel.Event.SaveAndConnect)

        val saved = assertNotNull(machineStore.getMachine("host-devbox-ian"))
        assertEquals(enrolledKey.copy(transport = HardwareKeyTransport.UsbOnly), saved.authMethod)
        assertTrue(requests.any { it is SshRequest.Connect && it.machineId == saved.id }, "$requests")
    }

    @Test
    fun aFailedEnrollmentShowsTheCause() = withViewModel(machine = hardwareKeyMachine) { viewModel, _, _ ->
        signer.result = { throw HardwareKeyException(HardwareKeyException.Reason.NoKey) }
        assertEquals(HardwareKeyType.SkEcdsaSha2Nistp256, viewModel.state.value.hardwareKeyType)
        assertEquals(HardwareKeyTransport.NfcOnly, viewModel.state.value.hardwareKeyTransport)

        viewModel.onEvent(ConfigureMachineViewModel.Event.EnrollHardwareKey)

        val state = viewModel.state.value
        assertEquals(hardwareKeyMachine.id, signer.enrollCalls.single().machineId)
        assertFalse(state.isEnrollingKey)
        assertEquals(
            "${ConfigureMachineViewModel.ENROLL_ERROR} " +
                HardwareKeyException.messageFor(HardwareKeyException.Reason.NoKey),
            state.enrollError,
        )
        assertNull(state.hardwareKeyPublicKeyLine)
        assertFalse(state.canConnect)
    }

    @Test
    fun aKeyTypeChangeRemovesTheEnrolledCredential() = withViewModel(
        machine = hardwareKeyMachine.copy(authMethod = enrolledKey.copy(applicationId = "ssh:bison")),
    ) { viewModel, machineStore, _ ->
        assertTrue(viewModel.state.value.canConnect)

        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHardwareKeyType(HardwareKeyType.PivSmartCard))
        assertEquals(HardwareKeyType.SkSshEd25519, viewModel.state.value.hardwareKeyType)

        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHardwareKeyType(HardwareKeyType.SkEcdsaSha2Nistp256))
        assertNull(viewModel.state.value.hardwareKeyPublicKeyLine)
        assertFalse(viewModel.state.value.canConnect)

        viewModel.onEvent(ConfigureMachineViewModel.Event.SaveMachine)

        val saved = machineStore.getMachine(hardwareKeyMachine.id)?.authMethod
        assertIs<SshAuthMethod.HardwareSecurityKey>(saved)
        assertEquals(
            SshAuthMethod.HardwareSecurityKey(
                keyType = HardwareKeyType.SkEcdsaSha2Nistp256,
                transport = HardwareKeyTransport.UsbAndNfc,
                applicationId = "ssh:bison",
            ),
            saved,
        )
        assertFalse(saved.isEnrolled)
    }

    @Test
    fun aLoadedPivKeyCannotConnectAlsoWithTheFlag() = withViewModel(
        machine = hardwareKeyMachine.copy(
            authMethod = SshAuthMethod.HardwareSecurityKey(keyType = HardwareKeyType.PivSmartCard),
        ),
    ) { viewModel, _, requests ->
        assertFalse(viewModel.state.value.isAuthTypeSupported)

        viewModel.onEvent(ConfigureMachineViewModel.Event.EnrollHardwareKey)
        viewModel.onEvent(ConfigureMachineViewModel.Event.SaveAndConnect)

        assertTrue(signer.enrollCalls.isEmpty())
        assertEquals(ConfigureMachineViewModel.UNSUPPORTED_AUTH_ERROR, viewModel.state.value.validationError)
        assertTrue(requests.isEmpty(), "The view model sent $requests")
    }

    /**
     * Runs [block] with a view model of [machine] (or a new machine), the security key flag on, and [signer]. The
     * block gets the view model, the machine store, and the requests that the view model sent.
     */
    private fun withViewModel(
        machine: HostMachine?,
        signer: HardwareKeySigner? = this.signer,
        block: suspend (ConfigureMachineViewModel, MachineStore, List<SshRequest>) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            machine?.let { machineStore.upsertMachine(it) }
            val requestManager = SshRequestManager()
            val requests = mutableListOf<SshRequest>()
            val collector = launch(dispatcher) { requestManager.bus.collect { requests += it } }
            val viewModel = ConfigureMachineViewModel(
                machineId = machine?.id.orEmpty(),
                machineStore = machineStore,
                machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, requestManager, EndpointSnapshotBus()),
                secretStore = InMemorySecretStore(),
                featureFlags = FeatureFlags(hardwareSecurityKeys = true),
                hardwareKeySigner = signer,
            )
            block(viewModel, machineStore, requests)
            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private data class EnrollCall(
        val machineId: String,
        val keyType: HardwareKeyType,
        val transport: HardwareKeyTransport,
        val applicationId: String,
    )

    /** A [HardwareKeySigner] that returns the key of [result] at once, and records each enrollment. */
    private class FakeHardwareKeySigner : HardwareKeySigner {
        var result: () -> SshAuthMethod.HardwareSecurityKey = { enrolledKey }
        val enrollCalls = mutableListOf<EnrollCall>()

        override suspend fun enroll(
            machineId: String,
            keyType: HardwareKeyType,
            transport: HardwareKeyTransport,
            applicationId: String,
        ): SshAuthMethod.HardwareSecurityKey {
            enrollCalls += EnrollCall(machineId, keyType, transport, applicationId)
            return result()
        }

        override suspend fun sign(
            machineId: String,
            key: SshAuthMethod.HardwareSecurityKey,
            data: ByteArray,
        ): ByteArray = error("The view model does not sign")
    }

    private companion object {
        /** An enrolled Ed25519 key. The values are only test data. */
        val enrolledKey = SshAuthMethod.HardwareSecurityKey(
            keyType = HardwareKeyType.SkSshEd25519,
            credentialId = Base64.encode(ByteArray(48) { it.toByte() }),
            publicKey = Base64.encode(ByteArray(SecurityKeyPublicKeys.ED25519_PUBLIC_KEY_LENGTH) { (it * 3).toByte() }),
        )
    }
}
