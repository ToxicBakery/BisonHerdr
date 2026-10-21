package com.toxicbakery.app.bison.model

/**
 * The [KeyAlgorithm] values that the device can create in its key store.
 *
 * The platform layer supplies the implementation. For example, Android supports Ed25519 keys
 * only on Android 13 (API 33) and later.
 */
interface KeystoreKeySupport {
    /** The supported algorithms, in [KeyAlgorithm] order. */
    val supportedAlgorithms: List<KeyAlgorithm>

    /** The algorithm for new keys: Ed25519 if supported, else ECDSA P-256, else the first supported algorithm. */
    val defaultAlgorithm: KeyAlgorithm
        get() = listOf(KeyAlgorithm.Ed25519, KeyAlgorithm.EcdsaP256).firstOrNull { it in supportedAlgorithms }
            ?: supportedAlgorithms.first()

    fun isSupported(algorithm: KeyAlgorithm): Boolean = algorithm in supportedAlgorithms

    companion object {
        /** All algorithms. Use it where the platform does not limit the key types. */
        val AllAlgorithms: KeystoreKeySupport = object : KeystoreKeySupport {
            override val supportedAlgorithms: List<KeyAlgorithm> = KeyAlgorithm.entries.toList()
        }
    }
}
