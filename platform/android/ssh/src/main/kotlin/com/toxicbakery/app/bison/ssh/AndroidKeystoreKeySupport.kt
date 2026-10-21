package com.toxicbakery.app.bison.ssh

import android.os.Build
import com.toxicbakery.app.bison.model.KeyAlgorithm
import com.toxicbakery.app.bison.model.KeystoreKeySupport

/**
 * The SSH key types that [AndroidKeystoreKeyManager] can create on this device. AndroidKeyStore
 * supports Ed25519 only on Android 13 (API 33) and later. Android 12 also has no software Ed25519.
 */
object AndroidKeystoreKeySupport : KeystoreKeySupport {
    override val supportedAlgorithms: List<KeyAlgorithm> by lazy { supportedAlgorithmsFor(Build.VERSION.SDK_INT) }

    internal fun supportedAlgorithmsFor(sdkInt: Int): List<KeyAlgorithm> =
        KeyAlgorithm.entries.filter { algorithm ->
            algorithm != KeyAlgorithm.Ed25519 || sdkInt >= Build.VERSION_CODES.TIRAMISU
        }
}
