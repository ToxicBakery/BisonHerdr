package com.toxicbakery.app.bison.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val storeIoDispatcher: CoroutineDispatcher = Dispatchers.IO
