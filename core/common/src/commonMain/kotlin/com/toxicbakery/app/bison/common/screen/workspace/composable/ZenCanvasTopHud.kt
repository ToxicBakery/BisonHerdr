package com.toxicbakery.app.bison.common.screen.workspace.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.toxicbakery.app.bison.common.screen.delegate.FederatedAgentItem
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellTab
import com.toxicbakery.app.bison.protocol.ClientShellWorkspace
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_cancel
import com.toxicbakery.app.bison.resources.common_close
import com.toxicbakery.app.bison.resources.hud_agents_blocked_count
import com.toxicbakery.app.bison.resources.hud_agents_done_count
import com.toxicbakery.app.bison.resources.hud_agents_pill_label
import com.toxicbakery.app.bison.resources.hud_agents_working_count
import com.toxicbakery.app.bison.resources.hud_pane_close
import com.toxicbakery.app.bison.resources.hud_pane_zoom_toggle
import com.toxicbakery.app.bison.resources.hud_rtt_ms
import com.toxicbakery.app.bison.resources.hud_split_down
import com.toxicbakery.app.bison.resources.hud_split_right
import com.toxicbakery.app.bison.resources.hud_splits_pill_label
import com.toxicbakery.app.bison.resources.hud_tab_create
import com.toxicbakery.app.bison.resources.hud_workspace_create
import com.toxicbakery.app.bison.resources.hud_worktree_create
import com.toxicbakery.app.bison.resources.roaming_status_parked
import com.toxicbakery.app.bison.resources.roaming_status_reconnecting
import com.toxicbakery.app.bison.resources.transport_badge_mosh_roamed
import com.toxicbakery.app.bison.resources.transport_badge_mosh_udp_port
import com.toxicbakery.app.bison.resources.transport_badge_ssh_fallback
import com.toxicbakery.app.bison.resources.transport_badge_ssh_tcp
import org.jetbrains.compose.resources.stringResource

/**
 * Floating Top HUD Islands for the "Zen Canvas" (Design 2) layout:
 * - Left Host & Space Switcher Pill (`● workbox · MOSH UDP :60004 · 11ms › herdr-core (1/3) ▾`)
 * - Right Agent Attention Pill (`🔴 1 Blocked · 1 Working · ☰ Agents`) & Split Pill (`◫ Splits`).
 */
