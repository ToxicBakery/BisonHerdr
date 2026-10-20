package com.toxicbakery.app.bison.common.screen.workspace.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toxicbakery.app.bison.common.terminal.ModifierKeyState
import com.toxicbakery.app.bison.common.terminal.ModifierMode
import com.toxicbakery.app.bison.common.terminal.TerminalModifierKey
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.protocol.ClientKeyCode
import com.toxicbakery.app.bison.protocol.ClientShellAgent
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.agent_action_approve
import com.toxicbakery.app.bison.resources.agent_action_reject
import com.toxicbakery.app.bison.resources.common_close
import com.toxicbakery.app.bison.resources.composer_expanded_title
import com.toxicbakery.app.bison.resources.composer_placeholder
import com.toxicbakery.app.bison.resources.composer_queued_offline
import com.toxicbakery.app.bison.resources.composer_send_button
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * Target destination for prompts composed in the expandable Native Prompt Composer card.
 */
sealed interface PromptTarget {
    data object FocusedPane : PromptTarget
    data class SpecificAgent(val paneId: String, val agentName: String?) : PromptTarget
    data object BroadcastAllPanes : PromptTarget
}

/**
 * Ergonomic Split-Thumb Floating Bottom Controls & Center Native Prompt Composer Pill.
 *
 * - **Left Thumb Cluster**: `[Esc]`, `[Tab]`, sticky/chorded `[Ctrl]` and `[Alt]`
 *   (single-tap arms sticky one-shot, double-tap locks, tap again releases), `[^C]`, `[^B]`, `[/]`, `[|]`.
 * - **Center Floating Prompt Composer Pill**:
 *   - Displays inline `[ ✓ Approve ]` and `[ ✕ Reject ]` buttons whenever the focused pane's agent is `Blocked`.
 *   - Expands on tap into a multi-line native Compose `OutlinedTextField` supporting Android voice
 *     dictation, swipe typing, target selector (Focused Pane, Specific Agent, or Broadcast), and
 *     offline Mosh roaming queue badge.
 * - **Right Thumb Cluster**: Swipeable directional arrow cluster (`[←]`, `[↓]`, `[↑]`, `[→]`) + `[⏎ Enter]`.
 */
