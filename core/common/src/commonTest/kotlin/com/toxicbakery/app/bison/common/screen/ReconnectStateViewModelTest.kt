package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.AgentActionDelegateImpl
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConnectingMachineViewModel
import com.toxicbakery.app.bison.common.screen.workspace.WorkspaceTerminalViewModel
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.HostKeyPrompt
import com.toxicbakery.app.bison.ssh.NeedsUserCause
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tests how the screens handle the reconnect states of a machine:
 * - A connection that stopped for the user: the switch of the terminal screen to the connecting screen in
 *   [WorkspaceTerminalViewModel], and the cause in [ConnectingMachineViewModel].
 * - A prompt from the composer while the machine reconnects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectStateViewModelTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
    private val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
    private val requestManager = SshRequestManager()
    private val stateManager = SshStateManager()
    private val snapshotBus = EndpointSnapshotBus()
    private val machineDelegate = MachineViewModelDelegateImpl(machineStore, requestManager, snapshotBus)
    private val unknownKey = KnownHost(
        host = "gpu-rig.local",
        port = 22,
        keyType = "ssh-ed25519",
        fingerprint = "SHA256:sent",
        publicKey = "AAAAsent",
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun workspaceViewModel(): WorkspaceTerminalViewModel = WorkspaceTerminalViewModel(
        machineId = WORKBOX,
        machineStore = machineStore,
        sshStateManager = stateManager,
        paneSurfaceBus = PaneSurfaceBus(),
        machineViewModelDelegate = machineDelegate,
        agentActionDelegate = AgentActionDelegateImpl(requestManager, SemanticNotificationBus(), snapshotBus),
    )

    private fun connectingViewModel(machineId: String): ConnectingMachineViewModel = ConnectingMachineViewModel(
        machineId = machineId,
        sshStateManager = stateManager,
        machineStore = machineStore,
        machineViewModelDelegate = machineDelegate,
        sshDebugLog = SshDebugLog(),
        knownHostsStore = KnownHostsStore(InMemoryStoreIOProvider(), dispatcher = dispatcher),
    )

    /** Collects the actions of [viewModel] until the end of the test. */
    private fun TestScope.actionsOf(viewModel: WorkspaceTerminalViewModel): List<WorkspaceTerminalViewModel.Action> {
        val actions = mutableListOf<WorkspaceTerminalViewModel.Action>()
        backgroundScope.launch(dispatcher) { viewModel.actions.toList(actions) }
        return actions
    }

    /** Returns the next request, or null when no request comes in 1 s of test time. */
    private suspend fun nextRequest(): SshRequest? = withTimeoutOrNull(1_000) { requestManager.bus.first() }

    @Test
    fun aSwitchToAMachineWithAHostKeyPromptOpensItsConnectingScreenAndDoesNotConnect() = testScope.runTest {
        stateManager.markMachineConnected(WORKBOX, ActiveBackbone.SshEndpoint(rttMs = 10))
        stateManager.setHostKeyPrompt(HostKeyPrompt.Unknown(machineId = GPU_RIG, presented = unknownKey))
        val viewModel = workspaceViewModel()
        val actions = actionsOf(viewModel)

        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SwitchMachine(GPU_RIG))

        assertEquals(listOf(WorkspaceTerminalViewModel.Action.OpenConnectingMachine(GPU_RIG)), actions)
        assertNull(nextRequest())
    }

    @Test
    fun aHostKeyPromptAfterTheSwitchOpensTheConnectingScreen() = testScope.runTest {
        stateManager.markMachineConnected(WORKBOX, ActiveBackbone.SshEndpoint(rttMs = 10))
        val viewModel = workspaceViewModel()
        val actions = actionsOf(viewModel)

        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SwitchMachine(GPU_RIG))
        assertEquals(SshRequest.Connect(GPU_RIG), nextRequest())
        assertTrue(actions.isEmpty())

        // The transport finds an unknown host key and stops the connect.
        stateManager.setHostKeyPrompt(HostKeyPrompt.Unknown(machineId = GPU_RIG, presented = unknownKey))

        assertEquals(listOf(WorkspaceTerminalViewModel.Action.OpenConnectingMachine(GPU_RIG)), actions)
    }

    @Test
    fun aReconnectLoopThatStopsForTheUserOpensTheConnectingScreen() = testScope.runTest {
        stateManager.markMachineConnected(WORKBOX, ActiveBackbone.SshEndpoint(rttMs = 10))
        val viewModel = workspaceViewModel()
        val actions = actionsOf(viewModel)

        stateManager.markMachineDisconnected(WORKBOX)
        stateManager.setNeedsUser(WORKBOX, NeedsUserCause.LoginRefused("Auth fail"))

        assertEquals(listOf(WorkspaceTerminalViewModel.Action.OpenConnectingMachine(WORKBOX)), actions)
    }

    @Test
    fun theConnectingScreenShowsTheCauseAndConnectsOnlyAfterRetry() = testScope.runTest {
        val cause = NeedsUserCause.KeyMissing("work_key")
        stateManager.setNeedsUser(GPU_RIG, cause)
        stateManager.setConnectionProgress(GPU_RIG, ConnectionProgress.Failed)
        val viewModel = connectingViewModel(GPU_RIG)
        assertEquals(cause, viewModel.state.value.needsUser)

        viewModel.onEvent(ConnectingMachineViewModel.Event.StartConnectIfNeeded)
        assertNull(nextRequest())

        viewModel.onEvent(ConnectingMachineViewModel.Event.RetryConnect)
        assertEquals(SshRequest.Connect(GPU_RIG), nextRequest())

        // The new connect removes the cause. After a suspension of the test, the state changes on the next dispatch.
        stateManager.clearNeedsUser(GPU_RIG)
        assertNotNull(withTimeoutOrNull(1_000) { viewModel.state.first { it.needsUser == null } })
    }

    @Test
    fun aPromptWhileTheMachineReconnectsGoesOnlyIntoTheQueue() = testScope.runTest {
        stateManager.markMachineConnected(WORKBOX, ActiveBackbone.SshEndpoint(rttMs = 10))
        stateManager.setRoamingStatus(WORKBOX, RoamingStatus.RoamingReconnect)
        val viewModel = workspaceViewModel()

        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SubmitComposerPrompt(prompt = "Queued while roaming"))

        assertEquals(listOf("Queued while roaming"), stateManager.queuedPromptsForMachine(WORKBOX).map { it.prompt })
        // The transport sends the queue after the reconnect. A request now would send the prompt two times.
        assertNull(nextRequest())
    }

    @Test
    fun aPromptWhileTheMachineIsHealthyGoesOutAtOnce() = testScope.runTest {
        stateManager.markMachineConnected(WORKBOX, ActiveBackbone.SshEndpoint(rttMs = 10))
        val viewModel = workspaceViewModel()

        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SubmitComposerPrompt(prompt = "Run the tests"))

        assertEquals("Run the tests", assertIs<SshRequest.SubmitAgentPrompt>(nextRequest()).prompt)
        assertTrue(stateManager.queuedPromptsForMachine(WORKBOX).isEmpty())
    }

    private companion object {
        const val WORKBOX = "workbox"
        const val GPU_RIG = "gpu-rig"
    }
}
