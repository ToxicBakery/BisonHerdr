package com.toxicbakery.app.bison

import com.toxicbakery.app.bison.activity.StartedActivityCounter
import com.toxicbakery.app.bison.model.RoamingStatus
import com.toxicbakery.app.bison.ssh.ForegroundState
import com.toxicbakery.app.bison.ssh.LocalNetworkGuard
import com.toxicbakery.app.bison.ssh.SshRequest
import com.toxicbakery.app.bison.ssh.SshStateManager
import com.toxicbakery.app.bison.ssh.SshTransportManager
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Tests that [appModule] gives [SshTransportManager] the foreground state of the started activities. The test runs
 * in the debug and the release unit tests. It checks the behavior, so that a missing binding fails it.
 */
class ForegroundStateBindingTest {

    private val di = DI {
        import(appModule)
        // The Application binds this directory and the local network guard with its Context.
        bindSingleton<StoreIOProvider>(tag = StoreIOProviderTags.SECRETS) { InMemoryStoreIOProvider() }
        bindSingleton<LocalNetworkGuard> {
            LocalNetworkGuard(hasPermission = { true }, localPrefixes = { emptyList() })
        }
    }

    @Test
    fun foregroundStateIsTheCounterOfTheStartedActivities() {
        val counter = di.direct.instance<StartedActivityCounter>()
        val foregroundState = di.direct.instance<ForegroundState>()
        assertSame<Any>(counter, foregroundState)

        assertFalse(foregroundState.isInForeground())
        counter.onActivityStarted()
        assertTrue(foregroundState.isInForeground())
        counter.onActivityStopped()
        assertFalse(foregroundState.isInForeground())
    }

    @Test
    fun theTransportManagerKeepsTheLeaseOffWhileNoActivityIsStarted() = runTest {
        val counter = di.direct.instance<StartedActivityCounter>()
        val stateManager = di.direct.instance<SshStateManager>()
        val transport = di.direct.instance<SshTransportManager>()

        // No activity is started. With ForegroundState.ALWAYS, the status would be Healthy.
        transport.handleRequest(SshRequest.SetSurfaceLease(MACHINE_ID, active = true))
        assertEquals(RoamingStatus.SurfaceParked, stateManager.roamingState.value[MACHINE_ID])

        counter.onActivityStarted()
        transport.handleRequest(SshRequest.SetSurfaceLease(MACHINE_ID, active = true))
        assertEquals(RoamingStatus.Healthy, stateManager.roamingState.value[MACHINE_ID])
    }

    private companion object {
        const val MACHINE_ID = "lan-box"
    }
}
