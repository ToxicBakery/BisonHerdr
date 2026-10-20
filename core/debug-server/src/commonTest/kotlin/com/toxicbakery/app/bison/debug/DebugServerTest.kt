package com.toxicbakery.app.bison.debug

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DebugServerTest {

    @Test
    fun `NoDebugServer remains disabled after enable`() {
        assertFalse(NoDebugServer.isEnabled)
        NoDebugServer.enable()
        assertFalse(NoDebugServer.isEnabled)
    }

    @Test
    fun `NoDebugServer handlers are inert`() = runTest {
        assertFalse(NoDebugServer.handleConnect("workbox"))
        assertFalse(NoDebugServer.handleDisconnect("workbox"))
        assertFalse(NoDebugServer.handleSetSurfaceLease("workbox", active = true))
        assertNull(NoDebugServer.handleEndpointCommand("workbox", "pane.split", "{}"))
        assertFalse(NoDebugServer.handlePaneInput("workbox", "w1:p1", "{}"))
        assertFalse(NoDebugServer.handleResizeShell("workbox", 120u, 40u))
    }
}
