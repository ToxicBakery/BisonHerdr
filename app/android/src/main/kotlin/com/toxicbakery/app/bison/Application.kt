package com.toxicbakery.app.bison

import android.content.Context
import com.toxicbakery.app.bison.ssh.LocalNetworkGuard
import com.toxicbakery.app.bison.ssh.androidLocalNetworkGuard
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderAndroid
import com.toxicbakery.app.bison.store.StoreIOProviderTags
import com.toxicbakery.logging.Arbor
import com.toxicbakery.logging.LogCatSeedling
import java.io.File
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

class Application : android.app.Application(), DIAware {

    override val di: DI by DI.lazy {
        bindProvider<Application> { this@Application }
        bindProvider<android.app.Application> { this@Application }
        bindProvider<Context> { this@Application }
        import(appModule)

        // Inject Android-specific StoreIOProvider
        bindSingleton<StoreIOProvider>(overrides = true) {
            StoreIOProviderAndroid(
                context = instance<Application>()
            )
        }
        // Backups do not read noBackupFilesDir. It has the device marker and the encrypted secrets.
        bindSingleton<StoreIOProvider>(tag = StoreIOProviderTags.NO_BACKUP) {
            val application = instance<Application>()
            StoreIOProviderAndroid(directory = { application.noBackupFilesDir })
        }
        bindSingleton<StoreIOProvider>(tag = StoreIOProviderTags.SECRETS) {
            val application = instance<Application>()
            StoreIOProviderAndroid(directory = { File(application.noBackupFilesDir, SECRETS_DIRECTORY) })
        }
        // The guard reads the local network permission and the default network of the device.
        bindSingleton<LocalNetworkGuard> {
            androidLocalNetworkGuard(instance<Application>())
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

        /** The directory of the encrypted secrets in `noBackupFilesDir`. */
        private const val SECRETS_DIRECTORY = "secrets"
    }
}
