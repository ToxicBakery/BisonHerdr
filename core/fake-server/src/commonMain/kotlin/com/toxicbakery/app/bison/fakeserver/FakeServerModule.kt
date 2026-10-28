package com.toxicbakery.app.bison.fakeserver

import com.toxicbakery.app.bison.debug.DebugServer
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

/**
 * Kodein module binding [FakeHerdrServer] over the [DebugServer] seam for debug builds.
 *
 * Wired into `:app:android` via `debugImplementation(projects.core.fakeServer)` only;
 * release builds bind `NoDebugServer` so zero simulated server code ships in production.
 * The debug bindings also get [FakeHerdrServer] by its own type to ask [FakeHerdrServer.isFakeMachine].
 */
val fakeServerModule = DI.Module("fakeServerModule") {
    bindSingleton {
        FakeHerdrServer(
            machineStore = instance(),
            sshStateManager = instance(),
            sshDebugLog = instance(),
            endpointSnapshotBus = instance(),
            paneSurfaceBus = instance(),
            semanticNotificationBus = instance(),
        )
    }
    bindSingleton<DebugServer> { instance<FakeHerdrServer>() }
}
