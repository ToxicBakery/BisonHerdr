package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.MachineStore
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

expect val storeIOProviderModule: DI.Module

internal val storeModule = DI.Module("storeModule") {
    bindSingleton {
        MachineStore(
            storeIOProvider = instance(),
            json = instance(),
        )
    }
    bindSingleton {
        AppSettingsStore(
            storeIOProvider = instance(),
            json = instance(),
        )
    }
}
