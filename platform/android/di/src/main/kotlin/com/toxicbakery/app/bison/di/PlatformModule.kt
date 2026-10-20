package com.toxicbakery.app.bison.di

import org.kodein.di.DI

val platformModule = DI.Module("platformModule") {
    import(sshModule)
}
