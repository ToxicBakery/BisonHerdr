package com.toxicbakery.app.bison.common.screen.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.toxicbakery.app.bison.common.navigation.NavigationController
import com.toxicbakery.app.bison.common.navigation.ScreenKeyMachineFederation
import com.toxicbakery.app.bison.common.screen.workspace.composable.AgentCommandCenterDrawer
import com.toxicbakery.app.bison.common.screen.workspace.composable.SplitThumbBottomBar
import com.toxicbakery.app.bison.common.screen.workspace.composable.ZenCanvasTopHud
import com.toxicbakery.app.bison.common.terminal.TerminalSurfaceCanvas
import com.toxicbakery.app.bison.common.theme.BisonTheme
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_cancel
import com.toxicbakery.app.bison.resources.hardware_key_key_type_label
import com.toxicbakery.app.bison.resources.hardware_key_sheet_tap_nfc
import com.toxicbakery.app.bison.resources.hardware_key_sheet_title
import com.toxicbakery.app.bison.resources.hardware_key_sheet_touch_usb
import com.toxicbakery.app.bison.ssh.SshRequest
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

/**
 * Design 2 ("Zen Canvas") Full-Bleed Workspace Terminal Screen.
 *
 * Dedicates 96%+ of the tablet display to the hardware-accelerated [TerminalSurfaceCanvas],
 * overlaid by translucent floating Top HUD islands, a slide-over Federated Agent Command Center
 * drawer, and ergonomic split-thumb bottom key clusters + Native Prompt Composer pill.
 */
