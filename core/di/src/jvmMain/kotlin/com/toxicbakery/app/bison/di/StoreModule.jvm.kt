package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderJvm
import org.kodein.di.DI
import org.kodein.di.bindProvider

actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    bindProvider<StoreIOProvider> { StoreIOProviderJvm() }
}
