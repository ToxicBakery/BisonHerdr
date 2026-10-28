package com.toxicbakery.app.bison.ssh

/**
 * The key store has no key for [keyAlias]. A login does not make a key, because the host does not know
 * a new key. The user makes the key in the machine settings and adds its public key to the host.
 *
 * It is a [RuntimeException], because JSch lets a [RuntimeException] from an `Identity` go through
 * `Session.connect`. JSch changes a checked exception to a general "Auth fail" error.
 */
class MissingKeyException(val keyAlias: String) : RuntimeException(messageFor(keyAlias)) {

    companion object {
        /** Returns the text that the debug log and the connect error show for the missing [keyAlias]. */
        fun messageFor(keyAlias: String): String =
            "The key $keyAlias does not exist. Open the machine settings and copy the public key."
    }
}
