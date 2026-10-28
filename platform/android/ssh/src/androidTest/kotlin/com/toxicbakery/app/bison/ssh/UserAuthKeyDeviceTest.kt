package com.toxicbakery.app.bison.ssh

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.toxicbakery.app.bison.model.KeyAlgorithm
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for the parameters of keys that need a user authentication (`requireBiometric`).
 *
 * The tests that make such a key need a device with a secure screen lock. They do not unlock the key, so
 * a sign gives a signature only when the user unlocked the device in the last 30 s. The app test
 * `UserAuthPromptDeviceTest` signs after a successful prompt.
 */
@RunWith(AndroidJUnit4::class)
class UserAuthKeyDeviceTest {

    private val keyManager = AndroidKeystoreKeyManager()

    @After
    fun deleteTestKey() {
        keyManager.deleteKey(KEY_ALIAS)
    }

    @Test
    fun aBiometricKeyCanSignForAShortTimeAfterABiometricOrTheScreenLock() {
        assumeSecureScreenLock()
        keyManager.deleteKey(KEY_ALIAS)

        keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)

        val keyInfo = storedKeyInfo()
        assertTrue(keyInfo.isUserAuthenticationRequired)
        assertEquals(AndroidKeystoreKeyManager.USER_AUTH_VALIDITY_SECONDS, keyInfo.userAuthenticationValidityDurationSeconds)
        assertEquals(AndroidKeystoreKeyManager.USER_AUTH_TYPES, keyInfo.userAuthenticationType)
        assertSignsOrNeedsTheUser()
    }

    @Test
    fun aKeyWithoutRequireBiometricNeedsNoUserAuthentication() {
        keyManager.deleteKey(KEY_ALIAS)

        keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = false)

        assertFalse(storedKeyInfo().isUserAuthenticationRequired)
        assertTrue(keyManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE).isNotEmpty())
    }

    @Test
    fun regenerateKeyReplacesAKeyFromAnEarlierVersion() {
        assumeSecureScreenLock()
        keyManager.deleteKey(KEY_ALIAS)
        // Earlier versions made a key that needs a user authentication for each sign. Such a key needs a
        // CryptoObject, which the JSch thread cannot give.
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .build(),
            )
            generateKeyPair()
        }
        val oldLine = AndroidKeystoreKeyManager()
            .getExistingPublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256)
            .openSshAuthorizedKeyLine

        val newLine = keyManager.regenerateKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)
            .openSshAuthorizedKeyLine

        assertNotEquals(oldLine, newLine)
        assertEquals(AndroidKeystoreKeyManager.USER_AUTH_VALIDITY_SECONDS, storedKeyInfo().userAuthenticationValidityDurationSeconds)
        assertEquals(newLine, AndroidKeystoreKeyManager().getExistingPublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256).openSshAuthorizedKeyLine)
        assertSignsOrNeedsTheUser()
    }

    /** A sign gives a signature, or a [UserAuthRequiredException] when the key is locked. It never gives a different error. */
    private fun assertSignsOrNeedsTheUser() {
        val result = runCatching { keyManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE) }
        val error = result.exceptionOrNull()
        if (error == null) {
            assertTrue(result.getOrThrow().isNotEmpty())
        } else {
            assertEquals(KEY_ALIAS, assertIs<UserAuthRequiredException>(error, "Unexpected sign error: $error").keyAlias)
        }
    }

    private fun assumeSecureScreenLock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assumeTrue("A key that needs a user authentication needs a secure screen lock", keyguard.isDeviceSecure)
    }

    private fun storedKeyInfo(): KeyInfo {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        return KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore").getKeySpec(privateKey, KeyInfo::class.java)
    }

    private companion object {
        const val KEY_ALIAS = "bison_device_test_user_auth"
        val CHALLENGE = "ssh-userauth-device-test".encodeToByteArray()
    }
}
