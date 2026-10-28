package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine

/**
 * Tells if the device has the credential that a machine needs for a login. The app does not keep this state.
 * It calculates it again after each load of the machines.
 *
 * A backup does not copy passwords or device keys. After a restore to a different device, a machine can need a
 * password or a key again.
 */
sealed interface CredentialState {

    /** True when the user must give a password or make a key before a login can work. */
    val needsSignIn: Boolean
        get() = this != Ready

    /** The device has the credential, or the app cannot tell. */
    data object Ready : CredentialState

    /** The machine uses a password, and the [SecretStore] has no password for it. */
    data object PasswordMissing : CredentialState

    /** The machine uses the device key [keyAlias], and the device key store has no key for it. */
    data class KeyMissing(val keyAlias: String) : CredentialState {
        /** The text that tells the user what to do. */
        val message: String
            get() = "The key $keyAlias does not exist. Open the machine settings and copy the public key."
    }
}

/** Calculates the [CredentialState] of a machine. The platform layer supplies the implementation. */
fun interface CredentialChecker {
    suspend fun check(machine: HostMachine): CredentialState
}
