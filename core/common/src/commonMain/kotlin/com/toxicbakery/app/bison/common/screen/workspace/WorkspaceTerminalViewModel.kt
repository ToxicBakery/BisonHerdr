package com.toxicbakery.app.bison.common.screen.workspace

import androidx.lifecycle.viewModelScope
import com.toxicbakery.app.bison.common.screen.delegate.AgentActionDelegate
import com.toxicbakery.app.bison.common.screen.delegate.FederatedAgentItem
import com.toxicbakery.app.bison.common.screen.delegate.MachineViewModelDelegate
import com.toxicbakery.app.bison.common.screen.workspace.composable.PromptTarget
import com.toxicbakery.app.bison.common.terminal.ModifierKeyState
import com.toxicbakery.app.bison.common.terminal.TerminalModifierKey
import com.toxicbakery.app.bison.common.terminal.TerminalSelectionRange
import com.toxicbakery.app.bison.common.theme.BisonThemePalette
import com.toxicbakery.app.bison.common.theme.BisonThemes
import com.toxicbakery.app.bison.common.theme.resolveTheme
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.AppSettings
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientKeyEvent
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.ssh.PaneSurfaceBus
import com.toxicbakery.app.bison.ssh.QueuedPrompt
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.util.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WorkspaceTerminalViewModel(
    val machineId: String,
    private val machineStore: MachineStore,
    private val sshStateManager: SshStateManager,
    private val paneSurfaceBus: PaneSurfaceBus,
    private val machineViewModelDelegate: MachineViewModelDelegate,
    private val agentActionDelegate: AgentActionDelegate,
    private val appSettingsStore: AppSettingsStore? = null,
) : BaseViewModel<WorkspaceTerminalViewModel.State, WorkspaceTerminalViewModel.Event, WorkspaceTerminalViewModel.Action>(
    initialState = State(activeMachineId = machineId),
), MachineViewModelDelegate by machineViewModelDelegate, AgentActionDelegate by agentActionDelegate {

    data class State(
        val activeMachineId: String,
        val modifierKeyState: ModifierKeyState = ModifierKeyState(),
        val isAgentDrawerOpen: Boolean = false,
        val isTypingHudFaded: Boolean = false,
        val selectionRange: TerminalSelectionRange? = null,
    )

    sealed interface Event {
        data class SwitchMachine(val targetMachineId: String) : Event
        data object ToggleAgentDrawer : Event
        data class SetAgentDrawerOpen(val open: Boolean) : Event
        data class SingleTapModifier(val key: TerminalModifierKey) : Event
        data class DoubleTapModifier(val key: TerminalModifierKey) : Event
        data class TapModifierWithTimestamp(val key: TerminalModifierKey, val timestampMs: Long) : Event
        data class PressQuickKey(val keyLabel: String) : Event
        data class PressArrowKey(val keyCode: ClientKeyCode) : Event
        data class PressSpecialKey(val keyCode: ClientKeyCode) : Event
        data class TypeText(val text: String) : Event
        data object PressEnterKey : Event
        data class SubmitPrompt(val prompt: String, val target: PromptTarget = PromptTarget.FocusedPane) : Event
        data object ApproveFocusedAgent : Event
        data object RejectFocusedAgent : Event
        data class ApproveFederatedAgent(val item: FederatedAgentItem) : Event
        data class RejectFederatedAgent(val item: FederatedAgentItem) : Event
        data class JumpToFederatedAgent(val item: FederatedAgentItem) : Event
        data class RefreshAgentSnippet(val item: FederatedAgentItem) : Event
        data class FocusWorkspace(val workspaceId: String) : Event
        data class CreateWorkspace(val label: String, val cwd: String? = null) : Event
        data class CreateWorktree(val branch: String) : Event
        data class FocusTab(val tabId: String) : Event
        data class CreateTab(val label: String? = null) : Event
        data class CloseTab(val tabId: String) : Event
        data class FocusPane(val paneId: String) : Event
        data class SplitPane(val direction: String) : Event
        data class TogglePaneZoom(val paneId: String? = null) : Event
        data object CloseFocusedPane : Event
        data class UpdateSplitRatio(val path: List<Boolean>, val ratio: Float) : Event
        data class ScrollPane(val paneId: String, val offsetFromBottom: Int) : Event
        data class UpdateFontSize(val fontSizeSp: Float) : Event
        data class ResizeViewport(
            val cols: UShort,
            val rows: UShort,
            val cellWidthPx: Int,
            val cellHeightPx: Int,
        ) : Event
        data class SelectionChanged(val selection: TerminalSelectionRange?) : Event
        data class CopySelection(val selection: TerminalSelectionRange) : Event

        companion object {
            fun SubmitComposerPrompt(
                prompt: String,
                target: PromptTarget = PromptTarget.FocusedPane,
            ): SubmitPrompt = SubmitPrompt(prompt = prompt, target = target)
        }
    }

    sealed interface Action {
        data object OpenMachineFederation : Action
    }

    private val defaultSettingsFlow = MutableStateFlow(AppSettings())
    val appSettings: StateFlow<AppSettings> = appSettingsStore?.settings ?: defaultSettingsFlow

    val activeMachine: StateFlow<HostMachine?> = combine(
        machineStore.knownMachines,
        state.map { it.activeMachineId },
    ) { machines, activeId ->
        machines.firstOrNull { it.id == activeId } ?: machines.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, machineStore.getMachine(machineId))

    val activeBackbone: StateFlow<ActiveBackbone?> = combine(
        sshStateManager.activeTransportBackbone,
        state.map { it.activeMachineId },
    ) { backbones, activeId ->
        backbones[activeId]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, sshStateManager.activeTransportBackbone.value[machineId])

    val roamingStatus: StateFlow<RoamingStatus> = combine(
        sshStateManager.roamingState,
        state.map { it.activeMachineId },
    ) { states, activeId ->
        states[activeId] ?: RoamingStatus.Healthy
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RoamingStatus.Healthy)

    val activeSnapshot: StateFlow<ClientShellSnapshot?> = combine(
        snapshots,
        state.map { it.activeMachineId },
    ) { snapshotMap, activeId ->
        snapshotMap[activeId]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, snapshots.value[machineId])

    val activeSurfaceFrame: StateFlow<PaneSurfaceFrame?> = combine(
        paneSurfaceBus.surfaces,
        state.map { it.activeMachineId },
    ) { surfaceMap, activeId ->
        surfaceMap[activeId]
    }.stateIn(viewModelScope, SharingStarted.Eagerly, paneSurfaceBus.surfaces.value[machineId])

    val federatedAgents: StateFlow<List<FederatedAgentItem>> = combine(
        snapshots,
        machineStore.knownMachines,
        agentSnippets,
    ) { snapshotMap, machines, snippetMap ->
        val nameMap = machines.associate { it.id to it.name }
        aggregateFederatedAgents(snapshotMap, nameMap, snippetMap)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val queuedPromptsForActiveMachine: StateFlow<List<QueuedPrompt>> = combine(
        sshStateManager.queuedPrompts,
        state.map { it.activeMachineId },
    ) { _, activeId ->
        sshStateManager.queuedPromptsForMachine(activeId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val resolvedThemePalette: StateFlow<BisonThemePalette> = combine(
        appSettings,
        activeMachine,
    ) { settings, machine ->
        resolveTheme(
            globalThemeId = settings.themeId,
            hostOverrideThemeId = machine?.themeOverrideId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BisonThemes.GruvboxDark)

    val hardwareKeyTouchPrompt: StateFlow<SshRequest.PromptHardwareKeyTouch?> =
        sshStateManager.hardwareKeyTouchPrompt

    fun focusedPaneId(): String {
        val snapshotFocused = activeSnapshot.value?.focusedPaneId
        if (!snapshotFocused.isNullOrBlank()) return snapshotFocused
        val surfaceFocused = activeSurfaceFrame.value?.panes?.firstOrNull { it.focused }?.paneId
        if (!surfaceFocused.isNullOrBlank()) return surfaceFocused
        return activeSurfaceFrame.value?.panes?.firstOrNull()?.paneId ?: "w1:p1"
    }

    fun focusedPaneAgent(): ClientShellAgent? {
        val paneId = focusedPaneId()
        val snapshot = activeSnapshot.value ?: return null
        return snapshot.agents.firstOrNull { it.paneId == paneId }
            ?: snapshot.agents.firstOrNull { it.focused }
    }

    override fun onEvent(event: Event) {
        when (event) {
            is Event.SwitchMachine -> {
                updateState { it.copy(activeMachineId = event.targetMachineId, isTypingHudFaded = false) }
                viewModelScope.launch {
                    if (!sshStateManager.isMachineConnected(event.targetMachineId)) {
                        connect(event.targetMachineId)
                    }
                    setSurfaceLease(event.targetMachineId, active = true)
                }
            }

            Event.ToggleAgentDrawer -> {
                updateState { it.copy(isAgentDrawerOpen = !it.isAgentDrawerOpen) }
            }

            is Event.SetAgentDrawerOpen -> {
                updateState { it.copy(isAgentDrawerOpen = event.open) }
            }

            is Event.SingleTapModifier -> {
                updateState { it.copy(modifierKeyState = it.modifierKeyState.onSingleTap(event.key)) }
            }

            is Event.DoubleTapModifier -> {
                updateState { it.copy(modifierKeyState = it.modifierKeyState.onDoubleTap(event.key)) }
            }

            is Event.TapModifierWithTimestamp -> {
                updateState {
                    it.copy(
                        modifierKeyState = it.modifierKeyState.onTap(
                            key = event.key,
                            timestampMs = event.timestampMs,
                        )
                    )
                }
            }

            is Event.PressQuickKey -> {
                handleQuickKeyPress(event.keyLabel)
            }

            is Event.PressArrowKey -> {
                handleArrowKeyPress(event.keyCode)
            }

            is Event.PressSpecialKey -> {
                handleArrowKeyPress(event.keyCode)
            }

            is Event.TypeText -> {
                handleTypeText(event.text)
            }

            Event.PressEnterKey -> {
                handleEnterKeyPress()
            }

            is Event.SubmitPrompt -> {
                handleSubmitPrompt(event.prompt, event.target)
            }

            Event.ApproveFocusedAgent -> {
                val targetMachineId = state.value.activeMachineId
                val agent = focusedPaneAgent()
                val paneId = agent?.paneId ?: focusedPaneId()
                viewModelScope.launch {
                    approveAgent(targetMachineId, paneId, agent?.name)
                }
            }

            Event.RejectFocusedAgent -> {
                val targetMachineId = state.value.activeMachineId
                val agent = focusedPaneAgent()
                val paneId = agent?.paneId ?: focusedPaneId()
                viewModelScope.launch {
                    rejectAgent(targetMachineId, paneId, agent?.name)
                }
            }

            is Event.ApproveFederatedAgent -> {
                viewModelScope.launch {
                    approveAgent(
                        machineId = event.item.machineId,
                        paneId = event.item.agent.paneId,
                        agentName = event.item.agent.name,
                    )
                }
            }

            is Event.RejectFederatedAgent -> {
                viewModelScope.launch {
                    rejectAgent(
                        machineId = event.item.machineId,
                        paneId = event.item.agent.paneId,
                        agentName = event.item.agent.name,
                    )
                }
            }

            is Event.JumpToFederatedAgent -> {
                val item = event.item
                updateState {
                    it.copy(
                        activeMachineId = item.machineId,
                        isAgentDrawerOpen = false,
                    )
                }
                viewModelScope.launch {
                    focusAgentPane(
                        machineId = item.machineId,
                        paneId = item.agent.paneId,
                        agentName = item.agent.name,
                    )
                }
            }

            is Event.RefreshAgentSnippet -> {
                val item = event.item
                viewModelScope.launch {
                    requestAgentSnippet(
                        machineId = item.machineId,
                        paneId = item.agent.paneId,
                        agentName = item.agent.name,
                        agentStatus = item.agent.agentStatus,
                    )
                }
            }

            is Event.FocusWorkspace -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    focusWorkspace(targetMachineId, event.workspaceId)
                }
            }

            is Event.CreateWorkspace -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    createWorkspace(targetMachineId, event.label, event.cwd)
                }
            }

            is Event.CreateWorktree -> {
                val targetMachineId = state.value.activeMachineId
                val wsId = activeSnapshot.value?.focusedWorkspaceId
                viewModelScope.launch {
                    createWorktree(targetMachineId, event.branch, wsId)
                }
            }

            is Event.FocusTab -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    focusTab(targetMachineId, event.tabId)
                }
            }

            is Event.CreateTab -> {
                val targetMachineId = state.value.activeMachineId
                val wsId = activeSnapshot.value?.focusedWorkspaceId
                viewModelScope.launch {
                    createTab(targetMachineId, wsId, event.label)
                }
            }

            is Event.CloseTab -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    closeTab(targetMachineId, event.tabId)
                }
            }

            is Event.FocusPane -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    focusPane(targetMachineId, event.paneId)
                }
            }

            is Event.SplitPane -> {
                val targetMachineId = state.value.activeMachineId
                val paneId = focusedPaneId()
                viewModelScope.launch {
                    splitPane(targetMachineId, paneId = paneId, direction = event.direction)
                }
            }

            is Event.TogglePaneZoom -> {
                val targetMachineId = state.value.activeMachineId
                val paneId = event.paneId ?: focusedPaneId()
                viewModelScope.launch {
                    zoomPane(targetMachineId, paneId = paneId, mode = "toggle")
                }
            }

            Event.CloseFocusedPane -> {
                val targetMachineId = state.value.activeMachineId
                val paneId = focusedPaneId()
                viewModelScope.launch {
                    closePane(targetMachineId, paneId)
                }
            }

            is Event.UpdateSplitRatio -> {
                val targetMachineId = state.value.activeMachineId
                val tabId = activeSnapshot.value?.focusedTabId
                viewModelScope.launch {
                    setSplitRatio(
                        machineId = targetMachineId,
                        path = event.path,
                        ratio = event.ratio,
                        tabId = tabId,
                    )
                }
            }

            is Event.ScrollPane -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    scrollPane(targetMachineId, event.paneId, event.offsetFromBottom)
                }
            }

            is Event.UpdateFontSize -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    updateFontSize(targetMachineId, event.fontSizeSp)
                }
            }

            is Event.ResizeViewport -> {
                val targetMachineId = state.value.activeMachineId
                viewModelScope.launch {
                    resizeShell(
                        machineId = targetMachineId,
                        cols = event.cols,
                        rows = event.rows,
                        cellWidthPx = event.cellWidthPx,
                        cellHeightPx = event.cellHeightPx,
                    )
                }
            }

            is Event.SelectionChanged -> {
                updateState { it.copy(selectionRange = event.selection) }
            }

            is Event.CopySelection -> {
                val targetMachineId = state.value.activeMachineId
                val sel = event.selection
                val rev = activeSurfaceFrame.value?.panes
                    ?.firstOrNull { it.paneId == sel.paneId }
                    ?.contentRevision
                viewModelScope.launch {
                    copySelection(
                        machineId = targetMachineId,
                        paneId = sel.paneId,
                        startRow = sel.startRow,
                        startCol = sel.startCol,
                        endRow = sel.endRow,
                        endCol = sel.endCol,
                        contentRevision = rev,
                    )
                }
            }
        }
    }

    private fun handleQuickKeyPress(keyLabel: String) {
        val currentState = state.value
        val targetMachineId = currentState.activeMachineId
        val paneId = focusedPaneId()
        val mods = currentState.modifierKeyState
        val chord = mods.formatKeyChord(keyLabel)
        updateState {
            it.copy(
                modifierKeyState = mods.consumeOneShot(),
                isTypingHudFaded = appSettings.value.autoFadeHudWhileTyping,
            )
        }
        viewModelScope.launch {
            when (keyLabel.uppercase()) {
                "ESC" -> sendKeyEvent(
                    targetMachineId,
                    paneId,
                    ClientKeyEvent(
                        code = ClientKeyCode.Escape,
                        ctrl = mods.ctrl.isActive,
                        alt = mods.alt.isActive,
                        shift = mods.shift.isActive,
                    ),
                )
                "TAB" -> sendKeyEvent(
                    targetMachineId,
                    paneId,
                    ClientKeyEvent(
                        code = ClientKeyCode.Tab,
                        ctrl = mods.ctrl.isActive,
                        alt = mods.alt.isActive,
                        shift = mods.shift.isActive,
                    ),
                )
                "^C" -> sendKeyEvent(
                    targetMachineId,
                    paneId,
                    ClientKeyEvent(
                        code = ClientKeyCode.CharC,
                        ctrl = true,
                        alt = mods.alt.isActive,
                        shift = mods.shift.isActive,
                    ),
                )
                "^B" -> sendKeyEvent(
                    targetMachineId,
                    paneId,
                    ClientKeyEvent(
                        code = ClientKeyCode.CharB,
                        ctrl = true,
                        alt = mods.alt.isActive,
                        shift = mods.shift.isActive,
                    ),
                )
                else -> {
                    if (mods.hasActiveModifiers || keyLabel.startsWith("^")) {
                        sendKeyChord(targetMachineId, paneId, chord)
                    } else {
                        sendLiteralText(targetMachineId, paneId, keyLabel)
                    }
                }
            }
        }
    }

    private fun handleArrowKeyPress(keyCode: ClientKeyCode) {
        val currentState = state.value
        val targetMachineId = currentState.activeMachineId
        val paneId = focusedPaneId()
        val mods = currentState.modifierKeyState
        updateState { it.copy(modifierKeyState = mods.consumeOneShot()) }
        viewModelScope.launch {
            sendKeyEvent(
                targetMachineId,
                paneId,
                ClientKeyEvent(
                    code = keyCode,
                    ctrl = mods.ctrl.isActive,
                    alt = mods.alt.isActive,
                    shift = mods.shift.isActive,
                ),
            )
        }
    }

    private fun handleEnterKeyPress() {
        val currentState = state.value
        val targetMachineId = currentState.activeMachineId
        val paneId = focusedPaneId()
        val mods = currentState.modifierKeyState
        updateState { it.copy(modifierKeyState = mods.consumeOneShot()) }
        viewModelScope.launch {
            sendKeyEvent(
                targetMachineId,
                paneId,
                ClientKeyEvent(
                    code = ClientKeyCode.Enter,
                    ctrl = mods.ctrl.isActive,
                    alt = mods.alt.isActive,
                    shift = mods.shift.isActive,
                ),
            )
        }
    }

    private fun handleTypeText(text: String) {
        if (text.isEmpty()) return
        val currentState = state.value
        val targetMachineId = currentState.activeMachineId
        val paneId = focusedPaneId()
        val mods = currentState.modifierKeyState
        updateState {
            it.copy(
                modifierKeyState = mods.consumeOneShot(),
                isTypingHudFaded = appSettings.value.autoFadeHudWhileTyping,
            )
        }
        viewModelScope.launch {
            if (text == "\n" || text == "\r") {
                sendKeyEvent(
                    targetMachineId,
                    paneId,
                    ClientKeyEvent(
                        code = ClientKeyCode.Enter,
                        ctrl = mods.ctrl.isActive,
                        alt = mods.alt.isActive,
                        shift = mods.shift.isActive,
                    ),
                )
            } else if (mods.hasActiveModifiers && text.length == 1) {
                val chord = mods.formatKeyChord(text)
                sendKeyChord(targetMachineId, paneId, chord)
            } else {
                sendLiteralText(targetMachineId, paneId, text)
            }
        }
    }

    private fun handleSubmitPrompt(prompt: String, target: PromptTarget) {
        if (prompt.isBlank()) return
        val targetMachineId = state.value.activeMachineId
        val isRoaming = roamingStatus.value == RoamingStatus.RoamingReconnect ||
            !sshStateManager.isMachineConnected(targetMachineId)

        viewModelScope.launch {
            when (target) {
                PromptTarget.FocusedPane -> {
                    val agent = focusedPaneAgent()
                    val paneId = agent?.paneId ?: focusedPaneId()
                    if (isRoaming) {
                        sshStateManager.enqueuePrompt(
                            machineId = targetMachineId,
                            paneId = paneId,
                            prompt = prompt,
                            agentName = agent?.name,
                        )
                    }
                    submitPrompt(
                        machineId = targetMachineId,
                        paneId = paneId,
                        agentName = agent?.name,
                        prompt = prompt,
                    )
                }

                is PromptTarget.SpecificAgent -> {
                    if (isRoaming) {
                        sshStateManager.enqueuePrompt(
                            machineId = targetMachineId,
                            paneId = target.paneId,
                            prompt = prompt,
                            agentName = target.agentName,
                        )
                    }
                    submitPrompt(
                        machineId = targetMachineId,
                        paneId = target.paneId,
                        agentName = target.agentName,
                        prompt = prompt,
                    )
                }

                PromptTarget.BroadcastAllPanes -> {
                    val panes = activeSnapshot.value?.panes?.map { it.paneId }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(focusedPaneId())
                    panes.forEach { paneId ->
                        val paneAgent = activeSnapshot.value?.agents?.firstOrNull { it.paneId == paneId }
                        if (isRoaming) {
                            sshStateManager.enqueuePrompt(
                                machineId = targetMachineId,
                                paneId = paneId,
                                prompt = prompt,
                                agentName = paneAgent?.name,
                            )
                        }
                        submitPrompt(
                            machineId = targetMachineId,
                            paneId = paneId,
                            agentName = paneAgent?.name,
                            prompt = prompt,
                        )
                    }
                }
            }
        }
    }

    fun dismissHardwareKeyTouchSheet() {
        sshStateManager.dismissHardwareKeyTouchPrompt()
    }
}
