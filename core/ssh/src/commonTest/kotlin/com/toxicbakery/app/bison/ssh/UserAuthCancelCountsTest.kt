package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests the marks of the connect requests of [SshStateManager] ([SshStateManager.markConnectRequest]). */
class UserAuthCancelCountsTest {

    private val stateManager = SshStateManager()

    @Test
    fun aLoginTakesTheMarkOfItsConnectRequestOneTime() {
        stateManager.markConnectRequest(MACHINE_ID)
        // The disconnect comes while the connect waits in the queue.
        stateManager.cancelUserAuth(MACHINE_ID)

        val loginMark = stateManager.takeLoginCancelMark(MACHINE_ID)

        assertEquals(0L, loginMark)
        assertEquals(1L, stateManager.userAuthCancelCounts.value.of(MACHINE_ID))
        // A later login, for example a reconnect, takes the count now.
        assertEquals(1L, stateManager.takeLoginCancelMark(MACHINE_ID))
    }

    @Test
    fun theMarkOfOneMachineDoesNotChangeTheMarkOfADifferentMachine() {
        stateManager.cancelUserAuth(OTHER_MACHINE_ID)
        stateManager.markConnectRequest(MACHINE_ID)
        stateManager.markConnectRequest(OTHER_MACHINE_ID)
        stateManager.cancelUserAuth(OTHER_MACHINE_ID)

        assertEquals(0L, stateManager.takeLoginCancelMark(MACHINE_ID))
        assertEquals(1L, stateManager.takeLoginCancelMark(OTHER_MACHINE_ID))
        assertEquals(2L, stateManager.userAuthCancelCounts.value.of(OTHER_MACHINE_ID))

        stateManager.cancelAllUserAuth()
        assertEquals(1L, stateManager.takeLoginCancelMark(MACHINE_ID))
    }

    private companion object {
        const val MACHINE_ID = "workbox"
        const val OTHER_MACHINE_ID = "gpu-rig"
    }
}
