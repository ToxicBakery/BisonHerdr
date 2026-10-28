package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KnownHost

/**
 * The result of [SshBackend.authenticate]. Each failure tells its kind, so that a caller can retry
 * only a [NetworkError].
 */
sealed interface SshAuthResult {

    /** The login passed. */
    data object Success : SshAuthResult

    /** The server refused the credentials. A retry with the same credentials fails again. */
    data class AuthFailed(val message: String) : SshAuthResult

    /** The client did not get a connection to the server, or the connection closed. [cause] tells why. */
    data class NetworkError(val cause: Throwable) : SshAuthResult

    /** The login failed for a different cause, for example a key error or an algorithm mismatch. */
    data class Failed(val cause: Throwable) : SshAuthResult

    /**
     * The host key check stopped the connection before the client sent credentials. A retry gives the
     * same result until the user makes a decision, so a reconnect loop must stop and ask the user.
     */
    sealed interface HostKeyRejected : SshAuthResult {
        /** The host key that the server sent. Its `host:port` is the endpoint of the rejected session. */
        val presented: KnownHost
    }

    /**
     * The server sent the host key [presented], and the known hosts have no key for its `host:port`.
     * The user can accept [presented]. The app then saves it and connects again.
     */
    data class HostKeyUnknown(override val presented: KnownHost) : HostKeyRejected

    /**
     * The server sent the host key [presented], but the known hosts have the different key [known]
     * for its `host:port`. This can be an attack. The user can remove [known] and then connect again.
     */
    data class HostKeyChanged(val known: KnownHost, override val presented: KnownHost) : HostKeyRejected
}
