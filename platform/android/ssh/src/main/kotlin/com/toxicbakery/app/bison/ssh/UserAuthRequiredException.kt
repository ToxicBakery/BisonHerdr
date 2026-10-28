package com.toxicbakery.app.bison.ssh

/**
 * The key [keyAlias] needs a user authentication before it can sign, and AndroidKeyStore refused the sign.
 * [cause] is the `UserNotAuthenticatedException` of AndroidKeyStore. The sign does not try again: only the user
 * can unlock the key, with a strong biometric or the device screen lock.
 *
 * It is a [RuntimeException], because JSch lets a [RuntimeException] from an `Identity` go through
 * `Session.connect`. JSch changes a checked exception, for example `UserNotAuthenticatedException`, to a
 * general "Auth fail" error with no cause.
 */
class UserAuthRequiredException(
    val keyAlias: String,
    cause: Throwable? = null,
) : RuntimeException(messageFor(keyAlias), cause) {

    companion object {
        /** Returns the text that the debug log shows when [keyAlias] needs a user authentication. */
        fun messageFor(keyAlias: String): String =
            "The key $keyAlias needs a user authentication with a biometric or the screen lock before it can sign."
    }
}
