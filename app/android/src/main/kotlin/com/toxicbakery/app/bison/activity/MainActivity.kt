package com.toxicbakery.app.bison.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.toxicbakery.app.bison.AppTheme
import com.toxicbakery.app.bison.common.App
import com.toxicbakery.app.bison.debug.DebugServer
import com.toxicbakery.app.bison.service.BisonSessionService
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.compose.withDI
import org.kodein.di.instance

/** A [FragmentActivity], because the `androidx.biometric` prompt needs one. */
class MainActivity : FragmentActivity(), DIAware {

    override val di: DI by closestDI()

    private val debugServer: DebugServer by instance()
    private val sshRequestManager: SshRequestManager by instance()
    private val sshStateManager: SshStateManager by instance()
    private val startedActivityCounter: StartedActivityCounter by instance()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log.d { "POST_NOTIFICATIONS granted: $granted" }
        }
    private var showNotificationRationale by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        applyFakeServerIntent(intent)
        // A uiMode or locale change makes a new activity. Keep the rationale dialog open.
        showNotificationRationale = savedInstanceState?.getBoolean(STATE_SHOW_NOTIFICATION_RATIONALE) ?: false

        setContent {
            withDI(di) {
                AppTheme {
                    Box(Modifier.safeDrawingPadding()) {
                        App(
                            onExitRequested = { finish() }
                        )
                        ReportDrawn()
                    }
                    if (showNotificationRationale) {
                        NotificationPermissionRationale(
                            onContinue = {
                                showNotificationRationale = false
                                launchNotificationPermissionRequest()
                            },
                            onDismiss = { showNotificationRationale = false },
                        )
                    }
                }
            }
        }

        // The app starts on the machine list, so this request comes before the first connect.
        if (savedInstanceState == null) {
            requestNotificationPermissionIfNeeded()
        }

        // Show the user authentication prompts of SSH keys and the keyboard-interactive questions of a login only
        // while this activity is started. In the background, such a login stops at once and does not wait for the
        // user. The App composable shows the keyboard-interactive questions.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sshStateManager.hostUserAuthPrompts { prompt -> showUserAuthPrompt(prompt) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_NOTIFICATION_RATIONALE, showNotificationRationale)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyFakeServerIntent(intent)
        startSessionService()
    }

    override fun onStart() {
        super.onStart()
        startedActivityCounter.onActivityStarted()
        // The service is the only collector of the request queue, so start it before the requests.
        // The service can stop itself while this activity is stopped.
        startSessionService()
        // Resume active terminal surface leases (<50ms Endpoint resync)
        lifecycleScope.launch {
            for (machineId in sshStateManager.connectedMachines.value) {
                sshRequestManager.emit(
                    SshRequest.SetSurfaceLease(
                        machineId = machineId,
                        active = true,
                    )
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        startedActivityCounter.onActivityStopped()
        // Park active terminal surfaces at 0 bytes/sec while keeping the control stream alive
        lifecycleScope.launch {
            for (machineId in sshStateManager.connectedMachines.value) {
                sshRequestManager.emit(
                    SshRequest.SetSurfaceLease(
                        machineId = machineId,
                        active = false,
                    )
                )
            }
        }
    }

    private fun applyFakeServerIntent(intent: Intent?) {
        val enableFake = intent?.getBooleanExtra(EXTRA_FAKE_SERVER, false) == true
        if (enableFake) {
            log.d { "Enabling FakeHerdrServer via EXTRA_FAKE_SERVER" }
            debugServer.enable()
        }
    }

    /**
     * Asks for `POST_NOTIFICATIONS` on API 33 and later. When the user denied the permission one
     * time, the activity first shows [NotificationPermissionRationale]. When the user denied it two
     * times, Android shows no dialog.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        if (shouldShowRequestPermissionRationale(permission)) {
            showNotificationRationale = true
        } else {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private fun launchNotificationPermissionRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startSessionService() {
        runCatching {
            val serviceIntent = Intent(this, BisonSessionService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure { error ->
            log.w(error) { "Could not start BisonSessionService in foreground; starting standard service" }
            runCatching {
                startService(Intent(this, BisonSessionService::class.java))
            }
        }
    }

    companion object {
        const val EXTRA_FAKE_SERVER: String = "fake_server"
        private const val STATE_SHOW_NOTIFICATION_RATIONALE: String = "show_notification_rationale"

        private val log = Arbor.tag("MainActivity")
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
