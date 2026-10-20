package com.toxicbakery.app.bison.common.screen

import com.toxicbakery.app.bison.common.screen.delegate.AgentActionDelegateImpl
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegateImpl
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConnectingMachineViewModel
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelsAndDelegatesTest {

    @Test
    fun agentActionDelegateSortsFederatedAgentsByPriority() = runTest {
        val requestManager = SshRequestManager()
        val notificationBus = SemanticNotificationBus()
        val snapshotBus = EndpointSnapshotBus()
        val delegate = AgentActionDelegateImpl(requestManager, notificationBus, snapshotBus)

        val snapshots = mapOf(
            "workbox" to ClientShellSnapshot(
                agents = listOf(
                    ClientShellAgent(
                        paneId = "p2",
                        name = "codex",
                        displayAgent = "Codex",
                        agentStatus = AgentStatus.Working,
                    ),
                    ClientShellAgent(
                        paneId = "p1",
                        name = "claude",
                        displayAgent = "Claude Code",
                        agentStatus = AgentStatus.Blocked,
                    ),
                ),
            ),
            "gpu-rig" to ClientShellSnapshot(
                agents = listOf(
                    ClientShellAgent(
                        paneId = "p9",
                        name = "gemini",
                        displayAgent = "Gemini CLI",
                        agentStatus = AgentStatus.Done,
                    ),
                ),
            ),
        )

        val federated = delegate.aggregateFederatedAgents(
            snapshots = snapshots,
            machineNames = mapOf("workbox" to "Workbox", "gpu-rig" to "GPU Rig"),
            snippets = mapOf("workbox:p1" to "Allow Bash: ./gradlew build?"),
        )

        assertEquals(3, federated.size)
        assertEquals(AgentStatus.Blocked, federated[0].agent.agentStatus)
        assertEquals("Allow Bash: ./gradlew build?", federated[0].snippet)
        assertEquals(AgentStatus.Done, federated[1].agent.agentStatus)
        assertEquals(AgentStatus.Working, federated[2].agent.agentStatus)
    }

    @Test
    fun configureMachineViewModelValidatesAndUpsertsHost() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
            machineStore.awaitLoaded()
            val requestManager = SshRequestManager()
            val snapshotBus = EndpointSnapshotBus()
            val delegate = MachineViewModelDelegateImpl(machineStore, requestManager, snapshotBus)

            val viewModel = ConfigureMachineViewModel(
                machineId = "",
                machineStore = machineStore,
                machineViewModelDelegate = delegate,
            )

            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateName("Studio Devbox"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHostname("devbox.tailnet.ts.net"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateUsername("ian"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdatePort("2222"))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateTransportMode(TransportMode.AutoPreferMosh))
            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateThemeOverride("tokyo-night"))

            assertTrue(viewModel.state.value.canSave)
            viewModel.onEvent(ConfigureMachineViewModel.Event.SaveMachine)

            val saved = machineStore.knownMachines.value.firstOrNull()
            assertNotNull(saved)
            assertEquals("Studio Devbox", saved.name)
            assertEquals("devbox.tailnet.ts.net", saved.hostname)
            assertEquals(2222, saved.port)
            assertEquals("ian", saved.username)
            assertEquals("tokyo-night", saved.themeOverrideId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun connectingMachineViewModelMapsBootstrapStagesToSteps() = runTest {
        val stepsForVerify = ConnectingMachineViewModel.buildStepsForProgress(
            ConnectionProgress.VerifyingUdpOrHello
        )
        assertEquals(4, stepsForVerify.size)
        assertEquals(ConnectingMachineViewModel.StepStatus.Completed, stepsForVerify[0].status)
        assertEquals(ConnectingMachineViewModel.StepStatus.Completed, stepsForVerify[1].status)
        assertEquals(ConnectingMachineViewModel.StepStatus.Active, stepsForVerify[2].status)
        assertEquals(ConnectingMachineViewModel.StepStatus.Pending, stepsForVerify[3].status)

        val stepsConnected = ConnectingMachineViewModel.buildStepsForProgress(ConnectionProgress.Connected)
        assertTrue(stepsConnected.all { it.status == ConnectingMachineViewModel.StepStatus.Completed })
    }

    @Test
    fun machineViewModelDelegateDispatchesConnectAndSurfaceLeaseRequests() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val machineStore = MachineStore(InMemoryStoreIOProvider(), dispatcher = dispatcher)
        machineStore.awaitLoaded()
        val requestManager = SshRequestManager()
        val snapshotBus = EndpointSnapshotBus()
        val delegate = MachineViewModelDelegateImpl(machineStore, requestManager, snapshotBus)

        val collected = mutableListOf<SshRequest>()
        val job = launch(dispatcher) {
            requestManager.bus.collect { collected.add(it) }
        }

        delegate.connect("workbox")
        delegate.setSurfaceLease("workbox", active = false)

        assertEquals(2, collected.size)
        assertEquals(SshRequest.Connect("workbox"), collected[0])
        assertEquals(SshRequest.SetSurfaceLease("workbox", active = false), collected[1])
        job.cancel()
    }
}
