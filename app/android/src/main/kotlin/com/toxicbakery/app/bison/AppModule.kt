package com.toxicbakery.app.bison

import com.toxicbakery.app.bison.debug.debugModule
import com.toxicbakery.app.bison.di.coreDiModule
import com.toxicbakery.app.bison.di.platformModule
import com.toxicbakery.app.bison.screen.screenModule
import org.kodein.di.DI

internal val appModule = DI.Module("appModule") {
    import(coreDiModule)
    import(platformModule)
    import(screenModule)
    import(debugModule)
}
