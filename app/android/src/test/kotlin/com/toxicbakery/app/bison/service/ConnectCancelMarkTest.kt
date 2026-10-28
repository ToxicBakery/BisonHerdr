package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests that [cancelUserAuthBeforeQueue] keeps the cancel mark of a connect request when the request arrives. A
 * disconnect while the connect waits in the control queue then also stops the security key login of the connect
 * (issue #23).
 */
class ConnectCancelMarkTest {

    private val stateManager = SshStateManager()

    @Test
    fun aDisconnectAfterTheConnectRequestChangesTheCountAfterTheMark() {
        stateManager.cancelUserAuthBeforeQueue(SshRequest.Connect("workbox"))
        stateManager.cancelUserAuthBeforeQueue(SshRequest.Disconnect("workbox"))

        // The login of the queued connect starts now. Its mark is older than the count, so the signer stops it.
        val loginMark = stateManager.takeLoginCancelMark("workbox")
        assertEquals(0L, loginMark)
        assertEquals(1L, stateManager.userAuthCancelCounts.value.of("workbox"))
    }

    @Test
    fun aConnectWithNoDisconnectGivesTheCountNow() {
        stateManager.cancelUserAuthBeforeQueue(SshRequest.Disconnect("workbox"))
        stateManager.cancelUserAuthBeforeQueue(SshRequest.Connect("workbox"))
        stateManager.cancelUserAuthBeforeQueue(SshRequest.Disconnect("gpu-rig"))

        assertEquals(1L, stateManager.takeLoginCancelMark("workbox"))
        assertEquals(1L, stateManager.userAuthCancelCounts.value.of("workbox"))
    }
}