@Composable
fun WorkspaceTerminalScreen(
    navigationController: NavigationController,
    machineId: String,
    workspaceId: String? = null,
) {
    val viewModel: WorkspaceTerminalViewModel by rememberViewModel(arg = machineId)

    val state by viewModel.state.collectAsState()
    val knownMachines by viewModel.knownMachines.collectAsState()
    val activeMachine by viewModel.activeMachine.collectAsState()
    val activeBackbone by viewModel.activeBackbone.collectAsState()
    val roamingStatus by viewModel.roamingStatus.collectAsState()
    val activeSnapshot by viewModel.activeSnapshot.collectAsState()
    val activeSurfaceFrame by viewModel.activeSurfaceFrame.collectAsState()
    val federatedAgents by viewModel.federatedAgents.collectAsState()
    val queuedPrompts by viewModel.queuedPromptsForActiveMachine.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val themePalette by viewModel.resolvedThemePalette.collectAsState()
    val hardwareKeyPrompt by viewModel.hardwareKeyTouchPrompt.collectAsState()

    LaunchedEffect(machineId, workspaceId) {
        if (workspaceId != null) {
            viewModel.onEvent(WorkspaceTerminalViewModel.Event.FocusWorkspace(workspaceId))
        }
    }

    BisonTheme(palette = themePalette) {
        val palette = LocalBisonPalette.current
        val fontSizeSp = activeMachine?.fontSizeSp ?: appSettings.defaultFontSizeSp
        val hudOpacity = if (state.isTypingHudFaded && appSettings.autoFadeHudWhileTyping) 0.85f else 1f
        var keyboardTriggerCount by remember { mutableIntStateOf(0) }
        val focusedAgent = activeSnapshot?.let { snap ->
            val fPaneId = snap.focusedPaneId ?: activeSurfaceFrame?.panes?.firstOrNull { it.focused }?.paneId
            snap.agents.firstOrNull { it.paneId == fPaneId } ?: snap.agents.firstOrNull { it.focused }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.terminalBg)
                .testTag("workspaceTerminalScreen"),
        ) {
            // 1. Non-Overlapping Top Workspace ("Terminal Group") & Tab Bar
            ZenCanvasTopHud(
                activeMachine = activeMachine ?: HostMachine(
                    id = state.activeMachineId,
                    name = state.activeMachineId,
                    hostname = "127.0.0.1",
                    username = "user",
                ),
                knownMachines = knownMachines,
                activeBackbone = activeBackbone,
                roamingStatus = roamingStatus,
                workspaces = activeSnapshot?.workspaces.orEmpty(),
                tabs = activeSnapshot?.tabs.orEmpty(),
                panes = activeSnapshot?.panes.orEmpty(),
                focusedWorkspaceId = activeSnapshot?.focusedWorkspaceId,
                focusedTabId = activeSnapshot?.focusedTabId,
                focusedPaneId = activeSnapshot?.focusedPaneId,
                federatedAgents = federatedAgents,
                hudOpacity = hudOpacity,
                onSelectMachine = { id ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.SwitchMachine(id))
                },
                onOpenMachineFederation = {
                    navigationController.goTo(ScreenKeyMachineFederation)
                },
                onFocusWorkspace = { id ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.FocusWorkspace(id))
                },
                onCreateWorkspace = { label ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.CreateWorkspace(label))
                },
                onCreateWorktree = { branch ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.CreateWorktree(branch))
                },
                onFocusTab = { id ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.FocusTab(id))
                },
                onCreateTab = { label ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.CreateTab(label))
                },
                onCloseTab = { id ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.CloseTab(id))
                },
                onFocusPane = { id ->
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.FocusPane(id))
                },
                onSplitRight = {
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.SplitPane("right"))
                },
                onSplitDown = {
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.SplitPane("down"))
                },
                onTogglePaneZoom = {
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.TogglePaneZoom(null))
                },
                onClosePane = {
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.CloseFocusedPane)
                },
                onToggleAgentDrawer = {
                    viewModel.onEvent(WorkspaceTerminalViewModel.Event.ToggleAgentDrawer)
                },
                onRequestKeyboard = {
                    keyboardTriggerCount++
                },
            )

            // 2. Unobstructed Hardware-Accelerated Terminal Surface Canvas + Slide-Over Agent Drawer
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                TerminalSurfaceCanvas(
                    surfaceFrame = activeSurfaceFrame,
                    fontSizeSp = fontSizeSp,
                    predictiveEchoUnderlines = appSettings.predictiveEchoUnderlines,
                    keyboardTriggerCount = keyboardTriggerCount,
                    onPaneFocus = { paneId ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.FocusPane(paneId))
                    },
                    onPaneZoomToggle = { paneId ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.TogglePaneZoom(paneId))
                    },
                    onSplitRatioChanged = { path, ratio ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.UpdateSplitRatio(path, ratio))
                    },
                    onFontSizeChange = { newFontSize ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.UpdateFontSize(newFontSize))
                    },
                    onSurfaceResize = { cols, rows, cellW, cellH ->
                        viewModel.onEvent(
                            WorkspaceTerminalViewModel.Event.ResizeViewport(
                                cols = cols,
                                rows = rows,
                                cellWidthPx = cellW,
                                cellHeightPx = cellH,
                            )
                        )
                    },
                    onPaneScroll = { paneId, offsetFromBottom ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.ScrollPane(paneId, offsetFromBottom))
                    },
                    onSelectionChanged = { selection ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SelectionChanged(selection))
                    },
                    onCopySelectionRequest = { selection ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.CopySelection(selection))
                    },
                    onTextInput = { typedText ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.TypeText(typedText))
                    },
                    onSpecialKey = { keyCode ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.PressSpecialKey(keyCode))
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Slide-Over Floating Agent Command Center Drawer (Right Edge)
                if (state.isAgentDrawerOpen) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        AgentCommandCenterDrawer(
                            agents = federatedAgents,
                            onApproveAgent = { item ->
                                viewModel.onEvent(WorkspaceTerminalViewModel.Event.ApproveFederatedAgent(item))
                            },
                            onRejectAgent = { item ->
                                viewModel.onEvent(WorkspaceTerminalViewModel.Event.RejectFederatedAgent(item))
                            },
                            onJumpToAgentPane = { item ->
                                viewModel.onEvent(WorkspaceTerminalViewModel.Event.JumpToFederatedAgent(item))
                            },
                            onRefreshAgentSnippet = { item ->
                                viewModel.onEvent(WorkspaceTerminalViewModel.Event.RefreshAgentSnippet(item))
                            },
                            onCloseDrawer = {
                                viewModel.onEvent(WorkspaceTerminalViewModel.Event.SetAgentDrawerOpen(false))
                            },
                        )
                    }
                }
            }

            // 3. Non-Overlapping Ergonomic Split-Thumb Bottom Controls & Prompt Composer Bar
            if (appSettings.splitThumbLayoutEnabled) {
                SplitThumbBottomBar(
                    modifierKeyState = state.modifierKeyState,
                    leftThumbKeys = appSettings.leftThumbKeys,
                    focusedPaneAgent = focusedAgent,
                    availableAgents = activeSnapshot?.agents.orEmpty(),
                    queuedPromptCount = queuedPrompts.size,
                    hudOpacity = hudOpacity,
                    onSingleTapModifier = { modKey ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SingleTapModifier(modKey))
                    },
                    onDoubleTapModifier = { modKey ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.DoubleTapModifier(modKey))
                    },
                    onQuickKeyPress = { keyLabel ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.PressQuickKey(keyLabel))
                    },
                    onArrowKeyPress = { arrowCode ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.PressArrowKey(arrowCode))
                    },
                    onEnterPress = {
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.PressEnterKey)
                    },
                    onApproveFocusedAgent = {
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.ApproveFocusedAgent)
                    },
                    onRejectFocusedAgent = {
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.RejectFocusedAgent)
                    },
                    onSubmitPrompt = { prompt, target ->
                        viewModel.onEvent(WorkspaceTerminalViewModel.Event.SubmitPrompt(prompt, target))
                    },
                )
            }

            // 4. YubiKey / FIDO2 Hardware Security Key Touch Sheet
            val touchPrompt = hardwareKeyPrompt
            if (touchPrompt != null) {
                HardwareSecurityKeyPromptSheet(
                    prompt = touchPrompt,
                    onCancel = { viewModel.dismissHardwareKeyTouchSheet() },
                )
            }
        }
    }
}

@Composable
internal fun HardwareSecurityKeyPromptSheet(
    prompt: SshRequest.PromptHardwareKeyTouch,
    onCancel: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 460.dp)
                .border(1.5.dp, palette.accent, RoundedCornerShape(20.dp))
                .testTag("hardwareKeyPromptDialog"),
            shape = RoundedCornerShape(20.dp),
            color = palette.panelBg,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "🔑 " + stringResource(Res.string.hardware_key_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.hardware_key_key_type_label, prompt.keyType),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.mauve,
                )
                Text(
                    text = prompt.promptMessage.ifBlank {
                        stringResource(Res.string.hardware_key_sheet_touch_usb)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                )
                Text(
                    text = stringResource(Res.string.hardware_key_sheet_tap_nfc),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text.copy(alpha = 0.72f),
                )
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.red,
                        contentColor = palette.terminalBg,
                    ),
                ) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
        }
    }
}
