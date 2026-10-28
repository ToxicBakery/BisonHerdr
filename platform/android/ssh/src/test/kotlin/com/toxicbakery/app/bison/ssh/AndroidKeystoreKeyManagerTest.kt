package com.toxicbakery.app.bison.ssh

import android.security.keystore.UserNotAuthenticatedException
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPair
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    /** A JCA signature whose init throws [error], as AndroidKeyStore does for a locked key. */
    private class FailingSignature(private val error: Exception) : Signature("failing") {
        override fun engineInitSign(privateKey: PrivateKey?) {
            throw error
        }

        override fun engineInitVerify(publicKey: PublicKey?) {
            throw UnsupportedOperationException()
        }

        override fun engineUpdate(b: Byte) = Unit

        override fun engineUpdate(b: ByteArray?, off: Int, len: Int) = Unit

        override fun engineSign(): ByteArray = throw UnsupportedOperationException()

        override fun engineVerify(sigBytes: ByteArray?): Boolean = false

        @Deprecated("Deprecated in SignatureSpi")
        override fun engineSetParameter(param: String?, value: Any?) = Unit

        @Deprecated("Deprecated in SignatureSpi")
        override fun engineGetParameter(param: String?): Any? = null
    }

    private fun manager(
        keystore: KeystoreKeyGenerator,
        allowSoftwareKeys: Boolean = false,
        supportedAlgorithms: List<KeyAlgorithm> = KeyAlgorithm.entries.toList(),
        signatureFactory: (String) -> Signature = { Signature.getInstance(it) },
    ) = AndroidKeystoreKeyManager(
        allowSoftwareKeys = allowSoftwareKeys,
        keystoreKeyGenerator = keystore,
        keySupport = object : KeystoreKeySupport {
            override val supportedAlgorithms: List<KeyAlgorithm> = supportedAlgorithms
        },
        signatureFactory = signatureFactory,
    )

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

    @Test
    fun aSignWithALockedKeyThrowsUserAuthRequiredAndDoesNotTryAgain() {
        var signatures = 0
        val locked = UserNotAuthenticatedException()
        val manager = manager(
            keystore = FailingKeystore(ProviderException("No AndroidKeyStore")),
            allowSoftwareKeys = true,
            signatureFactory = {
                signatures++
                FailingSignature(locked)
            },
        )
        manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)

        val error = assertFailsWith<UserAuthRequiredException> {
            manager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE)
        }

        assertEquals(KEY_ALIAS, error.keyAlias)
        assertSame(locked, error.cause)
        assertEquals(1, signatures)
    }

    @Test
    fun otherSignErrorsAreNotChanged() {
        val signError = SignatureException("Keystore operation failed")
        val manager = manager(
            keystore = FailingKeystore(ProviderException("No AndroidKeyStore")),
            allowSoftwareKeys = true,
            signatureFactory = { FailingSignature(signError) },
        )
        manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256)

        val error = assertFailsWith<SignatureException> {
            manager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE)
        }

        assertSame(signError, error)
    }

    @Test
    fun regenerateKeyReplacesTheKeyAndTheNewKeySigns() {
        val manager = manager(FailingKeystore(ProviderException("No AndroidKeyStore")), allowSoftwareKeys = true)
        val oldKey = manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256)

        val newKey = manager.regenerateKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)

        assertNotEquals(oldKey.openSshAuthorizedKeyLine, newKey.openSshAuthorizedKeyLine)
        assertEquals(newKey, manager.getExistingPublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256))
        assertEquals(newKey, manager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256))
        assertTrue(manager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE).isNotEmpty())
    }

    @Test
    fun regenerateKeyAsksTheKeyStoreWithTheNewParameters() {
        val requests = mutableListOf<Triple<String, KeyAlgorithm, Boolean>>()
        val manager = manager(
            keystore = { alias, algorithm, requireBiometric ->
                requests += Triple(alias, algorithm, requireBiometric)
                throw InvalidAlgorithmParameterException(IllegalStateException("No secure lock screen"))
            },
        )

        val error = assertFailsWith<KeyCreationException> {
            manager.regenerateKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256, requireBiometric = true)
        }

        assertEquals(KeyCreationFailure.NoSecureLockScreen, error.failure)
        assertEquals(listOf(Triple(KEY_ALIAS, KeyAlgorithm.EcdsaP256, true)), requests)
    }

    private companion object {
        const val KEY_ALIAS = "manager_test_key"
        const val UNKNOWN_ALIAS = "manager_test_unknown"
        val CHALLENGE = "ssh-userauth-challenge".toByteArray()
    }
}
