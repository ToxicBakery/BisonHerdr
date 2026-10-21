package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KeyAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidKeystoreKeySupportTest {
    @Test
    fun ed25519NeedsAndroid13() {
        assertEquals(listOf(KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256), AndroidKeystoreKeySupport.supportedAlgorithmsFor(31))
        assertEquals(listOf(KeyAlgorithm.Rsa4096, KeyAlgorithm.EcdsaP256), AndroidKeystoreKeySupport.supportedAlgorithmsFor(32))
        assertEquals(KeyAlgorithm.entries.toList(), AndroidKeystoreKeySupport.supportedAlgorithmsFor(33))
        assertEquals(KeyAlgorithm.entries.toList(), AndroidKeystoreKeySupport.supportedAlgorithmsFor(36))
    }
}
