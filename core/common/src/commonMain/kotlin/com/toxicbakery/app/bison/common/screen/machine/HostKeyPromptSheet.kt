package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_cancel
import com.toxicbakery.app.bison.resources.host_key_accept
import com.toxicbakery.app.bison.resources.host_key_changed_body
import com.toxicbakery.app.bison.resources.host_key_changed_remove
import com.toxicbakery.app.bison.resources.host_key_changed_remove_hint
import com.toxicbakery.app.bison.resources.host_key_changed_saved_label
import com.toxicbakery.app.bison.resources.host_key_changed_sent_label
import com.toxicbakery.app.bison.resources.host_key_changed_title
import com.toxicbakery.app.bison.resources.host_key_fingerprint_hint
import com.toxicbakery.app.bison.resources.host_key_reject
import com.toxicbakery.app.bison.resources.host_key_type_label
import com.toxicbakery.app.bison.resources.host_key_unknown_body
import com.toxicbakery.app.bison.resources.host_key_unknown_title
import com.toxicbakery.app.bison.ssh.HostKeyPrompt
import org.jetbrains.compose.resources.stringResource

/**
 * Shows a host key decision. The layout is the same as the hardware security key sheet.
 *
 * - [HostKeyPrompt.Unknown]: the fingerprint of the new key, with [onAccept] and [onReject].
 * - [HostKeyPrompt.Changed]: the saved and the new fingerprint, with [onRemoveSavedKey] and [onReject].
 *
 * A tap outside the sheet calls [onReject].
 */
@Composable
internal fun HostKeyPromptSheet(
    prompt: HostKeyPrompt,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onRemoveSavedKey: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    val borderColor = when (prompt) {
        is HostKeyPrompt.Unknown -> palette.accent
        is HostKeyPrompt.Changed -> palette.red
    }
    Dialog(onDismissRequest = onReject) {
        Surface(
            modifier = Modifier
                .widthIn(min = 340.dp, max = 520.dp)
                .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
                .testTag("hostKeyPromptDialog"),
            shape = RoundedCornerShape(20.dp),
            color = palette.panelBg,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (prompt) {
                    is HostKeyPrompt.Unknown -> UnknownHostKeyContent(
                        prompt = prompt,
                        onAccept = onAccept,
                        onReject = onReject,
                    )

                    is HostKeyPrompt.Changed -> ChangedHostKeyContent(
                        prompt = prompt,
                        onRemoveSavedKey = onRemoveSavedKey,
                        onCancel = onReject,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnknownHostKeyContent(
    prompt: HostKeyPrompt.Unknown,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    val presented = prompt.presented
    Text(
        text = stringResource(Res.string.host_key_unknown_title),
        style = MaterialTheme.typography.titleLarge,
        color = palette.accent,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(Res.string.host_key_unknown_body, presented.hostPort),
        style = MaterialTheme.typography.bodyMedium,
        color = palette.text,
    )
    FingerprintBlock(
        label = stringResource(Res.string.host_key_type_label, presented.keyType),
        fingerprint = presented.fingerprint,
        labelColor = palette.mauve,
        testTag = "hostKeyFingerprint",
    )
    Text(
        text = stringResource(Res.string.host_key_fingerprint_hint),
        style = MaterialTheme.typography.bodySmall,
        color = palette.text.copy(alpha = 0.72f),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier.testTag("rejectHostKey"),
        ) {
            Text(stringResource(Res.string.host_key_reject))
        }
        Button(
            onClick = onAccept,
            modifier = Modifier.testTag("acceptHostKey"),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.terminalBg,
            ),
        ) {
            Text(stringResource(Res.string.host_key_accept), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChangedHostKeyContent(
    prompt: HostKeyPrompt.Changed,
    onRemoveSavedKey: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalBisonPalette.current
    Text(
        text = stringResource(Res.string.host_key_changed_title),
        style = MaterialTheme.typography.titleLarge,
        color = palette.red,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(Res.string.host_key_changed_body, prompt.presented.hostPort),
        style = MaterialTheme.typography.bodyMedium,
        color = palette.text,
    )
    FingerprintBlock(
        label = stringResource(Res.string.host_key_changed_saved_label, prompt.known.keyType),
        fingerprint = prompt.known.fingerprint,
        labelColor = palette.mauve,
        testTag = "savedHostKeyFingerprint",
    )
    FingerprintBlock(
        label = stringResource(Res.string.host_key_changed_sent_label, prompt.presented.keyType),
        fingerprint = prompt.presented.fingerprint,
        labelColor = palette.red,
        testTag = "sentHostKeyFingerprint",
    )
    Text(
        text = stringResource(Res.string.host_key_changed_remove_hint),
        style = MaterialTheme.typography.bodySmall,
        color = palette.text.copy(alpha = 0.72f),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.testTag("cancelHostKey"),
        ) {
            Text(stringResource(Res.string.common_cancel))
        }
        Button(
            onClick = onRemoveSavedKey,
            modifier = Modifier.testTag("removeSavedHostKey"),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.red,
                contentColor = palette.terminalBg,
            ),
        ) {
            Text(stringResource(Res.string.host_key_changed_remove), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Shows [label] and the [fingerprint] in a monospace font. The user can select the fingerprint text. */
@Composable
private fun FingerprintBlock(
    label: String,
    fingerprint: String,
    labelColor: Color,
    testTag: String,
) {
    val palette = LocalBisonPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor,
        )
        SelectionContainer {
            Text(
                text = fingerprint,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = palette.text,
                modifier = Modifier.testTag(testTag),
            )
        }
    }
}
