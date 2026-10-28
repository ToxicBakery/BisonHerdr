package com.toxicbakery.app.bison.common.screen.machine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.common_cancel
import com.toxicbakery.app.bison.resources.machine_key_regenerate
import com.toxicbakery.app.bison.resources.machine_key_regenerate_confirm
import com.toxicbakery.app.bison.resources.machine_key_regenerate_hint
import com.toxicbakery.app.bison.resources.machine_key_regenerate_warning_body
import com.toxicbakery.app.bison.resources.machine_key_regenerate_warning_title
import org.jetbrains.compose.resources.stringResource

/**
 * Tells the user that the biometric setting applies only to a new key, and shows the action that makes a
 * new key. A key keeps the parameters that it had when the app made it.
 *
 * @param enabled False while the screen loads or makes a key.
 * @param onRegenerate Called when the user wants a new key. The screen then shows [RegenerateKeyWarningDialog].
 */
@Composable
internal fun RegenerateKeySection(
    enabled: Boolean,
    onRegenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.machine_key_regenerate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRegenerate,
            enabled = enabled,
            modifier = Modifier.testTag("regenerateKey"),
        ) {
            Text(stringResource(Res.string.machine_key_regenerate))
        }
    }
}

/**
 * Tells the user that a new key replaces the key [keyAlias], and that each host needs the new public key.
 *
 * @param onConfirm Called when the user wants the new key.
 * @param onDismiss Called when the user keeps the old key.
 */
@Composable
internal fun RegenerateKeyWarningDialog(
    keyAlias: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("regenerateKeyWarning"),
        title = { Text(stringResource(Res.string.machine_key_regenerate_warning_title)) },
        text = { Text(stringResource(Res.string.machine_key_regenerate_warning_body, keyAlias)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirmRegenerateKey")) {
                Text(
                    text = stringResource(Res.string.machine_key_regenerate_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}
