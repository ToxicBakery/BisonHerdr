package com.toxicbakery.app.bison.debug

import com.toxicbakery.app.bison.fakeserver.fakeServerModule as implFakeServerModule
import org.kodein.di.DI

/**
 * Package alias for [com.toxicbakery.app.bison.fakeserver.fakeServerModule] so callers in
 * `com.toxicbakery.app.bison.debug` resolve `fakeServerModule` without extra imports.
 */
val fakeServerModule: DI.Module = implFakeServerModule
