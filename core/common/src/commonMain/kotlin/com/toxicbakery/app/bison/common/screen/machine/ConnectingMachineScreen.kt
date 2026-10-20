package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toxicbakery.app.bison.common.navigation.NavigationController
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.ConnectionProgress
import org.kodein.di.compose.viewmodel.rememberViewModel

@Composable
fun ConnectingMachineScreen(
    navigationController: NavigationController,
    machineId: String,
) {
    ConnectingMachineScreen(
        machineId = machineId,
        onConnected = { connectedId ->
            navigationController.navigateToWorkspaceTerminal(connectedId)
        },
        onCancel = {
            navigationController.goBack()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectingMachineScreen(
    machineId: String,
    onConnected: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val viewModel: ConnectingMachineViewModel by rememberViewModel(arg = machineId)
    val machine by viewModel.machine.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val activeBackbone by viewModel.activeBackbone.collectAsState()
    val palette = LocalBisonPalette.current

    LaunchedEffect(machineId) {
        viewModel.onEvent(ConnectingMachineViewModel.Event.StartConnectIfNeeded)
    }

    LaunchedEffect(progress) {
        if (progress == ConnectionProgress.Connected) {
            onConnected(machineId)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.actions.collect { action ->
            when (action) {
                is ConnectingMachineViewModel.Action.Connected -> onConnected(action.machineId)
                ConnectingMachineViewModel.Action.Cancelled -> onCancel()
            }
        }
    }

    Scaffold(
        containerColor = palette.bgDeep,
        contentColor = palette.fgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Connecting to ${machine?.name ?: machineId}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (machine != null) {
                            Text(
                                text = machine?.displayAddress.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.fgMuted,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.onEvent(ConnectingMachineViewModel.Event.CancelConnect) },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel",
                        )
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
                    .widthIn(max = 820.dp)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 4-Step Bootstrap Stepper Card
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Dual-Transport Bootstrap Progress",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.accentPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val backboneBadge = when (val bb = activeBackbone) {
                                is ActiveBackbone.MoshUdp -> "MOSH UDP :${bb.udpPort} (${bb.rttMs}ms)"
                                is ActiveBackbone.SshEndpoint -> "SSH ENDPOINT (${bb.rttMs}ms)"
                                null -> progress.name
                            }
                            Text(
                                text = backboneBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accentInfo,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        steps.forEach { step ->
                            BootstrapStepRow(step = step)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.onEvent(ConnectingMachineViewModel.Event.CancelConnect) },
                            ) {
                                Text("Cancel")
                            }
                            if (progress == ConnectionProgress.Failed) {
                                Button(
                                    onClick = { viewModel.onEvent(ConnectingMachineViewModel.Event.RetryConnect) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accentWarning,
                                        contentColor = palette.bgDeep,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = " Retry Bootstrap",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            } else if (progress == ConnectionProgress.Connected) {
                                Button(
                                    onClick = { onConnected(machineId) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.accentSuccess,
                                        contentColor = palette.bgDeep,
                                    ),
                                ) {
                                    Text("Open Zen Canvas", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Live SSH / Mosh Transport Diagnostic Log
                Surface(
                    color = palette.bgSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Transport Diagnostic Log",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.fgPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = palette.bgDeep,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, palette.borderSubtle),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "Waiting for SSH2 / Mosh UDP SSP diagnostic events...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.fgMuted,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(logs) { entry ->
                                        Text(
                                            text = "[${entry.tag}] ${entry.message}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (entry.isError) palette.accentError else palette.fgPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BootstrapStepRow(
    step: ConnectingMachineViewModel.BootstrapStep,
) {
    val palette = LocalBisonPalette.current
    val indicatorColor = when (step.status) {
        ConnectingMachineViewModel.StepStatus.Completed -> palette.accentSuccess
        ConnectingMachineViewModel.StepStatus.Active -> palette.accentPrimary
        ConnectingMachineViewModel.StepStatus.Failed -> palette.accentError
        ConnectingMachineViewModel.StepStatus.Pending -> palette.borderSubtle
    }

    Surface(
        color = palette.bgElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, indicatorColor.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(indicatorColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                when (step.status) {
                    ConnectingMachineViewModel.StepStatus.Completed -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = palette.accentSuccess,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    ConnectingMachineViewModel.StepStatus.Active -> {
                        CircularProgressIndicator(
                            color = palette.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    ConnectingMachineViewModel.StepStatus.Failed -> {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Failed",
                            tint = palette.accentError,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    ConnectingMachineViewModel.StepStatus.Pending -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette.fgMuted),
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.fgPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = step.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.fgMuted,
                )
            }
        }
    }
}
