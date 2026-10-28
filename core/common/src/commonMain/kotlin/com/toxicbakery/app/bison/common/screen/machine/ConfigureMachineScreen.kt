package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toxicbakery.app.bison.common.navigation.NavigationController
import com.toxicbakery.app.bison.common.platform.plainTextClipEntry
import com.toxicbakery.app.bison.common.screen.machine.ConfigureMachineViewModel.StoredPassword
import com.toxicbakery.app.bison.common.navigation.ScreenKeyConnectingMachine
import com.toxicbakery.app.bison.common.theme.BisonThemes
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.HardwareKeyTransport
import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.machine_auth_hardware_key
import com.toxicbakery.app.bison.resources.machine_auth_keystore
import com.toxicbakery.app.bison.resources.machine_auth_password
import com.toxicbakery.app.bison.resources.machine_bastion_credentials_hint
import com.toxicbakery.app.bison.resources.machine_bastion_host_name_hint
import com.toxicbakery.app.bison.resources.machine_field_mosh_ports_label
import com.toxicbakery.app.bison.resources.machine_field_transport_mode_label
import com.toxicbakery.app.bison.resources.machine_key_error_algorithm_not_supported
import com.toxicbakery.app.bison.resources.machine_key_error_no_secure_lock_screen
import com.toxicbakery.app.bison.resources.machine_key_error_other
import com.toxicbakery.app.bison.resources.machine_transport_auto_prefer_mosh
import com.toxicbakery.app.bison.resources.machine_transport_mosh_only
import com.toxicbakery.app.bison.resources.machine_transport_mosh_planned_hint
import com.toxicbakery.app.bison.resources.machine_transport_ssh_only
import com.toxicbakery.app.bison.ssh.KeyCreationFailure
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.viewmodel.rememberViewModel

