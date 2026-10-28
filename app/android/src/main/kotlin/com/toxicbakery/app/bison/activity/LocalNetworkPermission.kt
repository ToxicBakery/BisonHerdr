package com.toxicbakery.app.bison.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.local_network_permission_dismiss
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_body
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_continue
import com.toxicbakery.app.bison.resources.local_network_permission_settings_body
import com.toxicbakery.app.bison.resources.local_network_permission_settings_open
import com.toxicbakery.app.bison.resources.local_network_permission_title
import com.toxicbakery.app.bison.ssh.LocalNetworkAccess
import com.toxicbakery.app.bison.ssh.LocalNetworkOutcome
import com.toxicbakery.app.bison.ssh.NeedsUserCause
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.ssh.hasLocalNetworkPermission
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.compose.resources.stringResource

/** A dialog of the app about the local network permission. See [LocalNetworkPermissionFlow]. */
internal enum class LocalNetworkDialog {
    /** Tells the user why the app needs the permission, before the system dialog. */
    Rationale,

    /** Tells the user that the app does not have the permission, and offers to open the app settings. */
    Settings,
}

/**
 * Asks the user for the local network permission (`ACCESS_LOCAL_NETWORK`) of Android 17 for a connection to a host.
 * [MainActivity] shows the requests of [LocalNetworkAccess] with [ask].
 *
 * 1. When the app has the permission, the answer is [LocalNetworkOutcome.Granted], and the user sees no dialog.
 * 2. When Android tells the app to explain the permission, the flow shows [LocalNetworkDialog.Rationale] first.
 * 3. The flow shows the system dialog.
 * 4. Android shows no system dialog after the user refused the permission two times. Then Android also tells the app
 *    not to explain the permission. When the flow sees this before and after the system dialog, it shows
 *    [LocalNetworkDialog.Settings], which can open the app settings. A user who closes the first system dialog with
 *    Back also sees this dialog, so its text is true in the two cases.
 *
 * [showDialog] shows a dialog of the app for a host, and returns true when the user selects the main button.
 * [requestPermission] shows the system dialog, and returns true when the app has the permission after it.
 */
internal class LocalNetworkPermissionFlow(
    private val isGranted: () -> Boolean,
    private val shouldShowRationale: () -> Boolean,
    private val showDialog: suspend (dialog: LocalNetworkDialog, hostPort: String) -> Boolean,
    private val requestPermission: suspend () -> Boolean,
    private val openAppSettings: () -> Unit,
) {

    /** Asks for the permission for a connection to [hostPort], and returns the result. */
    suspend fun ask(hostPort: String): LocalNetworkOutcome {
        if (isGranted()) return LocalNetworkOutcome.Granted
        val rationaleBefore = shouldShowRationale()
        if (rationaleBefore && !showDialog(LocalNetworkDialog.Rationale, hostPort)) return LocalNetworkOutcome.Denied
        if (requestPermission()) return LocalNetworkOutcome.Granted
        if (!rationaleBefore && !shouldShowRationale() && showDialog(LocalNetworkDialog.Settings, hostPort)) {
            openAppSettings()
        }
        return LocalNetworkOutcome.Denied
    }
}

/**
 * Connects again each machine that stopped because the app did not have the local network permission
 * ([NeedsUserCause.LocalNetworkPermission]). Call it when the app has the permission, for example after the user
 * allowed it in the app settings. It removes the cause before the connect, so a second call does not connect the same
 * machine again.
 */
internal suspend fun reconnectAfterLocalNetworkGrant(
    sshStateManager: SshStateManager,
    sshRequestManager: SshRequestManager,
) {
    val blocked = sshStateManager.needsUser.value.filterValues { it is NeedsUserCause.LocalNetworkPermission }.keys
    for (machineId in blocked) {
        sshStateManager.clearNeedsUser(machineId)
        sshRequestManager.emit(SshRequest.Connect(machineId))
    }
}

/**
 * Connects [LocalNetworkPermissionFlow] to [activity]: the system dialog, the dialogs of the app, and the app settings.
 * Make it before [activity] starts, because it registers an activity result.
 *
 * [onGranted] runs on each grant from the system dialog. It also runs when no request waits for the result. This
 * occurs after the time limit of the request, or when Android made the activity again while the dialog was open.
 */
internal class LocalNetworkPermissionRequester(
    private val activity: ComponentActivity,
    private val onGranted: () -> Unit,
) {

    /** A dialog of the app that waits for the user. */
    class OpenDialog(val dialog: LocalNetworkDialog, val hostPort: String) {
        /** True when the user selects the main button. Only the first answer has an effect. */
        val answer = CompletableDeferred<Boolean>()
    }

    // Only the main thread reads and writes this field.
    private var permissionResult: CompletableDeferred<Boolean>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionResult?.complete(granted)
        if (granted) onGranted()
    }

    /** The dialog that the activity shows now, or null. */
    var openDialog by mutableStateOf<OpenDialog?>(null)
        private set

    val flow = LocalNetworkPermissionFlow(
        isGranted = { hasLocalNetworkPermission(activity) },
        shouldShowRationale = ::shouldShowRationale,
        showDialog = ::awaitDialog,
        requestPermission = ::awaitPermission,
        openAppSettings = ::openAppSettings,
    )

    private fun shouldShowRationale(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)

    private suspend fun awaitDialog(dialog: LocalNetworkDialog, hostPort: String): Boolean {
        val open = OpenDialog(dialog, hostPort)
        openDialog = open
        try {
            return open.answer.await()
        } finally {
            if (openDialog === open) openDialog = null
        }
    }

    private suspend fun awaitPermission(): Boolean {
        // Before API 37, the permission does not exist, and Android stops no connection.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return true
        val result = CompletableDeferred<Boolean>()
        permissionResult = result
        try {
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return result.await()
        } finally {
            if (permissionResult === result) permissionResult = null
        }
    }

    private fun openAppSettings() {
        val packageUri = Uri.fromParts("package", activity.packageName, null)
        runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
    }
}

/** Shows [open] with the texts of its [LocalNetworkPermissionRequester.OpenDialog.dialog]. */
@Composable
internal fun LocalNetworkPermissionDialog(open: LocalNetworkPermissionRequester.OpenDialog) {
    val (body, confirm) = when (open.dialog) {
        LocalNetworkDialog.Rationale ->
            Res.string.local_network_permission_rationale_body to Res.string.local_network_permission_rationale_continue
        LocalNetworkDialog.Settings ->
            Res.string.local_network_permission_settings_body to Res.string.local_network_permission_settings_open
    }
    AlertDialog(
        onDismissRequest = { open.answer.complete(false) },
        title = { Text(stringResource(Res.string.local_network_permission_title)) },
        text = { Text(stringResource(body, open.hostPort)) },
        confirmButton = {
            TextButton(onClick = { open.answer.complete(true) }) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { open.answer.complete(false) }) {
                Text(stringResource(Res.string.local_network_permission_dismiss))
            }
        },
    )
}
