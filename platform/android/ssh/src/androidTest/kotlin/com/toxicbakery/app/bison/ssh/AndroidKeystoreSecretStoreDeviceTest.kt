package com.toxicbakery.app.bison.ssh

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import java.security.KeyStore
import javax.crypto.SecretKeyFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for [AndroidKeystoreSecretStore] with its AES-256-GCM key in AndroidKeyStore.
 * The JVM tests use the same code with a software key. These tests do not need the E2E harness.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreDeviceTest {

    private val io = InMemoryStoreIOProvider()
    private val store = AndroidKeystoreSecretStore(io)

    @After
    fun deleteTestKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(AndroidKeystoreSecretKeySource.KEY_ALIAS)
    }

    @Test
    fun aSecretMakesARoundTripWithTheKeystoreKey() = runBlocking {
        store.put(REF, PASSWORD.encodeToByteArray())

        assertFalse(PASSWORD in assertNotNull(io.textAt(REF)))
        assertContentEquals(PASSWORD.encodeToByteArray(), store.get(REF))
        assertNull(store.get("password-absent"))
    }

    @Test
    fun theKeyHasTheSpecOfThePlan() = runBlocking {
        store.put(REF, PASSWORD.encodeToByteArray())
        val key = assertNotNull(AndroidKeystoreSecretKeySource.existingKey())
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertEquals(KeyProperties.KEY_ALGORITHM_AES, key.algorithm)
        assertEquals(256, info.keySize)
        assertTrue(KeyProperties.BLOCK_MODE_GCM in info.blockModes)
        assertTrue(KeyProperties.ENCRYPTION_PADDING_NONE in info.encryptionPaddings)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertFalse(info.isUserAuthenticationRequired)
    }

    @Test
    fun aSecretDoesNotDecryptAfterTheKeyIsGone() = runBlocking {
        store.put(REF, PASSWORD.encodeToByteArray())
        deleteTestKey()

        assertNull(store.get(REF))
        assertNull(AndroidKeystoreSecretKeySource.existingKey(), "A get made a new key")
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val REF = "password-device-test"
    }
}
