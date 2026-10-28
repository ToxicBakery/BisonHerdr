package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConnectingMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.KnownHostsViewModel
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.HostKeyPrompt
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests the host key decisions in [ConnectingMachineViewModel] and the known host list in [KnownHostsViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostKeyViewModelTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
    private val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
    private val knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
    private val requestManager = SshRequestManager()
    private val stateManager = SshStateManager()
    private val debugLog = SshDebugLog()

    private val savedKey = knownHost(fingerprint = "SHA256:saved", publicKey = "AAAAsaved")
    private val sentKey = knownHost(fingerprint = "SHA256:sent", publicKey = "AAAAsent")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun connectingViewModel(): ConnectingMachineViewModel = ConnectingMachineViewModel(
        machineId = MACHINE_ID,
        sshStateManager = stateManager,
        machineStore = machineStore,
        machineViewModelDelegate = MachineViewModelDelegateImpl(machineStore, requestManager, EndpointSnapshotBus()),
        sshDebugLog = debugLog,
        knownHostsStore = knownHostsStore,
    )

    /** Returns the next request, or null when no request comes in 1 s of test time. */
    private suspend fun nextRequest(): SshRequest? = withTimeoutOrNull(1_000) { requestManager.bus.first() }

    @Test
    fun acceptSavesTheKeyAndConnectsAgain() = testScope.runTest {
        val viewModel = connectingViewModel()
        val prompt = HostKeyPrompt.Unknown(machineId = MACHINE_ID, presented = sentKey)
        stateManager.setHostKeyPrompt(prompt)
        assertEquals(prompt, viewModel.state.value.hostKeyPrompt)

        viewModel.onEvent(ConnectingMachineViewModel.Event.AcceptHostKey)

        assertEquals(listOf(sentKey), knownHostsStore.knownHosts.value)
        assertNull(viewModel.state.value.hostKeyPrompt)
        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
        assertEquals(SshRequest.Connect(MACHINE_ID), nextRequest())
    }

    @Test
    fun rejectSavesNothingAndDoesNotConnect() = testScope.runTest {
        val viewModel = connectingViewModel()
        stateManager.setHostKeyPrompt(HostKeyPrompt.Unknown(machineId = MACHINE_ID, presented = sentKey))

        viewModel.onEvent(ConnectingMachineViewModel.Event.RejectHostKey)

        assertTrue(knownHostsStore.knownHosts.value.isEmpty())
        assertNull(viewModel.state.value.hostKeyPrompt)
        assertTrue(stateManager.hostKeyPrompts.value.isEmpty())
        assertNull(nextRequest())
        assertTrue(debugLog.entries.value.any { it.isError && it.message.contains("Rejected") })
    }

    @Test
    fun acceptDoesNotSaveTheKeyOfAChangedPrompt() = testScope.runTest {
        knownHostsStore.put(savedKey)
        val viewModel = connectingViewModel()
        val prompt = HostKeyPrompt.Changed(machineId = MACHINE_ID, known = savedKey, presented = sentKey)
        stateManager.setHostKeyPrompt(prompt)

        viewModel.onEvent(ConnectingMachineViewModel.Event.AcceptHostKey)

        assertEquals(listOf(savedKey), knownHostsStore.knownHosts.value)
        assertEquals(prompt, viewModel.state.value.hostKeyPrompt)
        assertNull(nextRequest())
    }

    @Test
    fun removeSavedKeyDeletesTheOldEntryAndConnectsAgain() = testScope.runTest {
        knownHostsStore.put(savedKey)
        val viewModel = connectingViewModel()
        stateManager.setHostKeyPrompt(HostKeyPrompt.Changed(machineId = MACHINE_ID, known = savedKey, presented = sentKey))

        viewModel.onEvent(ConnectingMachineViewModel.Event.RemoveSavedHostKey)

        // The new key is not saved. The next connection asks the user to accept it.
        assertTrue(knownHostsStore.knownHosts.value.isEmpty())
        assertNull(viewModel.state.value.hostKeyPrompt)
        assertEquals(SshRequest.Connect(MACHINE_ID), nextRequest())
    }

    @Test
    fun promptOfADifferentMachineIsNotShown() = testScope.runTest {
        val viewModel = connectingViewModel()

        stateManager.setHostKeyPrompt(HostKeyPrompt.Unknown(machineId = "other-box", presented = sentKey))

        assertNull(viewModel.state.value.hostKeyPrompt)
    }

    @Test
    fun knownHostsListShowsTheKeysOfTheMachineAndDeletesThem() = testScope.runTest {
        val machine = HostMachine(
            id = MACHINE_ID,
            name = "workbox",
            hostname = "workbox",
            port = 2222,
            username = "ian",
            proxyJump = ProxyJumpConfig(
                bastionHost = "bastion.example.com",
                bastionPort = 22,
                bastionUser = "ian",
            ),
        )
        machineStore.upsertMachine(machine)
        // The entry of the target has the host name that the user typed. The app adds no `.ts.net` suffix.
        val target = knownHost(host = "WorkBox", port = 2222, fingerprint = "SHA256:target")
        val bastion = knownHost(host = "bastion.example.com", port = 22, fingerprint = "SHA256:bastion")
        val otherPort = knownHost(host = "workbox", port = 22, fingerprint = "SHA256:other-port")
        val suffixed = knownHost(host = "workbox.ts.net", port = 2222, fingerprint = "SHA256:suffixed")
        listOf(target, otherPort, suffixed, bastion).forEach { knownHostsStore.put(it) }

        val viewModel = KnownHostsViewModel(
            machineId = MACHINE_ID,
            machineStore = machineStore,
            knownHostsStore = knownHostsStore,
        )
        assertEquals(listOf(target, bastion), viewModel.state.value.entries)

        viewModel.onEvent(KnownHostsViewModel.Event.Delete(target))

        assertEquals(listOf(bastion), viewModel.state.value.entries)
        assertEquals(listOf(otherPort, suffixed, bastion), knownHostsStore.knownHosts.value)
    }

    @Test
    fun knownHostsListIsEmptyForAnUnsavedMachine() = testScope.runTest {
        knownHostsStore.put(sentKey)

        val viewModel = KnownHostsViewModel(
            machineId = "not-saved",
            machineStore = machineStore,
            knownHostsStore = knownHostsStore,
        )

        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    private fun knownHost(
        host: String = "devbox.local",
        port: Int = 22,
        fingerprint: String,
        publicKey: String = "AAAA$fingerprint",
    ): KnownHost = KnownHost(
        host = host,
        port = port,
        keyType = "ssh-ed25519",
        fingerprint = fingerprint,
        publicKey = publicKey,
    )

    private companion object {
        const val MACHINE_ID = "workbox"
    }
}
