package com.toxicbakery.app.bison.activity

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.notification_permission_rationale_body
import com.toxicbakery.app.bison.resources.notification_permission_rationale_continue
import com.toxicbakery.app.bison.resources.notification_permission_rationale_dismiss
import com.toxicbakery.app.bison.resources.notification_permission_rationale_title
import org.jetbrains.compose.resources.stringResource

/**
 * Tells the user why BisonHerdr needs the `POST_NOTIFICATIONS` permission. [MainActivity] shows
 * this dialog when the user denied the permission one time.
 *
 * @param onContinue Called when the user wants to see the system permission dialog.
 * @param onDismiss Called when the user closes the dialog with no permission request.
 */
@Composable
internal fun NotificationPermissionRationale(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.notification_permission_rationale_title)) },
        text = { Text(stringResource(Res.string.notification_permission_rationale_body)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(Res.string.notification_permission_rationale_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.notification_permission_rationale_dismiss))
            }
        },
    )
}
