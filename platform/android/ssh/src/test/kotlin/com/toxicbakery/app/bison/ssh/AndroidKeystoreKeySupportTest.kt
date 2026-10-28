package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KeyAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class AndroidKeystoreKeySupportTest {
    @Test
    fun ed25519NeedsAndroid13() {
        assertEquals(listOf(KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256), AndroidKeystoreKeySupport.supportedAlgorithmsFor(31))
        assertEquals(listOf(KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256), AndroidKeystoreKeySupport.supportedAlgorithmsFor(32))
        assertEquals(KeyAlgorithm.entries.toList(), AndroidKeystoreKeySupport.supportedAlgorithmsFor(33))
        assertEquals(KeyAlgorithm.entries.toList(), AndroidKeystoreKeySupport.supportedAlgorithmsFor(36))
    }

    @Test
    fun authorizedKeyLineIsAnOpenSshPublicKey() = runTest {
        val manager = AndroidKeystoreKeyManager(allowSoftwareKeys = true)
        val line = manager.authorizedKeyLine("jvm_test_ecdsa", KeyAlgorithm.EcdsaP256, requireBiometric = false)
        assertEquals("ecdsa-sha2-nistp256", line.substringBefore(' '))
        assertEquals(line, manager.getOrCreatePublicKey("jvm_test_ecdsa", KeyAlgorithm.EcdsaP256).openSshAuthorizedKeyLine)
        manager.deleteKey("jvm_test_ecdsa")
    }
}
