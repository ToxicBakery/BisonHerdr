package com.toxicbakery.app.bison.activity

import com.toxicbakery.app.bison.resources.Res
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_body
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_body_timed_out
import com.toxicbakery.app.bison.resources.local_network_permission_rationale_continue
import com.toxicbakery.app.bison.resources.local_network_permission_settings_body
import com.toxicbakery.app.bison.resources.local_network_permission_settings_body_timed_out
import com.toxicbakery.app.bison.resources.local_network_permission_settings_open
import com.toxicbakery.app.bison.ssh.LocalNetworkOutcome
import com.toxicbakery.app.bison.ssh.NeedsUserCause
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests the steps of [LocalNetworkPermissionFlow] with a script of answers in place of Android, and
 * [reconnectAfterLocalNetworkGrant].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkPermissionFlowTest {

    /** Each step of the flow as a short text, in order. */
    private val steps = mutableListOf<String>()

    /**
     * Returns a flow with these answers. [rationale] gives the answer of `shouldShowRequestPermissionRationale` before
     * and after the system dialog. [dialogAnswer] is the answer of the user to a dialog of the app. [grant] is the
     * result of the system dialog. [grantedAfter] is the permission state after the system dialog.
     */
    private fun flow(
        granted: Boolean = false,
        rationale: List<Boolean> = listOf(false, false),
        dialogAnswer: Boolean = true,
        grant: Boolean = false,
        grantedAfter: Boolean = grant,
    ): LocalNetworkPermissionFlow {
        val rationaleAnswers = ArrayDeque(rationale)
        var systemDialogShown = false
        return LocalNetworkPermissionFlow(
            isGranted = { if (systemDialogShown) grantedAfter else granted },
            shouldShowRationale = { rationaleAnswers.removeFirst() },
            showDialog = { dialog, hostPort, timedOut ->
                steps += "dialog $dialog $hostPort" + if (timedOut) " timed out" else ""
                dialogAnswer
            },
            requestPermission = {
                steps += "system dialog"
                systemDialogShown = true
                grant
            },
            openAppSettings = { steps += "app settings" },
        )
    }

    @Test
    fun withThePermissionTheFlowShowsNothing() = runTest {
        assertEquals(LocalNetworkOutcome.Granted, flow(granted = true).ask(HOST_PORT))
        assertTrue(steps.isEmpty(), "The flow showed $steps")
    }

    @Test
    fun theFirstRequestShowsOnlyTheSystemDialog() = runTest {
        assertEquals(LocalNetworkOutcome.Granted, flow(grant = true).ask(HOST_PORT))
        assertEquals(listOf("system dialog"), steps)
    }

    @Test
    fun aFirstDenialShowsNoMoreDialogs() = runTest {
        // After one denial, Android tells the app to explain the permission at the next request.
        val outcome = flow(rationale = listOf(false, true)).ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Denied, outcome)
        assertEquals(listOf("system dialog"), steps)
    }

    @Test
    fun afterADenialTheRationaleComesBeforeTheSystemDialog() = runTest {
        val outcome = flow(rationale = listOf(true), grant = true).ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Granted, outcome)
        assertEquals(listOf("dialog Rationale $HOST_PORT", "system dialog"), steps)
    }

    @Test
    fun theUserCanCloseTheRationaleWithNoSystemDialog() = runTest {
        val outcome = flow(rationale = listOf(true), dialogAnswer = false).ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Denied, outcome)
        assertEquals(listOf("dialog Rationale $HOST_PORT"), steps)
    }

    @Test
    fun aSecondDenialAfterTheRationaleDoesNotOpenTheSettings() = runTest {
        val outcome = flow(rationale = listOf(true, false)).ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Denied, outcome)
        assertEquals(listOf("dialog Rationale $HOST_PORT", "system dialog"), steps)
    }

    @Test
    fun whenAndroidDoesNotAskAgainTheFlowOffersTheAppSettings() = runTest {
        val outcome = flow().ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Denied, outcome)
        assertEquals(listOf("system dialog", "dialog Settings $HOST_PORT", "app settings"), steps)
    }

    @Test
    fun theUserCanCloseTheSettingsDialog() = runTest {
        val outcome = flow(dialogAnswer = false).ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Denied, outcome)
        assertEquals(listOf("system dialog", "dialog Settings $HOST_PORT"), steps)
    }

    @Test
    fun anEmptyResultAfterADifferentGrantShowsNoSettingsDialog() = runTest {
        // A second launch while the system dialog is open returns an empty result, and the other request granted it.
        val outcome = flow(grant = false, grantedAfter = true).ask(HOST_PORT)

        assertEquals(LocalNetworkOutcome.Granted, outcome)
        assertEquals(listOf("system dialog"), steps)
    }

    @Test
    fun theDialogsGetTheReasonOfTheRequest() = runTest {
        flow(rationale = listOf(true), dialogAnswer = false).ask(HOST_PORT, timedOut = true)
        flow().ask(HOST_PORT, timedOut = true)

        assertEquals(
            listOf(
                "dialog Rationale $HOST_PORT timed out",
                "system dialog",
                "dialog Settings $HOST_PORT timed out",
                "app settings",
            ),
            steps,
        )
    }

    @Test
    fun eachDialogHasATextForEachReason() {
        assertEquals(
            Res.string.local_network_permission_rationale_body to
                Res.string.local_network_permission_rationale_continue,
            localNetworkDialogTexts(LocalNetworkDialog.Rationale, timedOut = false),
        )
        assertEquals(
            Res.string.local_network_permission_rationale_body_timed_out to
                Res.string.local_network_permission_rationale_continue,
            localNetworkDialogTexts(LocalNetworkDialog.Rationale, timedOut = true),
        )
        assertEquals(
            Res.string.local_network_permission_settings_body to Res.string.local_network_permission_settings_open,
            localNetworkDialogTexts(LocalNetworkDialog.Settings, timedOut = false),
        )
        assertEquals(
            Res.string.local_network_permission_settings_body_timed_out to
                Res.string.local_network_permission_settings_open,
            localNetworkDialogTexts(LocalNetworkDialog.Settings, timedOut = true),
        )
    }

    @Test
    fun aSecondCallerWaitsForTheOpenSystemDialog() = runTest {
        var launches = 0
        val launch = SinglePermissionLaunch { launches++ }
        val first = async { launch.await() }
        val second = async { launch.await() }
        runCurrent()

        launch.onResult(true)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, launches)

        // After the result, the next caller shows the system dialog again.
        val third = async { launch.await() }
        runCurrent()
        launch.onResult(false)
        assertFalse(third.await())
        assertEquals(2, launches)
    }

    @Test
    fun aLaunchThatFailsLetsTheNextCallerLaunchAgain() = runTest {
        var fail = true
        val launch = SinglePermissionLaunch { if (fail) throw IllegalStateException("No activity") }

        assertFailsWith<IllegalStateException> { launch.await() }
        fail = false
        val next = async { launch.await() }
        runCurrent()
        launch.onResult(true)
        assertTrue(next.await())
    }

    @Test
    fun theGrantConnectsAgainOnlyTheMachinesThatTheLocalNetworkStopped() = runTest {
        val stateManager = SshStateManager()
        val requestManager = SshRequestManager()
        stateManager.setNeedsUser("lan-box", NeedsUserCause.LocalNetworkPermission("192.168.1.42:22"))
        stateManager.setNeedsUser("ts-box", NeedsUserCause.LocalNetworkPermission("100.101.102.103:22"))
        stateManager.setNeedsUser("cloud-box", NeedsUserCause.LoginRefused("Auth fail"))

        reconnectAfterLocalNetworkGrant(stateManager, requestManager)

        val requests = requestManager.bus.take(2).toList()
        assertEquals(setOf(SshRequest.Connect("lan-box"), SshRequest.Connect("ts-box")), requests.toSet())
        assertNull(stateManager.needsUser.value["lan-box"])
        assertNull(stateManager.needsUser.value["ts-box"])
        assertEquals(NeedsUserCause.LoginRefused("Auth fail"), stateManager.needsUser.value["cloud-box"])

        // A second call finds no cause, and sends no request: the next request in the queue is the marker.
        reconnectAfterLocalNetworkGrant(stateManager, requestManager)
        assertTrue(requestManager.tryEmit(SshRequest.Disconnect("marker")))
        assertEquals(SshRequest.Disconnect("marker"), requestManager.bus.first())
    }

    private companion object {
        const val HOST_PORT = "192.168.1.42:22"
    }
}
