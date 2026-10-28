package com.toxicbakery.app.bison.ssh

/**
 * The key [keyAlias] did not sign. [cause] is the checked error of the key store, for example a
 * `SignatureException` with a `KeyStoreException`.
 *
 * It is a [RuntimeException], because JSch lets a [RuntimeException] from an `Identity` go through
 * `Session.connect`. JSch changes a checked exception to a general "Auth fail" error with no cause.
 */
class KeySignException(
    val keyAlias: String,
    cause: Throwable,
) : RuntimeException("The key $keyAlias did not sign: ${cause.message ?: cause::class.java.name}", cause)
