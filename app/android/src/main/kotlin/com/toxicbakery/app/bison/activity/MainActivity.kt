package com.toxicbakery.app.bison.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
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

class MainActivity : ComponentActivity(), DIAware {

    override val di: DI by closestDI()

    private val debugServer: DebugServer by instance()
    private val sshRequestManager: SshRequestManager by instance()
    private val sshStateManager: SshStateManager by instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        applyFakeServerIntent(intent)
        window.decorView.post { startSessionService() }

        setContent {
            withDI(di) {
                AppTheme {
                    Box(Modifier.safeDrawingPadding()) {
                        App(
                            onExitRequested = { finish() }
                        )
                        ReportDrawn()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyFakeServerIntent(intent)
        startSessionService()
    }

    override fun onStart() {
        super.onStart()
        // Resume active terminal surface leases (<50ms Endpoint resync / <20ms Mosh UDP SSP wakeup)
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
        // Park active terminal surfaces at 0 bytes/sec while keeping control stream & Mosh session alive
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

        private val log = Arbor.tag("MainActivity")
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
