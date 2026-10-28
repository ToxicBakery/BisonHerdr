package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KeyAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Tests that only [AndroidKeystoreKeyManager.getOrCreatePublicKey] makes a key.
 */
class AndroidKeystoreKeyManagerTest {

    @Test
    fun aSignWithAnUnknownAliasThrowsAndMakesNoKey() {
        // On the JVM the manager makes a software key, so a sign that tries to make a key does not fail.
        val manager = AndroidKeystoreKeyManager()

        val error = assertFailsWith<MissingKeyException> {
            manager.signSshChallenge(UNKNOWN_ALIAS, KeyAlgorithm.Ed25519, CHALLENGE)
        }

        assertEquals(UNKNOWN_ALIAS, error.keyAlias)
        assertEquals(MissingKeyException.messageFor(UNKNOWN_ALIAS), error.message)
        assertFailsWith<MissingKeyException> { manager.getExistingPublicKey(UNKNOWN_ALIAS, KeyAlgorithm.Ed25519) }
    }

    @Test
    fun theJschIdentityOfAnUnknownAliasThrowsAndMakesNoKey() {
        val manager = AndroidKeystoreKeyManager()
        val identity = AndroidKeystoreJschIdentity(manager, UNKNOWN_ALIAS, KeyAlgorithm.Ed25519)

        assertIs<MissingKeyException>(runCatching { identity.publicKeyBlob }.exceptionOrNull())
        assertIs<MissingKeyException>(runCatching { identity.getSignature(CHALLENGE, "ssh-ed25519") }.exceptionOrNull())
        assertFailsWith<MissingKeyException> { manager.getExistingPublicKey(UNKNOWN_ALIAS, KeyAlgorithm.Ed25519) }
    }

    private companion object {
        const val UNKNOWN_ALIAS = "manager_test_unknown"
        val CHALLENGE = "ssh-userauth-challenge".toByteArray()
    }
}
