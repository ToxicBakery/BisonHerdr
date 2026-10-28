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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import com.toxicbakery.app.bison.common.version.RemoteRelease
import com.toxicbakery.app.bison.common.version.VersionCheck
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.AppSettings
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.ClientShellSnapshot
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.app_version_label
import com.toxicbakery.app.bison.resources.frame_update_available
import com.toxicbakery.app.bison.resources.machine_federation_welcome_body
import com.toxicbakery.app.bison.resources.settings_about_new
import com.toxicbakery.app.bison.resources.settings_about_open_release
import com.toxicbakery.app.bison.resources.settings_about_update_available
import com.toxicbakery.app.bison.resources.settings_app_check_updates
import com.toxicbakery.app.bison.resources.settings_app_check_updates_summary
import com.toxicbakery.app.bison.resources.settings_predictive_echo_label
import com.toxicbakery.app.bison.ssh.CredentialChecker
import com.toxicbakery.app.bison.ssh.CredentialState
import com.toxicbakery.app.bison.ssh.EndpointSnapshotBus
import com.toxicbakery.app.bison.ssh.NeedsUserCause
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.version.AppVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.localDI
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

/** Test tag of the top-bar chip indicating that a new release version is available. */
const val UPDATE_AVAILABLE_TAG: String = "updateAvailable"

/** Test tag of the "Update available" card in the Settings/About sheet. */
const val ABOUT_UPDATE_AVAILABLE_TAG: String = "about-update-available"

/** Test tag of the button that opens the GitHub Release page. */
const val ABOUT_OPEN_RELEASE_TAG: String = "about-open-release"

/** Test tag of the switch that toggles automatic daily update checks. */
const val CHECK_FOR_UPDATES_TAG: String = "app-check-updates"

/** Test tag of the text on a machine card that tells why the last connection stopped. */
const val MACHINE_NEEDS_USER_TAG: String = "machine-needs-user"

/** The badge of a machine that needs a password or a key before a login can work. */
const val NEEDS_SIGN_IN_LABEL: String = "Needs sign-in"

/**
 * The action of a machine that needs its password again. It connects, and the connect asks for the password and
 * offers to save it. The edit button of the card opens the machine editor.
 */
const val TYPE_PASSWORD_ACTION: String = "Type Password"

/** The action of a machine that needs a new key. It opens the machine editor, which makes the key. */
const val MAKE_NEW_KEY_ACTION: String = "Make a New Key"

