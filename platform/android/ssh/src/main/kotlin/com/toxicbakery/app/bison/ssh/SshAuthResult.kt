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

    /**
     * The key store has no key for [keyAlias]. The user must make the key and add its public key to the
     * host. A retry without a change fails again.
     */
    data class KeyMissing(val keyAlias: String) : SshAuthResult {
        /** The text that tells the user what to do. */
        val message: String
            get() = MissingKeyException.messageFor(keyAlias)
    }

    /**
     * The key [keyAlias] needs a user authentication with a strong biometric or the device screen lock, and the
     * key is locked. Only the user can unlock it, so a reconnect loop must stop and wait for the user. It is not a
     * [NetworkError]. A user connect from the app shows the prompt again.
     *
     * [promptOutcome] is the result of the prompt of this login, or null when the login showed no prompt.
     * [keyAlias] is null when the error did not name the key. [keyNeedsUserForEachSign] is true for a key from an
     * earlier version that needs a user authentication for each sign. No prompt can unlock such a key, so the user
     * must make a new key.
     */
    data class UserAuthRequired(
        val keyAlias: String?,
        val promptOutcome: UserAuthOutcome? = null,
        val keyNeedsUserForEachSign: Boolean = false,
    ) : SshAuthResult {
        /** The text that tells the user what occurred and what to do. */
        val message: String
            get() {
                val key = keyAlias?.let { "The key $it" } ?: "The SSH key"
                if (keyNeedsUserForEachSign) {
                    return "$key needs a user authentication for each sign. This version cannot use such a key. " +
                        "Make a new key in the machine settings, and add the new public key to the host."
                }
                return when (promptOutcome) {
                    null -> "$key needs a user authentication. Connect again from the app."
                    UserAuthOutcome.NoUi ->
                        "$key needs a user authentication, but the app is in the background. " +
                            "Open the app and connect again."
                    UserAuthOutcome.Cancelled -> "The user authentication for ${key.lowercaseKey()} was cancelled."
                    is UserAuthOutcome.Failed ->
                        "The user authentication for ${key.lowercaseKey()} failed: ${promptOutcome.reason}"
                    UserAuthOutcome.TimedOut ->
                        "The user authentication for ${key.lowercaseKey()} did not complete in the time limit."
                    // The key is still locked after the prompt. A key from an earlier version needs a
                    // user authentication for each sign, and this version cannot give that.
                    UserAuthOutcome.Confirmed ->
                        "$key is still locked after the user authentication. Make a new key in the machine " +
                            "settings, and add the new public key to the host."
                }
            }

        private fun String.lowercaseKey(): String = replaceFirstChar { it.lowercaseChar() }
    }

    /**
     * The login needs answers from the user, and the user did not give them. [hostPort] is the bastion or the
     * target that asks.
     *
     * - [timedOut] is false: the host asked questions with the `keyboard-interactive` method, and no UI was open
     *   to ask the user, for example during a reconnect in the background.
     * - [timedOut] is true: the user did not answer the questions of the host, or the question for a missing
     *   password, before the time limit.
     *
     * A retry gives the same result until the user answers, so a reconnect loop must stop. It is not a
     * [NetworkError]. A missing password with no UI gives [Failed] with a [MissingSecretException].
     */
    data class InputRequired(val hostPort: String, val timedOut: Boolean) : SshAuthResult

    /** The user cancelled the questions of [hostPort]. A reconnect loop must stop. It is not a [NetworkError]. */
    data class InputCancelled(val hostPort: String) : SshAuthResult

    /**
     * Android stops the connection to [hostPort] on the local network, because the app does not have the local
     * network permission (`ACCESS_LOCAL_NETWORK`). [hostPort] is the bastion or the target. The client opened no
     * socket. A retry gives the same result until the user allows the permission, so a reconnect loop must stop. It is
     * not a [NetworkError].
     */
    data class LocalNetworkBlocked(val hostPort: String) : SshAuthResult
}
