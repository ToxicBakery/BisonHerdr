package com.toxicbakery.app.bison.activity

import com.toxicbakery.app.bison.ssh.LocalNetworkOutcome
import com.toxicbakery.app.bison.ssh.NeedsUserCause
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshRequestManager
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Tests the steps of [LocalNetworkPermissionFlow] with a script of answers in place of Android, and
 * [reconnectAfterLocalNetworkGrant].
 */
class LocalNetworkPermissionFlowTest {

    /** Each step of the flow as a short text, in order. */
    private val steps = mutableListOf<String>()

    /**
     * Returns a flow with these answers. [rationale] gives the answer of `shouldShowRequestPermissionRationale` before
     * and after the system dialog. [dialogAnswer] is the answer of the user to a dialog of the app. [grant] is the
     * result of the system dialog.
     */
    private fun flow(
        granted: Boolean = false,
        rationale: List<Boolean> = listOf(false, false),
        dialogAnswer: Boolean = true,
        grant: Boolean = false,
    ): LocalNetworkPermissionFlow {
        val rationaleAnswers = ArrayDeque(rationale)
        return LocalNetworkPermissionFlow(
            isGranted = { granted },
            shouldShowRationale = { rationaleAnswers.removeFirst() },
            showDialog = { dialog, hostPort ->
                steps += "dialog $dialog $hostPort"
                dialogAnswer
            },
            requestPermission = {
                steps += "system dialog"
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
