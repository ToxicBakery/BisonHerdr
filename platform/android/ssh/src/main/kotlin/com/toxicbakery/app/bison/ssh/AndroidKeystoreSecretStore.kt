package com.toxicbakery.app.bison.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.toxicbakery.app.bison.store.StoreIOProvider
import com.toxicbakery.logging.Arbor
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [SecretStore] of the app. It encrypts each secret with an AES-256-GCM key in AndroidKeyStore (alias
 * [AndroidKeystoreSecretKeySource.KEY_ALIAS]). The key never leaves the device.
 *
 * Give it a [StoreIOProvider] for `Context.getNoBackupFilesDir()/secrets`. Auto Backup does not read that
 * directory. A secret from a backup cannot decrypt on a different device, because the key stays on the old device.
 */
class AndroidKeystoreSecretStore(
    storeIOProvider: StoreIOProvider,
) : SecretStore by AesGcmSecretStore(storeIOProvider, AndroidKeystoreSecretKeySource)

/** Gives the AES key of an [AesGcmSecretStore]. */
internal interface SecretKeySource {

    /** Returns the key, or null when the key does not exist. It does not make a key. */
    fun existingKey(): SecretKey?

    /** Returns the key. It makes the key when the key does not exist. */
    fun getOrCreateKey(): SecretKey
}

/**
 * The AES-256-GCM key of the [AndroidKeystoreSecretStore] in AndroidKeyStore.
 *
 * The key does not need user authentication or an unlocked device, because the session service connects again
 * while the screen is locked. `setUnlockedDeviceRequired` also has known defects on Android 12 to 14. The key
 * does not use StrongBox, because many devices do not have it.
 */
internal object AndroidKeystoreSecretKeySource : SecretKeySource {

    /** The alias of the key in AndroidKeyStore. */
    const val KEY_ALIAS: String = "bison_secret_store_v1"

    private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_SIZE_BITS = 256
    private val keyLock = Any()

    override fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    override fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
            .apply { init(keySpec()) }
            .generateKey()
    }

    private fun keySpec(): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
}

/**
 * A [SecretStore] that encrypts each secret with `AES/GCM/NoPadding` and the key of [keySource].
 *
 * - The cipher makes a new 12-byte IV for each [put].
 * - The reference is the additional authenticated data, so the secret of one reference does not decrypt as the
 *   secret of a different reference.
 * - Each reference has one file in [storeIOProvider], with the reference as the file name. The file has the
 *   Base64 text of `iv || ciphertext`. The ciphertext includes the 16-byte GCM tag. [StoreIOProvider] writes
 *   text, so the file has Base64 text and not the raw bytes.
 */
internal class AesGcmSecretStore(
    private val storeIOProvider: StoreIOProvider,
    private val keySource: SecretKeySource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {

    override suspend fun put(ref: String, secret: ByteArray) {
        require(SecretRef.isValid(ref)) { "The secret reference is not valid." }
        withContext(ioDispatcher) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keySource.getOrCreateKey())
            cipher.updateAAD(ref.encodeToByteArray())
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "The cipher made an IV of ${iv.size} bytes." }
            val ciphertext = cipher.doFinal(secret)
            storeIOProvider.writeAtomic(ref, Base64.getEncoder().encodeToString(iv + ciphertext))
        }
    }

    override suspend fun get(ref: String): ByteArray? {
        if (!SecretRef.isValid(ref)) return null
        return withContext(ioDispatcher) { decrypt(ref) }
    }

    override suspend fun remove(ref: String) {
        if (!SecretRef.isValid(ref)) return
        withContext(ioDispatcher) { storeIOProvider.delete(ref) }
    }

    private fun decrypt(ref: String): ByteArray? {
        val text = storeIOProvider.readText(ref) ?: return null
        val payload = try {
            Base64.getDecoder().decode(text.trim())
        } catch (_: IllegalArgumentException) {
            log.w { "The secret file $ref does not have Base64 text." }
            return null
        }
        if (payload.size < IV_BYTES + TAG_BYTES) {
            log.w { "The secret file $ref is too short." }
            return null
        }
        val key = keySource.existingKey()
        if (key == null) {
            log.w { "The secret key does not exist, so the secret $ref cannot decrypt." }
            return null
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, payload, 0, IV_BYTES))
        cipher.updateAAD(ref.encodeToByteArray())
        return try {
            cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
        } catch (_: AEADBadTagException) {
            log.w { "The secret $ref does not decrypt with the key of this device." }
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16

        val log = Arbor.tag("AesGcmSecretStore")
    }
}
