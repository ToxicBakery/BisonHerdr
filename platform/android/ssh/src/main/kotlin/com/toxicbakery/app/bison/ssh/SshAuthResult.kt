package com.toxicbakery.app.bison.ssh

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
}
