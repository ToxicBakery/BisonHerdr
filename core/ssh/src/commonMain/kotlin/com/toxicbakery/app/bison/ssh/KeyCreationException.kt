package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KeyAlgorithm

/** The cause when the device key store does not make a key. */
sealed interface KeyCreationFailure {

    /**
     * The key needs user authentication, but the device has no secure screen lock. A PIN, a pattern, or a
     * password is sufficient. A biometric is not necessary.
     */
    data object NoSecureLockScreen : KeyCreationFailure

    /** The key store of the device does not support [algorithm]. For example, Ed25519 needs Android 13. */
    data class AlgorithmNotSupported(val algorithm: KeyAlgorithm) : KeyCreationFailure

    /** A different key store error. [detail] is the error text from the key store. */
    data class Other(val detail: String) : KeyCreationFailure
}

/**
 * The device key store did not make a key. [failure] tells the cause, so that a screen can show a
 * clear message.
 */
class KeyCreationException(
    val failure: KeyCreationFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
