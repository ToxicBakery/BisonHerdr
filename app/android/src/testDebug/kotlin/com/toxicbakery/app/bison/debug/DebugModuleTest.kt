package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.di.coreDiModule
import com.toxicbakery.app.bison.di.platformModule
import com.toxicbakery.app.bison.fakeserver.FakeHerdrServer
import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.ssh.RealSshBackend
import com.toxicbakery.app.bison.ssh.SelectingSshBackend
import com.toxicbakery.app.bison.ssh.SimulatedSshBackend
import com.toxicbakery.app.bison.ssh.SshBackend
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.kodein.di.DI
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
    }
    private val backend = assertIs<SelectingSshBackend>(di.direct.instance<SshBackend>())

    @Test
    fun tailnetMachineThatTheFakeServerDidNotMakeGetsTheRealBackend() {
        val machine = HostMachine(
            id = "host-gpu-box-tailnet-ts-net-ian",
            name = "gpu-box",
            hostname = "gpu-box.tailnet.ts.net",
            username = "ian",
        )

        assertSame(di.direct.instance<RealSshBackend>(), backend.backendFor(machine.id))
    }

    @Test
    fun fakeServerMachinesGetTheSimulatedBackend() {
        // The seeded gpu-rig machine has the host name gpu-rig.tailnet.ts.net.
        assertIs<SimulatedSshBackend>(backend.backendFor(FakeHerdrServer.GPU_RIG_ID))
        assertIs<SimulatedSshBackend>(backend.backendFor(FakeHerdrServer.WORKBOX_ID))
    }

    @Test
    fun debugServerIsTheFakeServerThatSelectsTheBackend() {
        assertSame(di.direct.instance<FakeHerdrServer>(), di.direct.instance<DebugServer>())
    }
}
