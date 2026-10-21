package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KeyAlgorithm

/**
 * Gives the OpenSSH public key of a device key store key, so that the user can add it to
 * `~/.ssh/authorized_keys` on a host. The platform layer supplies the implementation.
 */
interface SshPublicKeyProvider {
    /**
     * Returns the `authorized_keys` line for [keyAlias]. Creates the key if it does not exist.
     * Throws an exception when the key cannot be created or loaded.
     */
    suspend fun authorizedKeyLine(
        keyAlias: String,
        algorithm: KeyAlgorithm,
        requireBiometric: Boolean,
    ): String
}
