package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.store.appJson
import org.kodein.di.DI
import org.kodein.di.bindSingleton

val coreDiModule = DI.Module("coreDiModule") {
    import(sshCoreModule)
    import(storeIOProviderModule)
    import(storeModule)
    import(viewModelModule)

    // App-wide Json singleton configured with ignoreUnknownKeys = true and coerceInputValues = true
    // so new optional server fields or unknown enum values in Endpoint Gen 1 never fail deserialization.
    bindSingleton { appJson }
}
