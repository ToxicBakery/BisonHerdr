package com.toxicbakery.app.bison.ssh

/**
 * The [SecretStore] has no password for the machine [machineName], and no UI was open to ask the user for it. This
 * occurs after a restore from a backup to a different device, because a backup does not copy the passwords. The
 * client stops before it opens a connection. A connect from the app asks for the password.
 */
class MissingSecretException(val machineName: String) : RuntimeException(messageFor(machineName)) {

    companion object {
        /** Returns the text that the debug log and the connect error show for [machineName]. */
        fun messageFor(machineName: String): String =
            "This device does not have the password for $machineName. Connect from the app and type the password."
    }
}
