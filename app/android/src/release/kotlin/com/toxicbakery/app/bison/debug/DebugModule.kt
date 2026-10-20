package com.toxicbakery.app.bison.debug

import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Release bindings. Binds the inert [NoDebugServer] so zero simulated server code ships in release builds.
 */
internal val debugModule = DI.Module("debugModule") {
    bindSingleton<DebugServer> { NoDebugServer }
}