@Composable
fun SplitThumbBottomBar(
    modifierKeyState: ModifierKeyState,
    leftThumbKeys: List<String>,
    focusedPaneAgent: ClientShellAgent?,
    availableAgents: List<ClientShellAgent>,
    queuedPromptCount: Int,
    hudOpacity: Float = 1f,
    onSingleTapModifier: (TerminalModifierKey) -> Unit,
    onDoubleTapModifier: (TerminalModifierKey) -> Unit,
    onQuickKeyPress: (keyLabel: String) -> Unit,
    onArrowKeyPress: (ClientKeyCode) -> Unit,
    onEnterPress: () -> Unit,
    onApproveFocusedAgent: () -> Unit,
    onRejectFocusedAgent: () -> Unit,
    onSubmitPrompt: (prompt: String, target: PromptTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    var composerExpanded by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf("") }
    var selectedTarget by remember { mutableStateOf<PromptTarget>(PromptTarget.FocusedPane) }

    val isFocusedAgentBlocked = focusedPaneAgent?.agentStatus == com.toxicbakery.app.bison.protocol.AgentStatus.Blocked

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .alpha(hudOpacity),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Expanded Native Compose Prompt Composer Card
        if (composerExpanded) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 680.dp)
                    .fillMaxWidth(0.92f)
                    .border(1.5.dp, palette.accent, RoundedCornerShape(18.dp))
                    .testTag("expandedPromptComposerCard"),
                shape = RoundedCornerShape(18.dp),
                color = palette.panelBg.copy(alpha = 0.96f),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.composer_expanded_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.text,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TargetChip(
                                label = "Focused Pane",
                                selected = selectedTarget is PromptTarget.FocusedPane,
                                onClick = { selectedTarget = PromptTarget.FocusedPane },
                            )
                            availableAgents.firstOrNull()?.let { agent ->
                                TargetChip(
                                    label = agent.displayAgent.ifBlank { agent.name ?: "Agent" },
                                    selected = selectedTarget is PromptTarget.SpecificAgent,
                                    onClick = {
                                        selectedTarget = PromptTarget.SpecificAgent(
                                            paneId = agent.paneId,
                                            agentName = agent.name,
                                        )
                                    },
                                )
                            }
                            TargetChip(
                                label = "Broadcast",
                                selected = selectedTarget is PromptTarget.BroadcastAllPanes,
                                onClick = { selectedTarget = PromptTarget.BroadcastAllPanes },
                            )
                            TextButton(onClick = { composerExpanded = false }) {
                                Text("✕ " + stringResource(Res.string.common_close), color = palette.text)
                            }
                        }
                    }

                    if (queuedPromptCount > 0) {
                        Text(
                            text = "⏳ " + stringResource(Res.string.composer_queued_offline, queuedPromptCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                        )
                    }

                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        placeholder = { Text(stringResource(Res.string.composer_placeholder)) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("promptComposerTextField"),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isFocusedAgentBlocked) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onApproveFocusedAgent,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = palette.green,
                                        contentColor = palette.terminalBg,
                                    ),
                                ) {
                                    Text("✓ " + stringResource(Res.string.agent_action_approve))
                                }
                                OutlinedButton(onClick = onRejectFocusedAgent) {
                                    Text("✕ " + stringResource(Res.string.agent_action_reject), color = palette.red)
                                }
                            }
                        } else {
                            Spacer(Modifier.width(4.dp))
                        }

                        Button(
                            onClick = {
                                if (promptDraft.isNotBlank()) {
                                    onSubmitPrompt(promptDraft.trim(), selectedTarget)
                                    promptDraft = ""
                                    composerExpanded = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.accent,
                                contentColor = palette.terminalBg,
                            ),
                            modifier = Modifier.testTag("promptComposerSendButton"),
                        ) {
                            Text(
                                text = stringResource(Res.string.composer_send_button) + " ⏎",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // Bottom Split-Thumb Row: Left Cluster | Center Prompt Pill | Right Cluster
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Split-Thumb Cluster
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.panelBg.copy(alpha = 0.90f),
                tonalElevation = 4.dp,
                modifier = Modifier.testTag("leftThumbCluster"),
            ) {
                Row(
                    modifier = Modifier
                        .border(1.dp, palette.text.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leftThumbKeys.forEach { keyLabel ->
                        when (keyLabel.lowercase()) {
                            "ctrl" -> ModifierThumbButton(
                                label = "Ctrl",
                                mode = modifierKeyState.ctrl,
                                onSingleTap = { onSingleTapModifier(TerminalModifierKey.Ctrl) },
                                onDoubleTap = { onDoubleTapModifier(TerminalModifierKey.Ctrl) },
                            )
                            "alt" -> ModifierThumbButton(
                                label = "Alt",
                                mode = modifierKeyState.alt,
                                onSingleTap = { onSingleTapModifier(TerminalModifierKey.Alt) },
                                onDoubleTap = { onDoubleTapModifier(TerminalModifierKey.Alt) },
                            )
                            "shift" -> ModifierThumbButton(
                                label = "Shift",
                                mode = modifierKeyState.shift,
                                onSingleTap = { onSingleTapModifier(TerminalModifierKey.Shift) },
                                onDoubleTap = { onDoubleTapModifier(TerminalModifierKey.Shift) },
                            )
                            else -> QuickKeyButton(
                                label = keyLabel,
                                onClick = { onQuickKeyPress(keyLabel) },
                            )
                        }
                    }
                }
            }

            // Center Floating Prompt Composer Pill
            Surface(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { composerExpanded = !composerExpanded }
                    .testTag("centerPromptComposerPill"),
                shape = RoundedCornerShape(22.dp),
                color = palette.panelBg.copy(alpha = 0.90f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .border(
                            width = if (isFocusedAgentBlocked) 1.5.dp else 1.dp,
                            color = if (isFocusedAgentBlocked) palette.red else palette.accent.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(22.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (promptDraft.isNotBlank()) "❯ $promptDraft" else "❯ " + stringResource(Res.string.composer_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (promptDraft.isNotBlank()) palette.text else palette.text.copy(alpha = 0.65f),
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )

                    if (isFocusedAgentBlocked) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.green)
                                .clickable { onApproveFocusedAgent() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("pillQuickApproveButton"),
                        ) {
                            Text(
                                text = "✓ " + stringResource(Res.string.agent_action_approve),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.terminalBg,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.red.copy(alpha = 0.22f))
                                .clickable { onRejectFocusedAgent() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("pillQuickRejectButton"),
                        ) {
                            Text(
                                text = "✕ " + stringResource(Res.string.agent_action_reject),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.red,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Right Split-Thumb Cluster (Swipeable Arrow Trackpad + Enter)
            RightArrowThumbCluster(
                onArrowKeyPress = onArrowKeyPress,
                onEnterPress = onEnterPress,
            )
        }
    }
}

@Composable
private fun ModifierThumbButton(
    label: String,
    mode: ModifierMode,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    val bgColor = when (mode) {
        ModifierMode.Off -> palette.terminalBg.copy(alpha = 0.85f)
        ModifierMode.StickyOneShot -> palette.accent.copy(alpha = 0.28f)
        ModifierMode.Locked -> palette.accent
    }
    val textColor = when (mode) {
        ModifierMode.Off -> palette.text
        ModifierMode.StickyOneShot -> palette.accent
        ModifierMode.Locked -> palette.terminalBg
    }
    val displayText = when (mode) {
        ModifierMode.Off -> label
        ModifierMode.StickyOneShot -> "$label•"
        ModifierMode.Locked -> "$label🔒"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = if (mode.isActive) palette.accent else palette.text.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp),
            )
            .pointerInput(label) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { onDoubleTap() },
                )
            }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("thumbMod_$label"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun QuickKeyButton(
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.terminalBg.copy(alpha = 0.85f))
            .border(1.dp, palette.text.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("thumbKey_$label"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = palette.text,
        )
    }
}

@Composable
private fun RightArrowThumbCluster(
    onArrowKeyPress: (ClientKeyCode) -> Unit,
    onEnterPress: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    var dragAccumX by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.panelBg.copy(alpha = 0.90f),
        tonalElevation = 4.dp,
        modifier = Modifier
            .pointerInput(Unit) {
                val stepPx = 22.dp.toPx()
                detectDragGestures(
                    onDragStart = {
                        dragAccumX = 0f
                        dragAccumY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumX += dragAmount.x
                        dragAccumY += dragAmount.y
                        while (abs(dragAccumX) >= stepPx) {
                            if (dragAccumX > 0) {
                                onArrowKeyPress(ClientKeyCode.Right)
                                dragAccumX -= stepPx
                            } else {
                                onArrowKeyPress(ClientKeyCode.Left)
                                dragAccumX += stepPx
                            }
                        }
                        while (abs(dragAccumY) >= stepPx) {
                            if (dragAccumY > 0) {
                                onArrowKeyPress(ClientKeyCode.Down)
                                dragAccumY -= stepPx
                            } else {
                                onArrowKeyPress(ClientKeyCode.Up)
                                dragAccumY += stepPx
                            }
                        }
                    },
                )
            }
            .testTag("rightThumbCluster"),
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, palette.text.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickKeyButton(label = "←", onClick = { onArrowKeyPress(ClientKeyCode.Left) })
            QuickKeyButton(label = "↓", onClick = { onArrowKeyPress(ClientKeyCode.Down) })
            QuickKeyButton(label = "↑", onClick = { onArrowKeyPress(ClientKeyCode.Up) })
            QuickKeyButton(label = "→", onClick = { onArrowKeyPress(ClientKeyCode.Right) })
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.accent)
                    .clickable(onClick = onEnterPress)
                    .padding(horizontal = 11.dp, vertical = 7.dp)
                    .testTag("thumbKey_Enter"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⏎",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.terminalBg,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun TargetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accent.copy(alpha = 0.22f) else palette.terminalBg)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.text.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) palette.accent else palette.text,
            fontSize = 11.sp,
        )
    }
}