/** The notice after a restore from a backup, when a machine needs a password or a key again. */
const val RESTORED_FROM_BACKUP_NOTICE: String =
    "A backup does not keep passwords or device keys. Each machine that shows \"$NEEDS_SIGN_IN_LABEL\" " +
        "needs its password again or a new key on this device."

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
    val credentialChecker: CredentialChecker? by di.instanceOrNull()

    val machines by machineStore.knownMachines.collectAsState()
    val restoredFromBackup by machineStore.restoredFromBackup.collectAsState()
    // The screen calculates the states again when the machines change and when the user comes back to the screen.
    val credentialStates by produceState(emptyMap<String, CredentialState>(), machines, credentialChecker) {
        value = credentialStatesOf(machines, credentialChecker)
    }
    val connectedMachines by sshStateManager.connectedMachines.collectAsState()
    val needsUser by sshStateManager.needsUser.collectAsState()
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
                    val unseenUpdateVersion = VersionCheck.unseenReleaseVersion(appSettings, AppVersion.CODE)
                    if (unseenUpdateVersion != null && VersionCheck.shouldShowTopBarNotification(appSettings, AppVersion.CODE)) {
                        UpdateAvailableIndicator(
                            version = unseenUpdateVersion,
                            onClick = {
                                scope.launch {
                                    appSettingsStore.updateSettings(VersionCheck::acknowledgeRelease)
                                }
                                showSettingsSheet = true
                            },
                        )
                    }
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
                if (restoredFromBackup && credentialStates.values.any { it.needsSignIn }) {
                    item(key = RESTORED_FROM_BACKUP_NOTICE, span = { GridItemSpan(maxLineSpan) }) {
                        RestoredFromBackupNotice()
                    }
                }
                items(machines, key = { it.id }) { machine ->
                    val isConnected = machine.id in connectedMachines
                    val backbone = backbones[machine.id]
                    val snapshot = snapshots[machine.id]
                    val credentialState = credentialStates[machine.id] ?: CredentialState.Ready
                    HostMachineCard(
                        machine = machine,
                        isConnected = isConnected,
                        backbone = backbone,
                        snapshot = snapshot,
                        credentialState = credentialState,
                        needsUserCause = needsUser[machine.id],
                        onPrimaryAction = {
                            when (machineCardAction(machine, isConnected, credentialState)) {
                                MachineCardAction.OpenWorkspace -> onOpenWorkspaceTerminal(machine.id)
                                MachineCardAction.Configure -> onConfigureMachine(machine.id)
                                MachineCardAction.Connect -> {
                                    // A tap is a new connect from the user, so the connecting screen connects at
                                    // once. An open host key decision stays, and that screen shows it first.
                                    sshStateManager.clearNeedsUser(machine.id)
                                    onConnectMachine(machine.id)
                                }
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

/** The action of the main button of a machine card. See [machineCardAction]. */
internal enum class MachineCardAction {
    /** Open the terminal of the connected machine. */
    OpenWorkspace,

    /** Connect to the machine. */
    Connect,

    /** Open the machine editor. */
    Configure,
}

/**
 * Returns the action of the main button of the card of [machine].
 *
 * - A connected machine opens its terminal.
 * - A machine with a missing key opens the editor, which makes the key.
 * - A machine with a missing password connects. The connect asks for the password and offers to save it. The edit
 *   button of the card still opens the editor.
 * - A machine with an authentication method that this version does not support opens the editor, which tells why
 *   (issue #23).
 */
internal fun machineCardAction(
    machine: HostMachine,
    isConnected: Boolean,
    credentialState: CredentialState,
): MachineCardAction =
    when {
        isConnected -> MachineCardAction.OpenWorkspace
        credentialState is CredentialState.KeyMissing -> MachineCardAction.Configure
        machine.authMethod.isSupported -> MachineCardAction.Connect
        else -> MachineCardAction.Configure
    }

/**
 * Returns the [CredentialState] of each machine in [machines], by machine id. Without a [checker], or when the
 * check fails, a machine is [CredentialState.Ready].
 */
private suspend fun credentialStatesOf(
    machines: List<HostMachine>,
    checker: CredentialChecker?,
): Map<String, CredentialState> {
    if (checker == null) return emptyMap()
    return machines.associate { machine ->
        val state = try {
            checker.check(machine)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CredentialState.Ready
        }
        machine.id to state
    }
}

@Composable
private fun RestoredFromBackupNotice() {
    val palette = LocalBisonPalette.current
    Surface(
        color = palette.accentWarning.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.accentWarning.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = RESTORED_FROM_BACKUP_NOTICE,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.fgPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun HostMachineCard(
    machine: HostMachine,
    isConnected: Boolean,
    backbone: ActiveBackbone?,
    snapshot: ClientShellSnapshot?,
    credentialState: CredentialState,
    needsUserCause: NeedsUserCause?,
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
    val isAuthSupported = machine.authMethod.isSupported
    val needsSignIn = !isConnected && credentialState.needsSignIn

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
                    is ActiveBackbone.MoshUdp -> "MOSH UDP • ${pollTimeText(backbone)}"
                    is ActiveBackbone.SshEndpoint -> "SSH BRIDGE • ${pollTimeText(backbone)}"
                    null -> machine.transportMode.name
                }
                TelemetryBadge(
                    text = transportLabel,
                    color = if (isConnected) palette.accentSuccess else palette.accentInfo,
                )

                if (!isAuthSupported) {
                    TelemetryBadge(
                        text = ConfigureMachineViewModel.UNSUPPORTED_AUTH_LABEL,
                        color = palette.accentError,
                    )
                }
                if (needsSignIn) {
                    TelemetryBadge(
                        text = NEEDS_SIGN_IN_LABEL,
                        color = palette.accentWarning,
                    )
                }
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

            if (needsSignIn && credentialState is CredentialState.KeyMissing) {
                Text(
                    text = credentialState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.accentWarning,
                )
            } else if (!isConnected && needsUserCause != null) {
                // The last connection stopped, and the user must do an action before a new connect can work.
                Text(
                    text = needsUserText(needsUserCause),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.accentWarning,
                    modifier = Modifier.testTag(MACHINE_NEEDS_USER_TAG),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onPrimaryAction,
                    enabled = isConnected || isAuthSupported,
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
                        text = when {
                            isConnected -> " Open Zen Canvas"
                            !needsSignIn -> " Connect Host"
                            credentialState is CredentialState.KeyMissing -> " $MAKE_NEW_KEY_ACTION"
                            else -> " $TYPE_PASSWORD_ACTION"
                        },
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
                    text = stringResource(Res.string.machine_federation_welcome_body),
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
internal fun ThemeAndSettingsBottomSheet(
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
    val effectiveRelease = VersionCheck.effectiveAvailableRelease(settings, AppVersion.CODE)

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
            if (effectiveRelease != null) {
                UpdateAvailableSection(
                    release = effectiveRelease,
                    onOpenRelease = {
                        onUpdateSettings(VersionCheck::acknowledgeRelease)
                    },
                )
            }

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
                label = stringResource(Res.string.settings_predictive_echo_label),
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
            SettingToggleRow(
                label = stringResource(Res.string.settings_app_check_updates),
                summary = stringResource(Res.string.settings_app_check_updates_summary),
                checked = settings.checkForUpdates,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(checkForUpdates = enabled) }
                },
                tag = CHECK_FOR_UPDATES_TAG,
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

/**
 * Compact chip in the top bar when an unseen release is available. One tap marks the version as
 * seen and opens the Settings/About sheet.
 */
@Composable
internal fun UpdateAvailableIndicator(
    version: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .testTag(UPDATE_AVAILABLE_TAG)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = palette.accentPrimary.copy(alpha = 0.18f),
            contentColor = palette.accentPrimary,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, palette.accentPrimary.copy(alpha = 0.55f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.frame_update_available, version),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Non-intrusive "Update available" card in the Settings/About sheet with a button to open the
 * GitHub Release page in the system browser.
 */
@Composable
internal fun UpdateAvailableSection(
    release: RemoteRelease,
    onOpenRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    val uriHandler = LocalUriHandler.current

    Surface(
        color = palette.bgDeep,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.accentPrimary.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag(ABOUT_UPDATE_AVAILABLE_TAG),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = palette.accentPrimary.copy(alpha = 0.2f),
                    contentColor = palette.accentPrimary,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(Res.string.settings_about_new),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = stringResource(Res.string.settings_about_update_available, release.version),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.fgPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(
                    onClick = {
                        onOpenRelease()
                        runCatching { uriHandler.openUri(release.htmlUrl) }
                    },
                    modifier = Modifier.testTag(ABOUT_OPEN_RELEASE_TAG),
                ) {
                    Text(stringResource(Res.string.settings_about_open_release))
                }
            }
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
    summary: String? = null,
    tag: String? = null,
) {
    val palette = LocalBisonPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.fgPrimary,
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.fgMuted,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
