package com.toxicbakery.app.bison.common.screen.workspace.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toxicbakery.app.bison.common.screen.delegate.FederatedAgentItem
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.agent_action_approve
import com.toxicbakery.app.bison.resources.agent_action_jump_to_pane
import com.toxicbakery.app.bison.resources.agent_action_reject
import com.toxicbakery.app.bison.resources.agent_drawer_empty
import com.toxicbakery.app.bison.resources.agent_drawer_title
import com.toxicbakery.app.bison.resources.common_close
import org.jetbrains.compose.resources.stringResource

/**
 * Slide-Over Floating Agent Command Center Drawer on the right edge of the Zen Canvas.
 *
 * Aggregates [FederatedAgentItem] entries across all connected SSH/Mosh machines sorted by
 * priority (`Blocked` -> `Done` -> `Working` -> `Idle`), shows live bottom-buffer snippets
 * (`agent.read` with `source = "visible"`), and provides one-tap `[ ✓ Approve ]`, `[ ✕ Reject ]`,
 * and `[ Jump to Pane ]` actions.
 */
@Composable
fun AgentCommandCenterDrawer(
    agents: List<FederatedAgentItem>,
    onApproveAgent: (FederatedAgentItem) -> Unit,
    onRejectAgent: (FederatedAgentItem) -> Unit,
    onJumpToAgentPane: (FederatedAgentItem) -> Unit,
    onRefreshAgentSnippet: (FederatedAgentItem) -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current

    val activeAgentKeys = remember(agents) {
        agents
            .filter { it.agent.agentStatus == AgentStatus.Blocked || it.agent.agentStatus == AgentStatus.Working }
            .map { Triple(it.machineId, it.agent.paneId, it.agent.agentStatus) }
    }

    LaunchedEffect(activeAgentKeys) {
        agents.forEach { item ->
            if (item.agent.agentStatus == AgentStatus.Blocked || item.agent.agentStatus == AgentStatus.Working) {
                onRefreshAgentSnippet(item)
            }
        }
    }

    Surface(
        modifier = modifier
            .width(380.dp)
            .fillMaxHeight()
            .padding(top = 56.dp, bottom = 72.dp, end = 12.dp)
            .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .testTag("agentCommandCenterDrawer"),
        shape = RoundedCornerShape(20.dp),
        color = palette.panelBg.copy(alpha = 0.96f),
        tonalElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.agent_drawer_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onCloseDrawer) {
                    Text("✕ " + stringResource(Res.string.common_close), color = palette.text)
                }
            }

            HorizontalDivider(color = palette.text.copy(alpha = 0.15f))
            Spacer(Modifier.height(10.dp))

            if (agents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.agent_drawer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.text.copy(alpha = 0.65f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = agents,
                        key = { "${it.machineId}:${it.agent.paneId}" },
                    ) { item ->
                        AgentCommandCard(
                            item = item,
                            onApprove = { onApproveAgent(item) },
                            onReject = { onRejectAgent(item) },
                            onJump = { onJumpToAgentPane(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentCommandCard(
    item: FederatedAgentItem,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onJump: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    val status = item.agent.agentStatus
    val statusColor = when (status) {
        AgentStatus.Blocked -> palette.red
        AgentStatus.Done -> palette.green
        AgentStatus.Working -> palette.accent
        else -> palette.text.copy(alpha = 0.6f)
    }
    val statusIcon = when (status) {
        AgentStatus.Blocked -> "🔴"
        AgentStatus.Done -> "🟢"
        AgentStatus.Working -> "🟠"
        else -> "⚪"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (status == AgentStatus.Blocked) 1.5.dp else 1.dp,
                color = statusColor.copy(alpha = if (status == AgentStatus.Blocked) 0.85f else 0.35f),
                shape = RoundedCornerShape(14.dp),
            ),
        shape = RoundedCornerShape(14.dp),
        color = palette.terminalBg.copy(alpha = 0.88f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$statusIcon ${item.displayTitle} · ${item.machineName} (${item.agent.paneId})",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.text,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = status.wireName.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }

            val snippetText = item.snippet?.takeIf { it.isNotBlank() }
                ?: item.agent.title
                ?: item.agent.terminalTitleStripped
            if (!snippetText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.panelBg.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = snippetText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = palette.text.copy(alpha = 0.90f),
                        maxLines = 4,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (status == AgentStatus.Blocked) {
                    Button(
                        onClick = onApprove,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("agentApprove_${item.machineId}_${item.agent.paneId}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.green,
                            contentColor = palette.terminalBg,
                        ),
                    ) {
                        Text("✓ " + stringResource(Res.string.agent_action_approve), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("agentReject_${item.machineId}_${item.agent.paneId}"),
                    ) {
                        Text("✕ " + stringResource(Res.string.agent_action_reject), color = palette.red)
                    }
                }
                TextButton(
                    onClick = onJump,
                    modifier = Modifier.testTag("agentJump_${item.machineId}_${item.agent.paneId}"),
                ) {
                    Text(stringResource(Res.string.agent_action_jump_to_pane) + " ›", color = palette.accent)
                }
            }
        }
    }
}
