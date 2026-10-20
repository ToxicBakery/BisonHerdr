package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.common.screen.delegate.AgentActionDelegate
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegate
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel
import com.toxicbakery.app.bison.common.screen.machine.ConnectingMachineViewModel
import com.toxicbakery.app.bison.common.screen.workspace.WorkspaceTerminalViewModel
import com.toxicbakery.app.bison.debug.DebugServer
import com.toxicbakery.app.bison.debug.NoDebugServer
import com.toxicbakery.app.bison.fakeserver.fakeServerModule
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.SemanticNotificationBus
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.store.StoreIOProvider
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.factory
import org.kodein.di.instance

/**
 * Full Kodein DI verification and End-to-End ViewModel connection flow test suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KodeinConnectionFlowE2ETest {

    @Test
    fun coreDiModuleWithNoDebugServerResolvesAllBindingsCleanly() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val di = DI {
                import(coreDiModule)
                bindSingleton<DebugServer> { NoDebugServer }
                bindSingleton<StoreIOProvider>(overrides = true) { InMemoryStoreIOProvider() }
            }

            val direct = di.direct
            assertEquals(direct.instance<StoreIOProvider>(), direct.instance<StoreIOProvider>())
            assertNotNull(direct.instance<Json>())
            assertNotNull(direct.instance<SshStateManager>())
            assertNotNull(direct.instance<SshRequestManager>())
            assertNotNull(direct.instance<SshDebugLog>())
            assertNotNull(direct.instance<EndpointSnapshotBus>())
            assertNotNull(direct.instance<PaneSurfaceBus>())
            assertNotNull(direct.instance<SemanticNotificationBus>())
            assertNotNull(direct.instance<MachineStore>())
            assertNotNull(direct.instance<AppSettingsStore>())
            assertNotNull(direct.instance<MachineViewModelDelegate>())
            assertNotNull(direct.instance<AgentActionDelegate>())
            assertEquals(NoDebugServer, direct.instance<DebugServer>())

            val configureVmFactory: (String) -> ConfigureMachineViewModel = direct.factory()
            val connectingVmFactory: (String) -> ConnectingMachineViewModel = direct.factory()
            val workspaceVmFactory: (String) -> WorkspaceTerminalViewModel = direct.factory()

            assertNotNull(configureVmFactory(""))
            assertNotNull(connectingVmFactory("workbox"))
            assertNotNull(workspaceVmFactory("workbox"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun endToEndViewModelJourney_configureSaveAndConnect_connectingStages_andWorkspaceTerminalActions() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val inMemoryIo = InMemoryStoreIOProvider()
            val di = DI {
                import(coreDiModule)
                import(fakeServerModule)
                bindProvider<StoreIOProvider>(overrides = true) { inMemoryIo }
                bindSingleton(overrides = true) {
                    MachineStore(
                        storeIOProvider = instance(),
                        json = instance(),
                        dispatcher = dispatcher,
                    )
                }
                bindSingleton(overrides = true) {
                    AppSettingsStore(
                        storeIOProvider = instance(),
                        json = instance(),
                        dispatcher = dispatcher,
                    )
                }
            }

            val direct = di.direct
            val sshStateManager = direct.instance<SshStateManager>()
            val sshRequestManager = direct.instance<SshRequestManager>()
            val sshDebugLog = direct.instance<SshDebugLog>()
            val endpointSnapshotBus = direct.instance<EndpointSnapshotBus>()
            val paneSurfaceBus = direct.instance<PaneSurfaceBus>()
            val semanticNotificationBus = direct.instance<SemanticNotificationBus>()
            val machineStore = direct.instance<MachineStore>()
            val appSettingsStore = direct.instance<AppSettingsStore>()
            val machineDelegate = direct.instance<MachineViewModelDelegate>()
            val agentDelegate = direct.instance<AgentActionDelegate>()
            val debugServer = direct.instance<DebugServer>()

            assertNotNull(sshDebugLog)
            assertNotNull(endpointSnapshotBus)
            assertNotNull(paneSurfaceBus)
            assertNotNull(semanticNotificationBus)
            assertNotNull(appSettingsStore)
            assertNotNull(machineDelegate)
            assertNotNull(agentDelegate)

            machineStore.awaitLoaded()
            appSettingsStore.awaitLoaded()
            debugServer.enable()
            assertTrue(debugServer.isEnabled)

            val configureFactory: (String) -> ConfigureMachineViewModel = direct.factory()
            val connectingFactory: (String) -> ConnectingMachineViewModel = direct.factory()
            val workspaceFactory: (String) -> WorkspaceTerminalViewModel = direct.factory()

            // Capture SshRequests emitted during ConfigureMachineViewModel.SaveAndConnect
            val pendingConnectRequests = mutableListOf<SshRequest.Connect>()
            val connectCaptureJob = launch(dispatcher) {
                sshRequestManager.bus.collect { req ->
                    if (req is SshRequest.Connect) {
                        pendingConnectRequests.add(req)
                    }
                }
            }

            // 1. Create a host in ConfigureMachineViewModel (SaveAndConnect)
            val configureVm = configureFactory("")
            val configureActions = mutableListOf<ConfigureMachineViewModel.Action>()
            val configureActionJob = launch(dispatcher) {
                configureVm.actions.collect { configureActions.add(it) }
            }

            configureVm.onEvent(ConfigureMachineViewModel.Event.UpdateName("E2E Studio Rig"))
            configureVm.onEvent(ConfigureMachineViewModel.Event.UpdateHostname("studio-rig.tailnet.ts.net"))
            configureVm.onEvent(ConfigureMachineViewModel.Event.UpdateUsername("ian"))
            configureVm.onEvent(ConfigureMachineViewModel.Event.UpdatePort("22"))
            configureVm.onEvent(ConfigureMachineViewModel.Event.UpdateTransportMode(TransportMode.AutoPreferMosh))
            assertTrue(configureVm.state.value.canSave)

            configureVm.onEvent(ConfigureMachineViewModel.Event.SaveAndConnect)
            configureActionJob.cancel()
            connectCaptureJob.cancel()

            val savedAction = assertIs<ConfigureMachineViewModel.Action.Saved>(configureActions.single())
            assertTrue(savedAction.connectImmediately)
            val machineId = savedAction.machineId
            assertEquals("host-studio-rig-tailnet-ts-net-ian", machineId)
            assertNotNull(machineStore.getMachine(machineId))
            assertEquals(1, pendingConnectRequests.size)
            assertEquals(machineId, pendingConnectRequests.single().machineId)

            // 2. Instantiate ConnectingMachineViewModel and observe the 4-stage progression -> Connected
            val connectingVm = connectingFactory(machineId)
            val observedProgress = mutableListOf<ConnectionProgress>()
            val observedActions = mutableListOf<ConnectingMachineViewModel.Action>()

            val progressJob = launch(dispatcher) {
                sshStateManager.connectionProgress.collect { map ->
                    val p = map[machineId]
                    if (p != null && observedProgress.lastOrNull() != p) {
                        observedProgress.add(p)
                    }
                }
            }
            val connectingActionJob = launch(dispatcher) {
                connectingVm.actions.collect { observedActions.add(it) }
            }

            // Bridge SshRequestManager to FakeHerdrServer and execute the connect lifecycle
            val requestBridgeJob = launch(dispatcher) {
                sshRequestManager.bus.collect { request ->
                    when (request) {
                        is SshRequest.Connect -> debugServer.handleConnect(request.machineId)
                        is SshRequest.Disconnect -> debugServer.handleDisconnect(request.machineId)
                        is SshRequest.SetSurfaceLease ->
                            debugServer.handleSetSurfaceLease(request.machineId, request.active)
                        is SshRequest.SendEndpointCommand ->
                            debugServer.handleEndpointCommand(
                                machineId = request.machineId,
                                method = request.request.method,
                                paramsJson = request.request.paramsJson,
                            )
                        is SshRequest.SubmitAgentPrompt -> {
                            val params = buildJsonObject {
                                put("pane_id", request.paneId)
                                put("prompt", request.prompt)
                                if (!request.agentName.isNullOrBlank()) {
                                    put("name", request.agentName)
                                }
                            }.toString()
                            debugServer.handleEndpointCommand(
                                machineId = request.machineId,
                                method = "agent.prompt",
                                paramsJson = params,
                            )
                        }
                        is SshRequest.SendPaneInput ->
                            debugServer.handlePaneInput(
                                machineId = request.machineId,
                                paneId = request.paneId,
                                inputJson = request.event.toString(),
                            )
                        is SshRequest.ResizeShell ->
                            debugServer.handleResizeShell(
                                machineId = request.machineId,
                                cols = request.cols,
                                rows = request.rows,
                            )
                        is SshRequest.TriggerNetworkRoam ->
                            sshStateManager.setRoamingStatus(request.machineId ?: machineId, RoamingStatus.Healthy)
                        is SshRequest.PromptHardwareKeyTouch ->
                            sshStateManager.setHardwareKeyTouchPrompt(request)
                    }
                }
            }

            // Trigger connection handled by FakeHerdrServer
            debugServer.handleConnect(machineId)
            progressJob.cancel()
            connectingActionJob.cancel()

            assertEquals(
                listOf(
                    ConnectionProgress.AuthenticatingSsh,
                    ConnectionProgress.BootstrappingMoshOrBridge,
                    ConnectionProgress.VerifyingUdpOrHello,
                    ConnectionProgress.SyncingHerdrSnapshot,
                    ConnectionProgress.Connected,
                ),
                observedProgress,
            )
            assertEquals(ConnectionProgress.Connected, connectingVm.progress.value)
            assertTrue(
                connectingVm.steps.value.all {
                    it.status == ConnectingMachineViewModel.StepStatus.Completed
                },
            )
            assertTrue(
                observedActions.contains(ConnectingMachineViewModel.Action.NavigateToWorkspace(machineId)),
            )

            // 3. Instantiate WorkspaceTerminalViewModel for machineId and verify state, agent approval, split drag, and prompt submission
            val workspaceVm = workspaceFactory(machineId)
            assertEquals(machineId, workspaceVm.state.value.activeMachineId)
            assertIs<ActiveBackbone.MoshUdp>(workspaceVm.activeBackbone.value)

            val initialSnapshot = assertNotNull(workspaceVm.activeSnapshot.value)
            val initialSurface = assertNotNull(workspaceVm.activeSurfaceFrame.value)
            assertTrue(initialSurface.panes.isNotEmpty())
            assertTrue(initialSurface.splits.isNotEmpty())

            // Verify focused agent starts in Blocked state, then approve via Event.ApproveFocusedAgent
            val focusedAgentBefore = assertNotNull(workspaceVm.focusedPaneAgent())
            assertEquals(AgentStatus.Blocked, focusedAgentBefore.agentStatus)
            assertEquals(initialSnapshot.focusedPaneId, focusedAgentBefore.paneId)

            workspaceVm.onEvent(WorkspaceTerminalViewModel.Event.ApproveFocusedAgent)
            val focusedAgentAfter = assertNotNull(workspaceVm.focusedPaneAgent())
            assertEquals(AgentStatus.Working, focusedAgentAfter.agentStatus)

            // Drag a BSP split divider (Event.UpdateSplitRatio) and verify surface split ratio updates
            workspaceVm.onEvent(
                WorkspaceTerminalViewModel.Event.UpdateSplitRatio(
                    path = emptyList(),
                    ratio = 0.65f,
                ),
            )
            val updatedSurface = assertNotNull(workspaceVm.activeSurfaceFrame.value)
            val rootSplit = updatedSurface.splits.first { it.id == "s0:root" }
            assertTrue(abs(rootSplit.ratio - 0.65f) < 0.001f)

            // Test queuing a prompt while in RoamingReconnect and submitting a prompt when Healthy
            sshStateManager.setRoamingStatus(machineId, RoamingStatus.RoamingReconnect)
            workspaceVm.onEvent(
                WorkspaceTerminalViewModel.Event.SubmitComposerPrompt(
                    prompt = "Queued while roaming",
                ),
            )
            assertEquals(1, workspaceVm.queuedPromptsForActiveMachine.value.size)
            assertEquals("Queued while roaming", workspaceVm.queuedPromptsForActiveMachine.value.single().prompt)

            sshStateManager.setRoamingStatus(machineId, RoamingStatus.Healthy)
            sshStateManager.flushQueuedPrompts(machineId)
            workspaceVm.onEvent(
                WorkspaceTerminalViewModel.Event.SubmitComposerPrompt(
                    prompt = "Verify zero-warning Gradle and Cargo builds",
                ),
            )
            val agentAfterPrompt = assertNotNull(workspaceVm.focusedPaneAgent())
            assertTrue(agentAfterPrompt.title?.contains("Verify zero-warning Gradle and Cargo builds") == true)

            requestBridgeJob.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
