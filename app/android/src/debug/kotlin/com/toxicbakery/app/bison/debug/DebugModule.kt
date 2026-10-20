package com.toxicbakery.app.bison.debug

import org.kodein.di.DI

/**
 * Debug bindings importing [fakeServerModule] from `:core:fake-server`.
 */
internal val debugModule = DI.Module("debugModule") {
    import(fakeServerModule)
}