@Composable
fun ConfigureMachineScreen(
    navigationController: NavigationController,
    machineId: String,
) {
    ConfigureMachineScreen(
        machineId = machineId,
        onNavigateBack = { navigationController.goBack() },
        onSavedAndConnect = { savedId ->
            navigationController.replaceWith(ScreenKeyConnectingMachine(savedId))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfigureMachineScreen(
    machineId: String,
    onNavigateBack: () -> Unit,
    onSavedAndConnect: (String) -> Unit,
) {
    val viewModel: ConfigureMachineViewModel by rememberViewModel(arg = machineId)
    val state by viewModel.state.collectAsState()
    val palette = LocalBisonPalette.current

    LaunchedEffect(viewModel) {
        viewModel.actions.collect { action ->
            when (action) {
                is ConfigureMachineViewModel.Action.Saved -> {
                    if (action.connectImmediately) {
                        onSavedAndConnect(action.machineId)
                    } else {
                        onNavigateBack()
                    }
                }
                ConfigureMachineViewModel.Action.Deleted -> {
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        containerColor = palette.bgDeep,
        contentColor = palette.fgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isEditingExisting) "Edit Herdr Host" else "Add Herdr Host",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (state.isEditingExisting) {
                        IconButton(
                            onClick = { viewModel.onEvent(ConfigureMachineViewModel.Event.DeleteMachine) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Host",
                                tint = palette.accentError,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.bgSurface,
                    titleContentColor = palette.fgPrimary,
                    navigationIconContentColor = palette.fgPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.validationError != null) {
                    Surface(
                        color = palette.accentError.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, palette.accentError),
                    ) {
                        Text(
                            text = state.validationError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.accentError,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // 1. Host & Connection Identity
                SectionCard(title = "Host Identity & SSH Address") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = { viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateName(it)) },
                            label = { Text("Display Name (optional)") },
                            placeholder = { Text("e.g. Studio Workstation") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateUsername(it)) },
                            label = { Text("SSH Username *") },
                            placeholder = { Text("ubuntu") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = state.hostname,
                            onValueChange = { viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHostname(it)) },
                            label = { Text("Hostname / Tailscale IP *") },
                            placeholder = { Text("devbox.tailnet.ts.net") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = state.portText,
                            onValueChange = { viewModel.onEvent(ConfigureMachineViewModel.Event.UpdatePort(it)) },
                            label = { Text("SSH Port *") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 2. Transport selection. This version has no Mosh transport (issue #22).
                SectionCard(title = stringResource(Res.string.machine_field_transport_mode_label)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TransportMode.entries.forEach { mode ->
                            val label = when (mode) {
                                TransportMode.AutoPreferMosh -> Res.string.machine_transport_auto_prefer_mosh
                                TransportMode.MoshOnly -> Res.string.machine_transport_mosh_only
                                TransportMode.SshEndpointOnly -> Res.string.machine_transport_ssh_only
                            }
                            FilterChip(
                                selected = state.transportMode == mode,
                                onClick = {
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateTransportMode(mode))
                                },
                                // Mosh is not available in this version (issue #22), so the user cannot select it.
                                enabled = mode != TransportMode.MoshOnly,
                                label = { Text(stringResource(label)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.accentPrimary.copy(alpha = 0.22f),
                                    selectedLabelColor = palette.accentPrimary,
                                ),
                            )
                        }
                    }
                    Text(
                        text = stringResource(Res.string.machine_transport_mosh_planned_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = state.moshUdpPortRange,
                            onValueChange = {
                                viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateMoshUdpPortRange(it))
                            },
                            label = { Text(stringResource(Res.string.machine_field_mosh_ports_label)) },
                            placeholder = { Text("60000:61000") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.herdrSessionName,
                            onValueChange = {
                                viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateHerdrSessionName(it))
                            },
                            label = { Text("Herdr Session Name (optional)") },
                            placeholder = { Text("default") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // 3. SSH Authentication Strategy
                SectionCard(title = "SSH Authentication Strategy") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.authChoices.forEach { type ->
                            val label = when (type) {
                                ConfigureMachineViewModel.AuthType.KeystoreKey -> stringResource(Res.string.machine_auth_keystore)
                                ConfigureMachineViewModel.AuthType.Password -> stringResource(Res.string.machine_auth_password)
                                // Only a build with security keys offers this choice (issue #23).
                                ConfigureMachineViewModel.AuthType.HardwareSecurityKey ->
                                    stringResource(Res.string.machine_auth_hardware_key)
                            }
                            FilterChip(
                                selected = state.authType == type,
                                onClick = {
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateAuthType(type))
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.accentSecondary.copy(alpha = 0.22f),
                                    selectedLabelColor = palette.accentSecondary,
                                ),
                            )
                        }
                    }

                    when (state.authType) {
                        ConfigureMachineViewModel.AuthType.KeystoreKey -> {
                            OutlinedTextField(
                                value = state.keystoreAlias,
                                onValueChange = {
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeystoreAlias(it))
                                },
                                label = { Text("Keystore Key Alias") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                state.availableKeyAlgorithms.forEach { alg ->
                                    FilterChip(
                                        selected = state.keyAlgorithm == alg,
                                        onClick = {
                                            viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateKeyAlgorithm(alg))
                                        },
                                        label = { Text(alg.sshKeyType, fontFamily = FontFamily.Monospace) },
                                    )
                                }
                            }
                            state.keyCreationFailure?.let { failure ->
                                Text(
                                    text = keyCreationFailureText(failure),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("keyCreationError"),
                                )
                            }
                            if (KeyAlgorithm.Ed25519 !in state.availableKeyAlgorithms) {
                                Text(
                                    text = "Ed25519 keys need Android 13 or later.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Require Biometric / Device Credential before signing",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Switch(
                                    checked = state.requireBiometric,
                                    onCheckedChange = {
                                        viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateRequireBiometric(it))
                                    },
                                )
                            }
                            if (state.isEditingExisting && state.canShowPublicKey) {
                                RegenerateKeySection(
                                    enabled = !state.isLoadingPublicKey,
                                    onRegenerate = {
                                        viewModel.onEvent(ConfigureMachineViewModel.Event.RegenerateKey)
                                    },
                                )
                            }
                            if (state.showRegenerateKeyWarning) {
                                RegenerateKeyWarningDialog(
                                    keyAlias = state.keystoreAlias.trim().ifBlank { state.keyAlgorithm.defaultKeystoreAlias },
                                    onConfirm = {
                                        viewModel.onEvent(ConfigureMachineViewModel.Event.ConfirmRegenerateKey)
                                    },
                                    onDismiss = {
                                        viewModel.onEvent(ConfigureMachineViewModel.Event.DismissRegenerateKey)
                                    },
                                )
                            }
                            if (state.canShowPublicKey) {
                                PublicKeySection(
                                    state = state,
                                    onShowPublicKey = {
                                        viewModel.onEvent(ConfigureMachineViewModel.Event.ShowPublicKey)
                                    },
                                )
                            }
                        }

                        // Without the flag, only a loaded machine can have this method. See issue #23.
                        ConfigureMachineViewModel.AuthType.HardwareSecurityKey -> if (state.hardwareKeysEnabled) {
                            HardwareKeySection(state = state, onEvent = viewModel::onEvent)
                        } else {
                            Text(
                                text = "Hardware security key: ${ConfigureMachineViewModel.UNSUPPORTED_AUTH_LABEL}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.accentError,
                            )
                            Text(
                                text = "Select a different authentication method to connect to this host.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        ConfigureMachineViewModel.AuthType.Password -> {
                            // The field never shows the stored password. An empty field keeps it. The field state
                            // uses remember and not rememberSaveable, so the saved instance state has no password.
                            val passwordField = remember { TextFieldState(state.passwordRef) }
                            LaunchedEffect(passwordField) {
                                snapshotFlow { passwordField.text.toString() }.collect { password ->
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdatePasswordRef(password))
                                }
                            }
                            OutlinedSecureTextField(
                                state = passwordField,
                                label = { Text("Password") },
                                placeholder = if (state.storedPassword == StoredPassword.Stored) {
                                    { Text("Stored password") }
                                } else {
                                    null
                                },
                                supportingText = when (state.storedPassword) {
                                    StoredPassword.None -> null
                                    StoredPassword.Stored -> {
                                        { Text(ConfigureMachineViewModel.STORED_PASSWORD_HINT) }
                                    }
                                    StoredPassword.Missing -> {
                                        {
                                            Text(
                                                text = ConfigureMachineViewModel.MISSING_PASSWORD_HINT,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("passwordField"),
                            )
                        }
                    }
                }

                // 4. ProxyJump / Bastion
                SectionCard(title = "ProxyJump Bastion") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Enable Multi-Hop SSH Bastion (ProxyJump / direct-tcpip)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = state.proxyJumpEnabled,
                            onCheckedChange = {
                                viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateProxyJumpEnabled(it))
                            },
                        )
                    }
                    if (state.proxyJumpEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedTextField(
                                value = state.bastionHost,
                                onValueChange = {
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateBastionHost(it))
                                },
                                label = { Text("Bastion Host") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = state.bastionPortText,
                                onValueChange = {
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateBastionPort(it))
                                },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = state.bastionUser,
                                onValueChange = {
                                    viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateBastionUser(it))
                                },
                                label = { Text("Bastion User") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = stringResource(Res.string.machine_bastion_credentials_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(Res.string.machine_bastion_host_name_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 5. Per-Host Theme Override (19 Color Themes)
                SectionCard(title = "Per-Host Color Theme Override") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.themeOverrideId == null,
                            onClick = {
                                viewModel.onEvent(ConfigureMachineViewModel.Event.UpdateThemeOverride(null))
                            },
                            label = { Text("Use Global Default") },
                        )
                        BisonThemes.all.forEach { themePalette ->
                            val isSelected = state.themeOverrideId == themePalette.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = themePalette.bgSurface,
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) themePalette.accentPrimary else themePalette.borderSubtle,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.onEvent(
                                            ConfigureMachineViewModel.Event.UpdateThemeOverride(themePalette.id)
                                        )
                                    },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(themePalette.accentPrimary),
                                    )
                                    Text(
                                        text = themePalette.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = themePalette.fgPrimary,
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. Saved host keys of this machine
                if (state.isEditingExisting) {
                    KnownHostsSection(machineId = state.id)
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onNavigateBack) {
                        Text("Cancel")
                    }
                    OutlinedButton(
                        onClick = { viewModel.onEvent(ConfigureMachineViewModel.Event.SaveMachine) },
                        enabled = state.canSave,
                    ) {
                        Text("Save Host")
                    }
                    Button(
                        onClick = { viewModel.onEvent(ConfigureMachineViewModel.Event.SaveAndConnect) },
                        enabled = state.canConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accentPrimary,
                            contentColor = palette.bgDeep,
                        ),
                    ) {
                        Text("Save & Connect", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** Returns the text that tells the user why the device key store did not make the key. */
@Composable
private fun keyCreationFailureText(failure: KeyCreationFailure): String =
    when (failure) {
        KeyCreationFailure.NoSecureLockScreen -> stringResource(Res.string.machine_key_error_no_secure_lock_screen)
        is KeyCreationFailure.AlgorithmNotSupported ->
            stringResource(Res.string.machine_key_error_algorithm_not_supported, failure.algorithm.sshKeyType)
        is KeyCreationFailure.Other -> stringResource(Res.string.machine_key_error_other, failure.detail)
    }

/**
 * Shows the OpenSSH public key of the key store key, with a button that copies it, so that the
 * user can add it to `~/.ssh/authorized_keys` on the host.
 */
@Composable
private fun PublicKeySection(
    state: ConfigureMachineViewModel.State,
    onShowPublicKey: () -> Unit,
) {
    val publicKeyLine = state.publicKeyLine

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (publicKeyLine == null) {
            OutlinedButton(
                onClick = onShowPublicKey,
                enabled = !state.isLoadingPublicKey,
                modifier = Modifier.testTag("showPublicKey"),
            ) {
                Text(if (state.isLoadingPublicKey) "Loading public key" else "Show public key")
            }
        } else {
            AuthorizedKeyLine(publicKeyLine = publicKeyLine, lineTag = "publicKeyLine", copyTag = "copyPublicKey")
        }
        state.publicKeyError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Shows the `authorized_keys` line [publicKeyLine] with a button that copies it. [lineTag] and [copyTag] are the test
 * tags of the line and of the button.
 */
@Composable
private fun AuthorizedKeyLine(
    publicKeyLine: String,
    lineTag: String,
    copyTag: String,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(publicKeyLine) { mutableStateOf(false) }

    Text(
        text = "Add this line to ~/.ssh/authorized_keys on the host:",
        style = MaterialTheme.typography.bodySmall,
    )
    SelectionContainer {
        Text(
            text = publicKeyLine,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().testTag(lineTag),
        )
    }
    Button(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(plainTextClipEntry(label = "SSH public key", text = publicKeyLine))
                copied = true
            }
        },
        modifier = Modifier.testTag(copyTag),
    ) {
        Text(if (copied) "Copied" else "Copy public key")
    }
}

/**
 * The FIDO2 security key settings (issue #23): the key type, the connections, and the "Enroll key" action. After an
 * enrollment, the section shows the `authorized_keys` line of the key with a copy button.
 */
@Composable
private fun HardwareKeySection(
    state: ConfigureMachineViewModel.State,
    onEvent: (ConfigureMachineViewModel.Event) -> Unit,
) {
    val publicKeyLine = state.hardwareKeyPublicKeyLine

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            HardwareKeyType.entries.filter { it.isFido2 }.forEach { keyType ->
                FilterChip(
                    selected = state.hardwareKeyType == keyType,
                    enabled = !state.isEnrollingKey,
                    onClick = { onEvent(ConfigureMachineViewModel.Event.UpdateHardwareKeyType(keyType)) },
                    label = { Text(keyType.opensshKeyType, fontFamily = FontFamily.Monospace) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKeyTransport.entries.forEach { transport ->
                FilterChip(
                    selected = state.hardwareKeyTransport == transport,
                    onClick = { onEvent(ConfigureMachineViewModel.Event.UpdateHardwareKeyTransport(transport)) },
                    label = { Text(hardwareKeyTransportLabel(transport)) },
                )
            }
        }
        if (!state.hardwareKeyType.isFido2) {
            Text(
                text = "This version supports only FIDO2 security keys. Select a key type and enroll the key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "The host needs OpenSSH 8.2 or later. This version cannot use a security key that needs a PIN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { onEvent(ConfigureMachineViewModel.Event.EnrollHardwareKey) },
            enabled = !state.isEnrollingKey && state.hardwareKeyType.isFido2,
            modifier = Modifier.testTag("enrollHardwareKey"),
        ) {
            // The touch sheet shows while the key enrolls, and this button is not available.
            Text(if (publicKeyLine != null && !state.isEnrollingKey) "Enroll a new key" else "Enroll key")
        }
        if (publicKeyLine != null) {
            AuthorizedKeyLine(
                publicKeyLine = publicKeyLine,
                lineTag = "hardwareKeyPublicKeyLine",
                copyTag = "copyHardwareKeyPublicKey",
            )
            Text(
                text = "Save Host keeps the enrolled key. A new enrollment replaces this key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.enrollError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("enrollHardwareKeyError"),
            )
        }
    }
}

/** Returns the chip text of [transport]. */
private fun hardwareKeyTransportLabel(transport: HardwareKeyTransport): String =
    when (transport) {
        HardwareKeyTransport.UsbAndNfc -> "USB-C and NFC"
        HardwareKeyTransport.UsbOnly -> "USB-C"
        HardwareKeyTransport.NfcOnly -> "NFC"
    }

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val palette = LocalBisonPalette.current
    Surface(
        color = palette.bgSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.borderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.accentPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}
