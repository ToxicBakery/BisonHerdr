package com.toxicbakery.app.bison.common.screen.workspace.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.toxicbakery.app.bison.common.screen.delegate.FederatedAgentItem
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellPane
import com.toxicbakery.app.bison.protocol.ClientShellTab
import com.toxicbakery.app.bison.protocol.ClientShellWorkspace
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_close
import com.toxicbakery.app.bison.resources.hud_agents_pill_label
import com.toxicbakery.app.bison.resources.hud_pane_close
import com.toxicbakery.app.bison.resources.hud_pane_zoom_toggle
import com.toxicbakery.app.bison.resources.hud_split_down
import com.toxicbakery.app.bison.resources.hud_split_right
import com.toxicbakery.app.bison.resources.hud_splits_pill_label
import com.toxicbakery.app.bison.resources.hud_tab_create
import com.toxicbakery.app.bison.resources.hud_workspace_create
import com.toxicbakery.app.bison.resources.hud_worktree_create
import org.jetbrains.compose.resources.stringResource

/**
 * Non-overlapping Top Workspace & Tab Bar for the Workspace Terminal Screen:
 * - Row 1: Back to Hosts (`‹ Hosts`), compact Host status badge (`● epyc · 11ms`),
 *   horizontally scrollable 1-tap Workspace ("Terminal Group") pills, and `[⌨]`, `[◫ Splits]`, `[☰ Agents]` actions.
 * - Row 2: Horizontally scrollable 1-tap Tab pills within the active workspace + Pane pills when split.
 */
