package com.toxicbakery.app.bison.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
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
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_body_timed_out
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_continue
import com.toxicbakery.app.bison.resources.local_network_permission_settings_body
import com.toxicbakery.app.bison.resources.local_network_permission_settings_body_timed_out
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
 * The flow reads the permission again before the settings dialog. A different request can get the grant while this
 * request waits, and then the flow shows no dialog.
 *
 * [showDialog] shows a dialog of the app for a host, and returns true when the user selects the main button. Its
 * `timedOut` tells the reason of the request ([LocalNetworkRequest.timedOut]). [requestPermission] shows the system
 * dialog, and returns true when the app has the permission after it.
 */
internal class LocalNetworkPermissionFlow(
    private val isGranted: () -> Boolean,
    private val shouldShowRationale: () -> Boolean,
    private val showDialog: suspend (dialog: LocalNetworkDialog, hostPort: String, timedOut: Boolean) -> Boolean,
    private val requestPermission: suspend () -> Boolean,
    private val openAppSettings: () -> Unit,
) {

    /** Asks for the permission for a connection to [hostPort] for the reason [timedOut], and returns the result. */
    suspend fun ask(hostPort: String, timedOut: Boolean = false): LocalNetworkOutcome {
        if (isGranted()) return LocalNetworkOutcome.Granted
        val rationaleBefore = shouldShowRationale()
        if (rationaleBefore && !showDialog(LocalNetworkDialog.Rationale, hostPort, timedOut)) {
            return LocalNetworkOutcome.Denied
        }
        if (requestPermission() || isGranted()) return LocalNetworkOutcome.Granted
        if (!rationaleBefore && !shouldShowRationale() && showDialog(LocalNetworkDialog.Settings, hostPort, timedOut)) {
            openAppSettings()
        }
        return LocalNetworkOutcome.Denied
    }
}

/**
 * Lets one request of the system dialog run at a time. A second launch while the system dialog is open returns an
 * empty result at once. Thus a second caller waits for the result of the request that runs. [launch] shows the system
 * dialog, and the activity result callback calls [onResult].
 */
internal class SinglePermissionLaunch(private val launch: () -> Unit) {

    // Only the main thread reads and writes this field.
    private var inFlight: CompletableDeferred<Boolean>? = null

    /** Shows the system dialog, or waits for the dialog that is open. Returns true when the user granted it. */
    suspend fun await(): Boolean {
        inFlight?.let { return it.await() }
        val result = CompletableDeferred<Boolean>()
        inFlight = result
        try {
            launch()
        } catch (error: RuntimeException) {
            inFlight = null
            throw error
        }
        return result.await()
    }

    /** Gives [granted] to each caller of [await]. The next [await] shows the system dialog again. */
    fun onResult(granted: Boolean) {
        val result = inFlight
        inFlight = null
        result?.complete(granted)
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

    /** A dialog of the app that waits for the user. [timedOut] is the reason of the request. */
    class OpenDialog(val dialog: LocalNetworkDialog, val hostPort: String, val timedOut: Boolean) {
        /** True when the user selects the main button. Only the first answer has an effect. */
        val answer = CompletableDeferred<Boolean>()
    }

    private val launcher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionLaunch.onResult(granted)
            if (granted) onGranted()
        }

    private val permissionLaunch: SinglePermissionLaunch = SinglePermissionLaunch {
        // awaitPermission calls it only on API 37 and later.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            error("The local network permission does not exist before API 37")
        }
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

    private suspend fun awaitDialog(dialog: LocalNetworkDialog, hostPort: String, timedOut: Boolean): Boolean {
        val open = OpenDialog(dialog, hostPort, timedOut)
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
        return permissionLaunch.await()
    }

    private fun openAppSettings() {
        val packageUri = Uri.fromParts("package", activity.packageName, null)
        runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)) }
    }
}

/**
 * Shows [open] with the texts of its [LocalNetworkPermissionRequester.OpenDialog.dialog]. After a connect timeout, the
 * app does not know that the host is on the local network. Then the texts tell only that Android can stop the
 * connection.
 */
@Composable
internal fun LocalNetworkPermissionDialog(open: LocalNetworkPermissionRequester.OpenDialog) {
    val (body, confirm) = when (open.dialog) {
        LocalNetworkDialog.Rationale -> if (open.timedOut) {
            Res.string.local_network_permission_rationale_body_timed_out
        } else {
            Res.string.local_network_permission_rationale_body
        } to Res.string.local_network_permission_rationale_continue
        LocalNetworkDialog.Settings -> if (open.timedOut) {
            Res.string.local_network_permission_settings_body_timed_out
        } else {
            Res.string.local_network_permission_settings_body
        } to Res.string.local_network_permission_settings_open
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
