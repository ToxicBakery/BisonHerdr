package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.di.coreDiModule
import com.toxicbakery.app.bison.di.platformModule
import com.toxicbakery.app.bison.fakeserver.FakeHerdrServer
import com.toxicbakery.app.bison.model.ActiveBackbone
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.SshAuthMethod
import com.toxicbakery.app.bison.model.TransportMode
import com.toxicbakery.app.bison.ssh.CredentialChecker
import com.toxicbakery.app.bison.ssh.CredentialState
import com.toxicbakery.app.bison.ssh.ProxyJumpTunnel
import com.toxicbakery.app.bison.ssh.RealSshBackend
import com.toxicbakery.app.bison.ssh.SelectingSshBackend
import com.toxicbakery.app.bison.ssh.SimulatedSshBackend
import com.toxicbakery.app.bison.ssh.SshBackend
import com.toxicbakery.app.bison.ssh.SshDebugLog
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Tests the [SshBackend] binding of the debug [debugModule] with the same modules as the app.
 */
class DebugModuleTest {

    private val di = DI {
        import(coreDiModule)
        import(platformModule)
        import(debugModule)
        // The Application binds this directory with its Context.
        bindSingleton<StoreIOProvider>(tag = StoreIOProviderTags.SECRETS) { InMemoryStoreIOProvider() }
    }
    private val backend = assertIs<SelectingSshBackend>(di.direct.instance<SshBackend>())
    private val debugLog = di.direct.instance<SshDebugLog>()

    @Test
    fun storedTailnetMachineThatTheFakeServerDidNotMakeGetsTheRealBackend() = runBlocking {
        val machine = HostMachine(
            id = "host-gpu-box-tailnet-ts-net-ian",
            name = "gpu-box",
            hostname = "gpu-box.tailnet.ts.net",
            username = "ian",
            transportMode = TransportMode.SshEndpointOnly,
        )
        di.direct.instance<MachineStore>().update { it + machine }

        val backbone = backend.negotiateBackbone(machine, ProxyJumpTunnel().resolveRoute(machine))

        assertIs<ActiveBackbone.SshEndpoint>(backbone)
        assertFalse(hasSimulatedEndpointLog(machine.id), "The simulated backend got the call")
        assertSame(di.direct.instance<RealSshBackend>(), backend.backendFor(machine.id))
    }

    @Test
    fun fakeServerMachinesGetTheSimulatedBackend() = runBlocking {
        // The seeded gpu-rig machine has the host name gpu-rig.tailnet.ts.net.
        assertIs<SimulatedSshBackend>(backend.backendFor(FakeHerdrServer.GPU_RIG_ID))
        assertIs<SimulatedSshBackend>(backend.backendFor(FakeHerdrServer.WORKBOX_ID))

        val workbox = HostMachine(
            id = FakeHerdrServer.WORKBOX_ID,
            name = "workbox",
            hostname = "100.64.0.12",
            username = "ian",
            transportMode = TransportMode.SshEndpointOnly,
        )
        backend.negotiateBackbone(workbox, ProxyJumpTunnel().resolveRoute(workbox))

        assertTrue(hasSimulatedEndpointLog(workbox.id), "The simulated backend did not get the call")
    }

    @Test
    fun debugServerIsTheFakeServerThatSelectsTheBackend() {
        assertSame(di.direct.instance<FakeHerdrServer>(), di.direct.instance<DebugServer>())
    }

    @Test
    fun fakeServerMachinesAreReadyAndOtherMachinesGetTheValidator() = runBlocking {
        val checker = di.direct.instance<CredentialChecker>()
        val keyMachine = HostMachine(
            id = "host-gpu-box-tailnet-ts-net-ian",
            name = "gpu-box",
            hostname = "gpu-box.tailnet.ts.net",
            username = "ian",
            authMethod = SshAuthMethod.KeystoreKey(keyAlias = "debug_module_test_missing_key"),
        )

        // The simulated backend makes the key of a fake server machine at the connect.
        assertEquals(CredentialState.Ready, checker.check(keyMachine.copy(id = FakeHerdrServer.WORKBOX_ID)))
        assertEquals(CredentialState.KeyMissing("debug_module_test_missing_key"), checker.check(keyMachine))
    }

    /**
     * Returns true when the debug log has the `SimulatedHost` entry that only [SimulatedSshBackend] writes.
     * For an endpoint-only machine, [RealSshBackend] selects the backbone with no network call and no log entry.
     */
    private fun hasSimulatedEndpointLog(machineId: String): Boolean =
        debugLog.entries.value.any { it.machineId == machineId && it.tag == "SimulatedHost" }
}