@Composable
fun ZenCanvasTopHud(
    activeMachine: HostMachine?,
    knownMachines: List<HostMachine>,
    activeBackbone: ActiveBackbone?,
    roamingStatus: RoamingStatus,
    workspaces: List<ClientShellWorkspace>,
    tabs: List<ClientShellTab>,
    focusedWorkspaceId: String?,
    focusedTabId: String?,
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
    onSplitRight: () -> Unit,
    onSplitDown: () -> Unit,
    onTogglePaneZoom: () -> Unit,
    onClosePane: () -> Unit,
    onToggleAgentDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    var showHostWorkspaceSheet by remember { mutableStateOf(false) }
    var showSplitMenu by remember { mutableStateOf(false) }

    val activeWorkspace = workspaces.firstOrNull { it.workspaceId == focusedWorkspaceId }
        ?: workspaces.firstOrNull { it.focused }
        ?: workspaces.firstOrNull()
    val workspaceTabs = tabs.filter { activeWorkspace == null || it.workspaceId == activeWorkspace.workspaceId }
        .ifEmpty { tabs }
    val activeTabIndex = workspaceTabs.indexOfFirst { it.tabId == focusedTabId || it.focused }
        .let { if (it >= 0) it + 1 else 1 }
    val totalTabs = workspaceTabs.size.coerceAtLeast(1)

    val blockedCount = federatedAgents.count { it.agent.agentStatus == AgentStatus.Blocked }
    val workingCount = federatedAgents.count { it.agent.agentStatus == AgentStatus.Working }
    val doneCount = federatedAgents.count { it.agent.agentStatus == AgentStatus.Done }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .alpha(hudOpacity),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Host & Space Switcher Pill
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .clickable { showHostWorkspaceSheet = true }
                .testTag("hudHostSwitcherPill"),
            shape = RoundedCornerShape(24.dp),
            color = palette.panelBg.copy(alpha = 0.88f),
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .border(1.dp, palette.text.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status dot
                val dotColor = when (roamingStatus) {
                    RoamingStatus.Healthy -> palette.green
                    RoamingStatus.SurfaceParked -> palette.accent
                    RoamingStatus.RoamingReconnect -> palette.red
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(8.dp))

                Text(
                    text = activeMachine?.name ?: "workbox",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.text,
                    fontWeight = FontWeight.Bold,
                )

                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.text.copy(alpha = 0.55f),
                )

                // Transport & Roaming badge
                val transportLabel = when {
                    roamingStatus == RoamingStatus.RoamingReconnect ->
                        stringResource(Res.string.roaming_status_reconnecting)
                    roamingStatus == RoamingStatus.SurfaceParked ->
                        stringResource(Res.string.roaming_status_parked)
                    activeBackbone is ActiveBackbone.MoshUdp && activeBackbone.roamedCount > 0 ->
                        stringResource(Res.string.transport_badge_mosh_roamed)
                    activeBackbone is ActiveBackbone.MoshUdp ->
                        stringResource(Res.string.transport_badge_mosh_udp_port, activeBackbone.udpPort)
                    activeBackbone is ActiveBackbone.SshEndpoint && activeBackbone.fallbackReason != null ->
                        stringResource(Res.string.transport_badge_ssh_fallback)
                    else ->
                        stringResource(Res.string.transport_badge_ssh_tcp)
                }
                val badgeAccent = when {
                    roamingStatus == RoamingStatus.RoamingReconnect -> palette.red
                    activeBackbone is ActiveBackbone.MoshUdp -> palette.green
                    else -> palette.accent
                }

                Box(
                    modifier = Modifier
                        .background(badgeAccent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = transportLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (activeBackbone != null) {
                    Text(
                        text = " · " + stringResource(Res.string.hud_rtt_ms, activeBackbone.rttMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.text.copy(alpha = 0.72f),
                    )
                }

                Text(
                    text = " › ${activeWorkspace?.label ?: "main"} ($activeTabIndex/$totalTabs) ▾",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                )
            }
        }

        // Right Agent Attention Pill & Split Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Agent Attention Pill
            val agentBorderColor = when {
                blockedCount > 0 -> palette.red
                doneCount > 0 -> palette.green
                workingCount > 0 -> palette.accent
                else -> palette.text.copy(alpha = 0.22f)
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onToggleAgentDrawer() }
                    .testTag("hudAgentAttentionPill"),
                shape = RoundedCornerShape(24.dp),
                color = palette.panelBg.copy(alpha = 0.88f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .border(1.2.dp, agentBorderColor, RoundedCornerShape(24.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (blockedCount > 0) {
                        Text(
                            text = "🔴 " + stringResource(Res.string.hud_agents_blocked_count, blockedCount) + " · ",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.red,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (workingCount > 0) {
                        Text(
                            text = "🟠 " + stringResource(Res.string.hud_agents_working_count, workingCount) + " · ",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                        )
                    } else if (doneCount > 0) {
                        Text(
                            text = "🟢 " + stringResource(Res.string.hud_agents_done_count, doneCount) + " · ",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.green,
                        )
                    }
                    Text(
                        text = "☰ " + stringResource(Res.string.hud_agents_pill_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.text,
                    )
                }
            }

            // Split Pill (`◫ Splits`)
            Box {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { showSplitMenu = true }
                        .testTag("hudSplitPill"),
                    shape = RoundedCornerShape(24.dp),
                    color = palette.panelBg.copy(alpha = 0.88f),
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .border(1.dp, palette.text.copy(alpha = 0.20f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "◫ " + stringResource(Res.string.hud_splits_pill_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.text,
                        )
                    }
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
        }
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
