package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProvider
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Default binding for `:core:di` on Android; overridden in `Application.kt` with
 * `StoreIOProviderAndroid(overrides = true)`.
 */
actual val storeIOProviderModule: DI.Module = DI.Module("storeIOProviderModule") {
    bindSingleton<StoreIOProvider> { InMemoryStoreIOProvider() }
}
