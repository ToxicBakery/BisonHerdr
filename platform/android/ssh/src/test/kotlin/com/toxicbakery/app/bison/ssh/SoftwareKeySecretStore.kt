package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.StoreIOProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers

/**
 * A software AES-256 key for JVM tests. The JVM has no AndroidKeyStore, so the tests use this key with the same
 * [AesGcmSecretStore] code and file format as [AndroidKeystoreSecretStore].
 */
internal class SoftwareSecretKeySource(private var key: SecretKey? = null) : SecretKeySource {

    override fun existingKey(): SecretKey? = key

    override fun getOrCreateKey(): SecretKey = synchronized(this) {
        key ?: KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey().also { key = it }
    }

    private companion object {
        const val KEY_SIZE_BITS = 256
    }
}

/**
 * Returns a [SecretStore] for JVM tests: [AesGcmSecretStore] with a software key in [keySource], over [io].
 */
internal fun softwareKeySecretStore(
    io: StoreIOProvider = InMemoryStoreIOProvider(),
    keySource: SecretKeySource = SoftwareSecretKeySource(),
): SecretStore = AesGcmSecretStore(io, keySource, Dispatchers.Unconfined)
