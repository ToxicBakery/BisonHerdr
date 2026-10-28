package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_cancel
import com.toxicbakery.app.bison.resources.hardware_key_key_type_label
import com.toxicbakery.app.bison.resources.hardware_key_sheet_tap_nfc
import com.toxicbakery.app.bison.resources.hardware_key_sheet_title
import com.toxicbakery.app.bison.resources.hardware_key_sheet_touch_usb
import com.toxicbakery.app.bison.ssh.HardwareKeySigner
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

/**
 * Shows [SshStateManager.hardwareKeyTouchPrompt] in a [HardwareSecurityKeyPromptSheet] above each screen. A
 * [HardwareKeySigner] call puts the prompt while it waits for the security key: at the enrollment on the configure
 * screen, and at a login on the connecting screen or during a reconnect. The cancel button removes the prompt, and
 * the call then stops.
 *
 * Only a build with FIDO2 security keys puts a prompt (issue #23). In all other builds, this host shows nothing.
 */
@Composable
internal fun HardwareKeyTouchPromptHost() {
    val sshStateManager: SshStateManager by rememberInstance()
    val prompt by sshStateManager.hardwareKeyTouchPrompt.collectAsState()
    val touchPrompt = prompt ?: return
    HardwareSecurityKeyPromptSheet(
        prompt = touchPrompt,
        onCancel = { sshStateManager.dismissHardwareKeyTouchPrompt(touchPrompt) },
    )
}

/**
 * Asks the user to touch the security key or to hold it against the device. [onCancel] stops the request. A prompt
 * with no message shows the default USB and NFC texts.
 */
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
                if (prompt.promptMessage.isBlank()) {
                    Text(
                        text = stringResource(Res.string.hardware_key_sheet_touch_usb),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.text,
                    )
                    Text(
                        text = stringResource(Res.string.hardware_key_sheet_tap_nfc),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text.copy(alpha = 0.72f),
                    )
                } else {
                    Text(
                        text = prompt.promptMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.text,
                        modifier = Modifier.testTag("hardwareKeyPromptMessage"),
                    )
                }
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
