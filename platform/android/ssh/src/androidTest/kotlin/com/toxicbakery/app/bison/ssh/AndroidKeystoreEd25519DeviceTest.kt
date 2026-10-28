package com.toxicbakery.app.bison.ssh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.toxicbakery.app.bison.model.KeyAlgorithm
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests for Ed25519 SSH keys in AndroidKeyStore (API 33+) and for `ssh-ed25519`
 * signature checks on the device. These tests do not need the E2E harness.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreEd25519DeviceTest {

    private val keyManager = AndroidKeystoreKeyManager()

    @After
    fun deleteTestKey() {
        AndroidKeystoreKeyManager().deleteKey(KEY_ALIAS)
    }

    /** Reads the raw 32-byte key from an SSH `ssh-ed25519` public key blob. */
    private fun rawEd25519PublicKey(wireBlob: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(wireBlob)
        val name = ByteArray(buffer.int).also { buffer.get(it) }
        assertEquals("ssh-ed25519", name.decodeToString())
        val key = ByteArray(buffer.int).also { buffer.get(it) }
        assertEquals(0, buffer.remaining())
        return key
    }

    private fun verifiesWithBouncyCastle(publicKey: ByteArray, message: ByteArray, sshSignature: ByteArray): Boolean =
        AndroidJschSignatureEd25519().run {
            init()
            setPubKey(publicKey)
            update(message)
            verify(sshSignature)
        }

    private fun storedKeyAlgorithm(): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null).algorithm
    }

    @Test
    fun newEd25519KeySignsAndTheExportedPublicKeyVerifies() {
        assumeTrue("AndroidKeyStore supports Ed25519 from API 33", Build.VERSION.SDK_INT >= 33)
        keyManager.deleteKey(KEY_ALIAS)

        val exported = keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.Ed25519)
        assertTrue(exported.openSshAuthorizedKeyLine.startsWith("ssh-ed25519 "))
        val publicKey = rawEd25519PublicKey(exported.wireBlob)

        val signature = keyManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.Ed25519, CHALLENGE, "ssh-ed25519")
        assertTrue(verifiesWithBouncyCastle(publicKey, CHALLENGE, signature), "The signature does not verify")
        assertEquals("EdDSA", storedKeyAlgorithm())
    }

    @Test
    fun brokenLegacyKeyIsReplacedWithAWorkingEd25519Key() {
        assumeTrue("AndroidKeyStore supports Ed25519 from API 33", Build.VERSION.SDK_INT >= 33)
        keyManager.deleteKey(KEY_ALIAS)

        // Make the key the way that earlier versions did. AndroidKeyStore makes a P-256 EC key.
        KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build(),
            )
            generateKeyPair()
        }
        assertEquals(KeyProperties.KEY_ALGORITHM_EC, storedKeyAlgorithm())

        val freshManager = AndroidKeystoreKeyManager()
        val publicKey = rawEd25519PublicKey(freshManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.Ed25519).wireBlob)
        val signature = freshManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.Ed25519, CHALLENGE, "ssh-ed25519")

        assertEquals("EdDSA", storedKeyAlgorithm())
        assertTrue(verifiesWithBouncyCastle(publicKey, CHALLENGE, signature), "The signature does not verify")
    }

    @Test
    fun workingEcdsaKeyWithTheSameAliasIsNotReplaced() {
        keyManager.deleteKey(KEY_ALIAS)
        val ecdsaLine = keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256).openSshAuthorizedKeyLine

        val error = runCatching { AndroidKeystoreKeyManager().getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.Ed25519) }
            .exceptionOrNull()
        assertIs<IllegalStateException>(error)
        assertEquals(KeyProperties.KEY_ALGORITHM_EC, storedKeyAlgorithm())
        assertEquals(ecdsaLine, AndroidKeystoreKeyManager().getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256).openSshAuthorizedKeyLine)
    }

    @Test
    fun ed25519IsUnavailableBeforeAndroid13AndEcdsaWorks() {
        assumeTrue("This test is for Android 12", Build.VERSION.SDK_INT < 33)
        keyManager.deleteKey(KEY_ALIAS)
        assertEquals(false, AndroidKeystoreKeySupport.isSupported(KeyAlgorithm.Ed25519))
        assertEquals(KeyAlgorithm.EcdsaP256, AndroidKeystoreKeySupport.defaultAlgorithm)

        val error = runCatching { keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.Ed25519) }.exceptionOrNull()
        assertIs<KeyCreationException>(error)
        assertEquals(KeyCreationFailure.AlgorithmNotSupported(KeyAlgorithm.Ed25519), error.failure)
        assertTrue(error.message.orEmpty().contains("Android 13"), "Unclear error: ${error.message}")

        val exported = keyManager.getOrCreatePublicKey(KEY_ALIAS, KeyAlgorithm.EcdsaP256)
        assertTrue(exported.openSshAuthorizedKeyLine.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(keyManager.signSshChallenge(KEY_ALIAS, KeyAlgorithm.EcdsaP256, CHALLENGE).isNotEmpty())
    }

    @Test
    fun hostKeySignatureVerifiesOnTheDevice() {
        // RFC 8032 section 7.1, TEST 2. Android 12 to 15 cannot do this through a JCA KeyFactory.
        val publicKey = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val signature = hex(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        )
        assertTrue(verifiesWithBouncyCastle(publicKey, byteArrayOf(0x72), signature))
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val KEY_ALIAS = "bison_device_test_ed25519"
        val CHALLENGE = "ssh-userauth-device-test".encodeToByteArray()
    }
}
