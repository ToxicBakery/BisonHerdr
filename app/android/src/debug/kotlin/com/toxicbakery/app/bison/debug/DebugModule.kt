package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.fakeserver.FakeHerdrServer
import com.toxicbakery.app.bison.ssh.CredentialChecker
import com.toxicbakery.app.bison.ssh.CredentialState
import com.toxicbakery.app.bison.ssh.CredentialValidator
import com.toxicbakery.app.bison.ssh.RealSshBackend
import com.toxicbakery.app.bison.ssh.SelectingSshBackend
import com.toxicbakery.app.bison.ssh.SimulatedSshBackend
import com.toxicbakery.app.bison.ssh.SshBackend
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Debug bindings importing [fakeServerModule] from `:core:fake-server`.
 *
 * [SshBackend] is a [SelectingSshBackend]. It uses [SimulatedSshBackend] only for the machines that
 * [FakeHerdrServer] made, and [RealSshBackend] for all other machines. The host name has no effect.
 *
 * The [CredentialChecker] gives [CredentialState.Ready] for the machines that [FakeHerdrServer] made, because the
 * simulated backend makes their key at the connect. It uses [CredentialValidator] for all other machines.
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeServerModule)
    bindSingleton<SshBackend> {
        val fakeServer = instance<FakeHerdrServer>()
        SelectingSshBackend(
            real = instance<RealSshBackend>(),
            simulated = SimulatedSshBackend(
                sshStateManager = instance(),
                sshDebugLog = instance(),
                endpointSnapshotBus = instance(),
                paneSurfaceBus = instance(),
                semanticNotificationBus = instance(),
                keystoreKeyManager = instance(),
            ),
            isSimulated = fakeServer::isFakeMachine,
        )
    }
    bindSingleton<CredentialChecker> {
        val fakeServer = instance<FakeHerdrServer>()
        val validator = instance<CredentialValidator>()
        CredentialChecker { machine ->
            if (fakeServer.isFakeMachine(machine.id)) CredentialState.Ready else validator.check(machine)
        }
    }
}
