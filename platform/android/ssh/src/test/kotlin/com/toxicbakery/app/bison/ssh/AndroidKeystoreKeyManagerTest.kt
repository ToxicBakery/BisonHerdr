package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPair
import java.security.ProviderException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Tests that [AndroidKeystoreKeyManager] makes no software key on a device, and that only
 * [AndroidKeystoreKeyManager.getOrCreatePublicKey] makes a key.
 */
class AndroidKeystoreKeyManagerTest {

    /** A key store generator that always fails with [error] and counts its calls. */
    private class FailingKeystore(private val error: Exception) : KeystoreKeyGenerator {
        var calls = 0
            private set

        override fun generate(keyAlias: String, algorithm: KeyAlgorithm, requireBiometric: Boolean): KeyPair {
            calls++
            throw error
        }
    }

    private fun manager(
        keystore: KeystoreKeyGenerator,
        allowSoftwareKeys: Boolean = false,
        supportedAlgorithms: List<KeyAlgorithm> = KeyAlgorithm.entries.toList(),
    ) = AndroidKeystoreKeyManager(allowSoftwareKeys = allowSoftwareKeys).apply {
        keystoreKeyGenerator = keystore
        keySupport = object : KeystoreKeySupport {
            override val supportedAlgorithms: List<KeyAlgorithm> = supportedAlgorithms
        }
    }

    @Test
    fun aFailedKeystoreThrowsAndMakesNoSoftwareKey() {
        val keystoreError = ProviderException("Keystore operation failed")
        val manager = manager(FailingKeystore(keystoreError))

        val error = assertFailsWith<KeyCreationException> {
            manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256)
        }

        assertEquals(KeyCreationFailure.Other("Keystore operation failed"), error.failure)
        assertContains(error.message.orEmpty(), KEY_ALIAS)
        assertSame(keystoreError, error.cause)
        assertFailsWith<MissingKeyException> { manager.getExistingPublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256) }
    }

    @Test
    fun theDefaultManagerMakesNoSoftwareKeyOnTheJvm() {
        // The JVM has no AndroidKeyStore, so the real key store generator fails here.
        val manager = AndroidKeystoreKeyManager()

        assertFailsWith<KeyCreationException> { manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256) }
        assertFailsWith<MissingKeyException> { manager.getExistingPublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256) }
    }

    @Test
    fun aKeyThatNeedsAuthenticationOnADeviceWithNoScreenLockNamesTheCause() {
        // AndroidKeyStore wraps this IllegalStateException when the device has no secure screen lock.
        val keystoreError = InvalidAlgorithmParameterException(
            IllegalStateException("Secure lock screen must be enabled to create keys requiring user authentication"),
        )
        val manager = manager(FailingKeystore(keystoreError))

        val error = assertFailsWith<KeyCreationException> {
            manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)
        }

        assertEquals(KeyCreationFailure.NoSecureLockScreen, error.failure)
        assertContains(error.message.orEmpty(), "secure screen lock")
    }

    @Test
    fun aKeyTypeThatTheDeviceDoesNotSupportNamesTheCause() {
        val manager = manager(
            keystore = FailingKeystore(InvalidAlgorithmParameterException("Unsupported EC curve name: ed25519")),
            supportedAlgorithms = listOf(KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256),
        )

        val error = assertFailsWith<KeyCreationException> {
            manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.Ed25519)
        }

        assertEquals(KeyCreationFailure.AlgorithmNotSupported(KeyAlgorithm.Ed25519), error.failure)
        assertContains(error.message.orEmpty(), "Android 13")
    }

    @Test
    fun softwareKeysAreMadeOnlyWhenTheyAreAllowed() {
        val manager = manager(FailingKeystore(ProviderException("No AndroidKeyStore")), allowSoftwareKeys = true)

        val exported = manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256)

        assertContentEquals(exported.wireBlob, manager.getExistingPublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256).wireBlob)
    }

    @Test
    fun aSignWithAnUnknownAliasThrowsAndMakesNoKey() {
        // The manager can make a software key, so a sign that tries to make a key does not fail.
        val keystore = FailingKeystore(ProviderException("No AndroidKeyStore"))
        val manager = manager(keystore, allowSoftwareKeys = true)

        val error = assertFailsWith<MissingKeyException> {
            manager.signSshChallenge(UNKNOWN_ALIAS, KeyAlgorithm.Ed25519, CHALLENGE)
        }

        assertEquals(UNKNOWN_ALIAS, error.keyAlias)
        assertEquals(MissingKeyException.messageFor(UNKNOWN_ALIAS), error.message)
        assertEquals(0, keystore.calls)
        assertFailsWith<MissingKeyException> { manager.getExistingPublicKey(UNKNOWN_ALIAS, KeyAlgorithm.Ed25519) }
    }

    @Test
    fun theJschIdentityOfAnUnknownAliasThrowsAndMakesNoKey() {
        val keystore = FailingKeystore(ProviderException("No AndroidKeyStore"))
        val manager = manager(keystore, allowSoftwareKeys = true)
        val identity = AndroidKeystoreJschIdentity(manager, UNKNOWN_ALIAS, KeyAlgorithm.Ed25519)

        assertIs<MissingKeyException>(runCatching { identity.publicKeyBlob }.exceptionOrNull())
        assertIs<MissingKeyException>(runCatching { identity.getSignature(CHALLENGE, "ssh-ed25519") }.exceptionOrNull())
        assertEquals(0, keystore.calls)
    }

    private companion object {
        const val KEY_ALIAS = "manager_test_key"
        const val UNKNOWN_ALIAS = "manager_test_unknown"
        val CHALLENGE = "ssh-userauth-challenge".toByteArray()
    }
}
