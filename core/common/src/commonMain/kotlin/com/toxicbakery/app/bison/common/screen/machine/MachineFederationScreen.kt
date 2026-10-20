package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toxicbakery.app.bison.common.navigation.NavigationController
import com.toxicbakery.app.bison.common.navigation.ScreenKeyConnectingMachine
import com.toxicbakery.app.bison.common.navigation.ScreenKeyMachineConfigure
import com.toxicbakery.app.bison.common.navigation.ScreenKeyWorkspaceTerminal
import com.toxicbakery.app.bison.common.theme.BisonThemePalette
import com.toxicbakery.app.bison.common.theme.BisonThemes
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.AppSettings
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.app_version_label
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.version.AppVersion
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import org.kodein.di.instance

@Composable
fun MachineFederationScreen(
    navigationController: NavigationController,
) {
    MachineFederationScreen(
        onOpenWorkspaceTerminal = { machineId ->
            navigationController.goTo(ScreenKeyWorkspaceTerminal(machineId))
        },
        onConnectMachine = { machineId ->
            navigationController.goTo(ScreenKeyConnectingMachine(machineId))
        },
        onConfigureMachine = { machineId ->
            navigationController.goTo(ScreenKeyMachineConfigure(machineId))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineFederationScreen(
    onOpenWorkspaceTerminal: (String) -> Unit,
    onConnectMachine: (String) -> Unit,
    onConfigureMachine: (String) -> Unit,
) {
    val di = localDI()
    val machineStore: MachineStore by di.instance()
    val sshStateManager: SshStateManager by di.instance()
    val endpointSnapshotBus: EndpointSnapshotBus by di.instance()
    val appSettingsStore: AppSettingsStore by di.instance()

    val machines by machineStore.knownMachines.collectAsState()
    val connectedMachines by sshStateManager.connectedMachines.collectAsState()
    val backbones by sshStateManager.activeTransportBackbone.collectAsState()
    val snapshots by endpointSnapshotBus.snapshots.collectAsState()
    val appSettings by appSettingsStore.settings.collectAsState()
    val palette = LocalBisonPalette.current
    val scope = rememberCoroutineScope()

    var showSettingsSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = palette.bgDeep,
        contentColor = palette.fgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(palette.accentPrimary),
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "BisonHerdr Fleet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                // Shared entry-point version surface (mirrors SnapshotPB DeviceBrowserScreen).
                                // Dev builds show the `-dev` marker; build with
                                // `-Pbison.stampBuildMetadata=true` to also show the `+<sha>` of the commit.
                                Text(
                                    text = stringResource(Res.string.app_version_label, AppVersion.NAME),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.fgMuted,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                text = "${connectedMachines.size} connected / ${machines.size} configured • Theme: ${palette.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.fgMuted,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Theme & Settings",
                            tint = palette.fgPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.bgSurface,
                    titleContentColor = palette.fgPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onConfigureMachine("") },
                containerColor = palette.accentPrimary,
                contentColor = palette.bgDeep,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Herdr Host",
                )
            }
        },
    ) { innerPadding ->
        if (machines.isEmpty()) {
            EmptyFleetBanner(
                onAddMachine = { onConfigureMachine("") },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 360.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(machines, key = { it.id }) { machine ->
                    val isConnected = machine.id in connectedMachines
                    val backbone = backbones[machine.id]
                    val snapshot = snapshots[machine.id]
                    HostMachineCard(
                        machine = machine,
                        isConnected = isConnected,
                        backbone = backbone,
                        snapshot = snapshot,
                        onPrimaryAction = {
                            if (isConnected) {
                                onOpenWorkspaceTerminal(machine.id)
                            } else {
                                onConnectMachine(machine.id)
                            }
                        },
                        onEdit = { onConfigureMachine(machine.id) },
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        ThemeAndSettingsBottomSheet(
            settings = appSettings,
            onSelectTheme = { themeId ->
                scope.launch { appSettingsStore.setTheme(themeId) }
            },
            onUpdateSettings = { transform ->
                scope.launch { appSettingsStore.updateSettings(transform) }
            },
            onDismiss = { showSettingsSheet = false },
        )
    }
}

@Composable
private fun HostMachineCard(
    machine: HostMachine,
    isConnected: Boolean,
    backbone: ActiveBackbone?,
    snapshot: ClientShellSnapshot?,
    onPrimaryAction: () -> Unit,
    onEdit: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    val waitingAgentsCount = snapshot?.agents?.count {
        it.agentStatus == AgentStatus.Blocked
    } ?: 0
    val runningAgentsCount = snapshot?.agents?.count {
        it.agentStatus == AgentStatus.Working
    } ?: 0

    Surface(
        color = palette.bgSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isConnected) 1.5.dp else 1.dp,
            color = if (isConnected) palette.accentSuccess.copy(alpha = 0.7f) else palette.borderSubtle,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onPrimaryAction),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) palette.accentSuccess else palette.fgMuted),
                    )
                    Column {
                        Text(
                            text = machine.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.fgPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = machine.displayAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.fgMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit ${machine.name}",
                        tint = palette.fgMuted,
                    )
                }
            }

            // Transport & Workspace telemetry pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val transportLabel = when (backbone) {
                    is ActiveBackbone.MoshUdp -> "MOSH UDP • ${backbone.rttMs}ms"
                    is ActiveBackbone.SshEndpoint -> "SSH BRIDGE • ${backbone.rttMs}ms"
                    null -> machine.transportMode.name
                }
                TelemetryBadge(
                    text = transportLabel,
                    color = if (isConnected) palette.accentSuccess else palette.accentInfo,
                )

                if (snapshot != null) {
                    TelemetryBadge(
                        text = "${snapshot.workspaces.size} WS • ${snapshot.panes.size} Panes",
                        color = palette.accentPrimary,
                    )
                }
                if (waitingAgentsCount > 0) {
                    TelemetryBadge(
                        text = "$waitingAgentsCount Agent Gate",
                        color = palette.accentWarning,
                    )
                } else if (runningAgentsCount > 0) {
                    TelemetryBadge(
                        text = "$runningAgentsCount Running",
                        color = palette.accentSecondary,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onPrimaryAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) palette.accentSuccess else palette.accentPrimary,
                        contentColor = palette.bgDeep,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isConnected) " Open Zen Canvas" else " Connect Host",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyFleetBanner(
    onAddMachine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.bgSurface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, palette.borderSubtle),
            modifier = Modifier.fillMaxWidth(0.75f),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Welcome to BisonHerdr",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.fgPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Connect to any unmodified upstream Herdr server over Mosh UDP SSP + SSH Endpoint Generation 1.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.fgMuted,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onAddMachine,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accentPrimary,
                        contentColor = palette.bgDeep,
                    ),
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Text(" Add Herdr Host", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeAndSettingsBottomSheet(
    settings: AppSettings,
    onSelectTheme: (String) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bgSurface,
        contentColor = palette.fgPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Color Themes (19 Curated Palettes)",
                style = MaterialTheme.typography.titleMedium,
                color = palette.accentPrimary,
                fontWeight = FontWeight.Bold,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BisonThemes.all.forEach { theme ->
                    ThemeSwatchChip(
                        theme = theme,
                        isSelected = settings.themeId == theme.id,
                        onClick = { onSelectTheme(theme.id) },
                    )
                }
            }

            Text(
                text = "Zen Canvas & Split-Thumb Ergonomics",
                style = MaterialTheme.typography.titleMedium,
                color = palette.accentPrimary,
                fontWeight = FontWeight.Bold,
            )

            SettingToggleRow(
                label = "Auto-fade Top HUD to 35% opacity while typing",
                checked = settings.autoFadeHudWhileTyping,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(autoFadeHudWhileTyping = enabled) }
                },
            )
            SettingToggleRow(
                label = "Mosh Predictive Local Echo Underlines",
                checked = settings.predictiveEchoUnderlines,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(predictiveEchoUnderlines = enabled) }
                },
            )
            SettingToggleRow(
                label = "Split-Thumb Left/Right Ergonomic Bottom Bar",
                checked = settings.splitThumbLayoutEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(splitThumbLayoutEnabled = enabled) }
                },
            )
            Text(
                text = stringResource(Res.string.app_version_label, AppVersion.NAME),
                style = MaterialTheme.typography.labelSmall,
                color = palette.fgMuted,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThemeSwatchChip(
    theme: BisonThemePalette,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = theme.bgSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) theme.accentPrimary else theme.borderSubtle,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(theme.accentPrimary),
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(theme.accentSecondary),
            )
            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = theme.fgPrimary,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalBisonPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.fgPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
