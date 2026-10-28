package com.toxicbakery.app.bison.di

import com.toxicbakery.app.bison.ssh.PasswordSecretMigration
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.KnownHostsStore
import com.toxicbakery.app.bison.store.MachineStore
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProviderTags
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

expect val storeIOProviderModule: DI.Module

internal val storeModule = DI.Module("storeModule") {
    bindSingleton {
        val passwordMigration = PasswordSecretMigration(secretStore = instance())
        MachineStore(
            storeIOProvider = instance(),
            json = instance(),
            // The Android app binds this directory. Without it, the store has no device marker.
            noBackupStoreIOProvider = instanceOrNull<StoreIOProvider>(tag = StoreIOProviderTags.NO_BACKUP),
            loadMigration = { machines -> passwordMigration.migrate(machines) },
        )
    }
    bindSingleton {
        AppSettingsStore(
            storeIOProvider = instance(),
            json = instance(),
        )
    }
    bindSingleton {
        KnownHostsStore(
            storeIOProvider = instance(),
            json = instance(),
        )
    }
}
