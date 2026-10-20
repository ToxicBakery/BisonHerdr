package com.toxicbakery.app.bison.store

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Default dispatcher for blocking file IO in [MachineStore] and [AppSettingsStore].
 */
expect val storeIoDispatcher: CoroutineDispatcher