@Composable
fun ZenCanvasTopHud(
    activeMachine: HostMachine?,
    knownMachines: List<HostMachine>,
    activeBackbone: ActiveBackbone?,
    roamingStatus: RoamingStatus,
    workspaces: List<ClientShellWorkspace>,
    tabs: List<ClientShellTab>,
    panes: List<ClientShellPane> = emptyList(),
    focusedWorkspaceId: String?,
    focusedTabId: String?,
    focusedPaneId: String? = null,
    federatedAgents: List<FederatedAgentItem>,
    hudOpacity: Float = 1f,
    onSelectMachine: (machineId: String) -> Unit,
    onOpenMachineFederation: () -> Unit,
    onFocusWorkspace: (workspaceId: String) -> Unit,
    onCreateWorkspace: (label: String) -> Unit,
    onCreateWorktree: (branch: String) -> Unit,
    onFocusTab: (tabId: String) -> Unit,
    onCreateTab: (label: String?) -> Unit,
    onCloseTab: (tabId: String) -> Unit,
    onFocusPane: (paneId: String) -> Unit = {},
    onSplitRight: () -> Unit,
    onSplitDown: () -> Unit,
    onTogglePaneZoom: () -> Unit,
    onClosePane: () -> Unit,
    onToggleAgentDrawer: () -> Unit,
    onRequestKeyboard: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    var showHostWorkspaceSheet by remember { mutableStateOf(false) }
    var showSplitMenu by remember { mutableStateOf(false) }

    val activeWorkspace = remember(workspaces, focusedWorkspaceId) {
        workspaces.firstOrNull { it.workspaceId == focusedWorkspaceId }
            ?: workspaces.firstOrNull { it.focused }
            ?: workspaces.firstOrNull()
    }
    val workspaceTabs = remember(tabs, activeWorkspace) {
        tabs.filter { activeWorkspace == null || it.workspaceId == activeWorkspace.workspaceId }
            .ifEmpty { tabs }
    }
    val activeTab = remember(workspaceTabs, focusedTabId) {
        workspaceTabs.firstOrNull { it.tabId == focusedTabId }
            ?: workspaceTabs.firstOrNull { it.focused }
            ?: workspaceTabs.firstOrNull()
    }
    val activeTabPanes = remember(panes, activeTab) {
        panes.filter { activeTab == null || it.tabId == activeTab.tabId }
    }

    val blockedCount = remember(federatedAgents) {
        federatedAgents.count { it.agent.agentStatus == AgentStatus.Blocked }
    }
    val workingCount = remember(federatedAgents) {
        federatedAgents.count { it.agent.agentStatus == AgentStatus.Working }
    }
    val doneCount = remember(federatedAgents) {
        federatedAgents.count { it.agent.agentStatus == AgentStatus.Done }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.panelBg)
            .alpha(hudOpacity),
    ) {
        // Row 1: Back | Host Pill | 1-Tap Workspace ("Terminal Group") Pills | Keyboard, Splits, Agents
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Back to Hosts button
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenMachineFederation() }
                    .testTag("hudBackToHostsButton"),
                shape = RoundedCornerShape(10.dp),
                color = palette.terminalBg,
            ) {
                Text(
                    text = "‹ Hosts",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }

            // Compact Host & Transport Pill
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showHostWorkspaceSheet = true }
                    .testTag("hudHostSwitcherPill"),
                shape = RoundedCornerShape(10.dp),
                color = palette.terminalBg,
            ) {
                Row(
                    modifier = Modifier
                        .border(1.dp, palette.text.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dotColor = when (roamingStatus) {
                        RoamingStatus.Healthy -> palette.green
                        RoamingStatus.SurfaceParked -> palette.accent
                        RoamingStatus.RoamingReconnect -> palette.red
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = activeMachine?.name ?: "host",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.text,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (activeBackbone != null) {
                        Text(
                            text = " · ${activeBackbone.rttMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text.copy(alpha = 0.65f),
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = " ▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                    )
                }
            }

            // Horizontally Scrollable 1-Tap Workspace ("Terminal Group") Pills
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                workspaces.forEachIndexed { idx, ws ->
                    key(ws.workspaceId) {
                        val isFocused = ws.workspaceId == (activeWorkspace?.workspaceId ?: focusedWorkspaceId)
                        val wsStatusColor = when (ws.agentStatus) {
                            AgentStatus.Blocked -> palette.red
                            AgentStatus.Working -> palette.accent
                            AgentStatus.Done -> palette.green
                            else -> null
                        }
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onFocusWorkspace(ws.workspaceId) }
                                .testTag("hudWorkspacePill_${ws.workspaceId}"),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isFocused) palette.accent else palette.terminalBg,
                        ) {
                            Row(
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = if (isFocused) palette.accent else (wsStatusColor ?: palette.text.copy(alpha = 0.16f)),
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (wsStatusColor != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                if (isFocused) palette.terminalBg else wsStatusColor,
                                                CircleShape,
                                            )
                                    )
                                }
                                Text(
                                    text = "${idx + 1}: ${ws.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isFocused) palette.terminalBg else palette.text,
                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Quick + Workspace button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onCreateWorkspace("space-${workspaces.size + 1}") }
                        .testTag("hudNewWorkspacePill"),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.terminalBg,
                ) {
                    Text(
                        text = "+ Group",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                        modifier = Modifier
                            .border(1.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }

            // Right Action Buttons: Keyboard | Splits | Agents
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // Soft Keyboard Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onRequestKeyboard() }
                        .testTag("hudKeyboardButton"),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.terminalBg,
                ) {
                    Text(
                        text = "⌨ Keybd",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.text,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .border(1.dp, palette.text.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }

                // Split Pill (`◫ Splits`)
                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showSplitMenu = true }
                            .testTag("hudSplitPill"),
                        shape = RoundedCornerShape(10.dp),
                        color = palette.terminalBg,
                    ) {
                        Text(
                            text = "◫ " + stringResource(Res.string.hud_splits_pill_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.text,
                            modifier = Modifier
                                .border(1.dp, palette.text.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = showSplitMenu,
                        onDismissRequest = { showSplitMenu = false },
                        modifier = Modifier.background(palette.panelBg),
                    ) {
                        DropdownMenuItem(
                            text = { Text("◫ " + stringResource(Res.string.hud_split_right), color = palette.text) },
                            onClick = {
                                showSplitMenu = false
                                onSplitRight()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("⊟ " + stringResource(Res.string.hud_split_down), color = palette.text) },
                            onClick = {
                                showSplitMenu = false
                                onSplitDown()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("⤢ " + stringResource(Res.string.hud_pane_zoom_toggle), color = palette.accent) },
                            onClick = {
                                showSplitMenu = false
                                onTogglePaneZoom()
                            },
                        )
                        HorizontalDivider(color = palette.text.copy(alpha = 0.14f))
                        DropdownMenuItem(
                            text = { Text("✕ " + stringResource(Res.string.hud_pane_close), color = palette.red) },
                            onClick = {
                                showSplitMenu = false
                                onClosePane()
                            },
                        )
                    }
                }

                // Agent Attention Pill
                val agentBorderColor = when {
                    blockedCount > 0 -> palette.red
                    doneCount > 0 -> palette.green
                    workingCount > 0 -> palette.accent
                    else -> palette.text.copy(alpha = 0.22f)
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleAgentDrawer() }
                        .testTag("hudAgentAttentionPill"),
                    shape = RoundedCornerShape(10.dp),
                    color = palette.terminalBg,
                ) {
                    Row(
                        modifier = Modifier
                            .border(1.2.dp, agentBorderColor, RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (blockedCount > 0) {
                            Text(
                                text = "🔴$blockedCount ",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.red,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (workingCount > 0) {
                            Text(
                                text = "🟠$workingCount ",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.accent,
                            )
                        } else if (doneCount > 0) {
                            Text(
                                text = "🟢$doneCount ",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.green,
                            )
                        }
                        Text(
                            text = "☰ " + stringResource(Res.string.hud_agents_pill_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.text,
                        )
                    }
                }
            }
        }

        // Row 2: Tabs within the Active Workspace + Panes within the Active Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.terminalBg.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tabs:",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.text.copy(alpha = 0.55f),
                )
                workspaceTabs.forEach { tab ->
                    key(tab.tabId) {
                        val isTabFocused = tab.tabId == (activeTab?.tabId ?: focusedTabId)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onFocusTab(tab.tabId) }
                                .testTag("hudTabPill_${tab.tabId}"),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isTabFocused) palette.accent.copy(alpha = 0.22f) else palette.panelBg,
                        ) {
                            Row(
                                modifier = Modifier
                                    .border(
                                        width = 1.dp,
                                        color = if (isTabFocused) palette.accent else palette.text.copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "${tab.number}: ${tab.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isTabFocused) palette.accent else palette.text.copy(alpha = 0.85f),
                                    fontWeight = if (isTabFocused) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                                if (workspaceTabs.size > 1 && isTabFocused) {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.red,
                                        modifier = Modifier.clickable { onCloseTab(tab.tabId) },
                                    )
                                }
                            }
                        }
                    }
                }

                // + Tab button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCreateTab(null) }
                        .testTag("hudNewTabPill"),
                    shape = RoundedCornerShape(8.dp),
                    color = palette.panelBg,
                ) {
                    Text(
                        text = "+ Tab",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        modifier = Modifier
                            .border(1.dp, palette.accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            // If active tab has multiple panes, show 1-tap pane switcher pills
            if (activeTabPanes.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "Panes:",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text.copy(alpha = 0.55f),
                    )
                    activeTabPanes.forEachIndexed { idx, pane ->
                        key(pane.paneId) {
                            val isPaneFocused = pane.paneId == focusedPaneId || pane.focused
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onFocusPane(pane.paneId) }
                                    .testTag("hudPanePill_${pane.paneId}"),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isPaneFocused) palette.accent else palette.panelBg,
                            ) {
                                Text(
                                    text = "#${idx + 1} ${pane.label.ifBlank { pane.paneId }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPaneFocused) palette.terminalBg else palette.text,
                                    fontWeight = if (isPaneFocused) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = palette.text.copy(alpha = 0.14f), thickness = 1.dp)
    }

    if (showHostWorkspaceSheet) {
        HostWorkspacePopoverSheet(
            activeMachine = activeMachine,
            knownMachines = knownMachines,
            workspaces = workspaces,
            tabs = workspaceTabs,
            focusedWorkspaceId = activeWorkspace?.workspaceId,
            focusedTabId = focusedTabId,
            onDismiss = { showHostWorkspaceSheet = false },
            onSelectMachine = { id ->
                showHostWorkspaceSheet = false
                onSelectMachine(id)
            },
            onOpenMachineFederation = {
                showHostWorkspaceSheet = false
                onOpenMachineFederation()
            },
            onFocusWorkspace = { id ->
                showHostWorkspaceSheet = false
                onFocusWorkspace(id)
            },
            onCreateWorkspace = { label ->
                showHostWorkspaceSheet = false
                onCreateWorkspace(label)
            },
            onCreateWorktree = { branch ->
                showHostWorkspaceSheet = false
                onCreateWorktree(branch)
            },
            onFocusTab = { id ->
                showHostWorkspaceSheet = false
                onFocusTab(id)
            },
            onCreateTab = { label ->
                showHostWorkspaceSheet = false
                onCreateTab(label)
            },
            onCloseTab = { id ->
                onCloseTab(id)
            },
        )
    }
}

@Composable
private fun HostWorkspacePopoverSheet(
    activeMachine: HostMachine?,
    knownMachines: List<HostMachine>,
    workspaces: List<ClientShellWorkspace>,
    tabs: List<ClientShellTab>,
    focusedWorkspaceId: String?,
    focusedTabId: String?,
    onDismiss: () -> Unit,
    onSelectMachine: (String) -> Unit,
    onOpenMachineFederation: () -> Unit,
    onFocusWorkspace: (String) -> Unit,
    onCreateWorkspace: (String) -> Unit,
    onCreateWorktree: (String) -> Unit,
    onFocusTab: (String) -> Unit,
    onCreateTab: (String?) -> Unit,
    onCloseTab: (String) -> Unit,
) {
    val palette = LocalBisonPalette.current
    var newWorkspaceName by remember { mutableStateOf("") }
    var newWorktreeBranch by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 380.dp, max = 560.dp)
                .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = palette.panelBg,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header: Host Fleet Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hosts, Workspaces & Tabs",
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.text,
                    )
                    TextButton(onClick = onOpenMachineFederation) {
                        Text("All Machines ›", color = palette.accent)
                    }
                }

                // Connected / Known Hosts row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    knownMachines.forEach { machine ->
                        val isSelected = machine.id == activeMachine?.id
                        OutlinedButton(
                            onClick = { onSelectMachine(machine.id) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) palette.accent.copy(alpha = 0.20f) else palette.terminalBg,
                                contentColor = if (isSelected) palette.accent else palette.text,
                            ),
                        ) {
                            Text(machine.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                HorizontalDivider(color = palette.text.copy(alpha = 0.14f))

                // Workspaces & Git Worktrees
                Text(
                    text = "Workspaces & Git Worktrees",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text,
                )
                workspaces.forEach { ws ->
                    val isFocused = ws.workspaceId == focusedWorkspaceId || ws.focused
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isFocused) palette.accent.copy(alpha = 0.16f) else palette.terminalBg.copy(alpha = 0.6f)
                            )
                            .clickable { onFocusWorkspace(ws.workspaceId) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = ws.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isFocused) palette.accent else palette.text,
                                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                            )
                            val branchText = ws.branch ?: ws.worktree?.label
                            if (!branchText.isNullOrBlank()) {
                                Text(
                                    text = "⎇ $branchText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.mauve,
                                )
                            }
                        }
                        if (ws.agentStatus != AgentStatus.Unknown) {
                            Text(
                                text = ws.agentStatus.wireName.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = when (ws.agentStatus) {
                                    AgentStatus.Blocked -> palette.red
                                    AgentStatus.Working -> palette.accent
                                    AgentStatus.Done -> palette.green
                                    else -> palette.text.copy(alpha = 0.6f)
                                },
                            )
                        }
                    }
                }

                // Quick Create Workspace / Git Worktree
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newWorkspaceName,
                        onValueChange = { newWorkspaceName = it },
                        placeholder = { Text(stringResource(Res.string.hud_workspace_create), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            onCreateWorkspace(newWorkspaceName.ifBlank { "workspace-${workspaces.size + 1}" })
                            newWorkspaceName = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.terminalBg),
                    ) {
                        Text("+ Space")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newWorktreeBranch,
                        onValueChange = { newWorktreeBranch = it },
                        placeholder = { Text(stringResource(Res.string.hud_worktree_create) + " (branch)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            if (newWorktreeBranch.isNotBlank()) {
                                onCreateWorktree(newWorktreeBranch.trim())
                                newWorktreeBranch = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.mauve, contentColor = palette.terminalBg),
                    ) {
                        Text("+ Worktree")
                    }
                }

                HorizontalDivider(color = palette.text.copy(alpha = 0.14f))

                // Tabs in Active Workspace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tabs (${tabs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.text,
                    )
                    TextButton(onClick = { onCreateTab(null) }) {
                        Text("+ " + stringResource(Res.string.hud_tab_create), color = palette.accent)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEach { tab ->
                        val isFocused = tab.tabId == focusedTabId || tab.focused
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isFocused) palette.accent.copy(alpha = 0.22f) else palette.terminalBg,
                            modifier = Modifier.clickable { onFocusTab(tab.tabId) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${tab.number}: ${tab.label}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isFocused) palette.accent else palette.text,
                                )
                                if (tabs.size > 1) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.red,
                                        modifier = Modifier.clickable { onCloseTab(tab.tabId) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.common_close), color = palette.text)
                    }
                }
            }
        }
    }
}
