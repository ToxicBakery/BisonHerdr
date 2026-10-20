package com.toxicbakery.app.bison

import android.content.Context
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderAndroid
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.LogCatSeedling
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bindProvider
import org.kodein.di.instance

class Application : android.app.Application(), DIAware {

    override val di: DI by DI.lazy {
        bindProvider<Application> { this@Application }
        bindProvider<android.app.Application> { this@Application }
        bindProvider<Context> { this@Application }
        import(appModule)

        // Inject Android-specific StoreIOProvider
        bindProvider<StoreIOProvider>(overrides = true) {
            StoreIOProviderAndroid(
                context = instance<Application>()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Arbor.sow(LogCatSeedling())
        }
        log.d { "BisonHerdr Application started" }
    }

    companion object {
        private val log = Arbor.tag("Application")
    }
}
