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
 */
val fakeServerModule = DI.Module("fakeServerModule") {
    bindSingleton<DebugServer>(overrides = true) {
        FakeHerdrServer(
            machineStore = instance(),
            sshStateManager = instance(),
            sshDebugLog = instance(),
            endpointSnapshotBus = instance(),
            paneSurfaceBus = instance(),
            semanticNotificationBus = instance(),
        )
    }
}
