package com.toxicbakery.app.bison.ssh

/**
 * The cause that stops the connection of a machine until the user does an action. The transport puts it in
 * [SshStateManager.needsUser], and the UI shows it. A new connect from the user removes it.
 *
 * The reconnect loop tries again only after a network error. It stops on all other failures and sets a cause,
 * because a new attempt gives the same failure until the user changes something.
 */
sealed interface NeedsUserCause {

    /** The host did not accept the login. [detail] is the error text of the SSH library. */
    data class LoginRefused(val detail: String) : NeedsUserCause

    /** The app does not know the host key of [hostPort]. [SshStateManager.hostKeyPrompts] has the decision. */
    data class HostKeyUnknown(val hostPort: String) : NeedsUserCause

    /**
     * The host key of [hostPort] is different from the saved key. [SshStateManager.hostKeyPrompts] has the
     * decision.
     */
    data class HostKeyChanged(val hostPort: String) : NeedsUserCause

    /** The device key store has no key for [keyAlias]. */
    data class KeyMissing(val keyAlias: String) : NeedsUserCause

    /**
     * The secret store has no password for the machine, and no UI was open to ask for it, for example during a
     * reconnect in the background. A connect from the app asks for the password.
     */
    data object PasswordMissing : NeedsUserCause

    /**
     * The key [keyAlias] needs a user authentication, and it is locked. A connect from the user shows the prompt
     * that unlocks it. [keyAlias] is null when the error did not name the key.
     */
    data class UserAuthRequired(val keyAlias: String?) : NeedsUserCause

    /**
     * The key [keyAlias] is from an earlier version and needs a user authentication for each sign. No prompt can
     * unlock it, so the user must make a new key.
     */
    data class KeyFromEarlierVersion(val keyAlias: String) : NeedsUserCause

    /**
     * The host [hostPort] asks for answers with the `keyboard-interactive` method, and no UI was open to ask the
     * user, for example during a reconnect in the background. [hostPort] is the bastion or the target.
     */
    data class InputRequired(val hostPort: String) : NeedsUserCause

    /** The user did not answer the questions of [hostPort] before the time limit. */
    data class InputTimedOut(val hostPort: String) : NeedsUserCause

    /** The user cancelled the questions of [hostPort]. */
    data class InputCancelled(val hostPort: String) : NeedsUserCause

    /**
     * The app did not get a connection to the host. Only a connect from the user gives this cause, because the
     * reconnect loop tries again after a network error. [detail] is the error text.
     */
    data class Unreachable(val detail: String) : NeedsUserCause

    /**
     * The app does not have the local network permission (`ACCESS_LOCAL_NETWORK`), and [hostPort] is on the local
     * network. [hostPort] is the bastion or the target. A connect from the app asks for the permission. The user can
     * also allow it in the app settings.
     *
     * When [timedOut] is false, the app did not open the socket, because Android stops a connection to [hostPort].
     * When [timedOut] is true, [hostPort] has a private address, and the connect did not complete in its time limit.
     * Android can stop such a connection, so the app does not try again.
     */
    data class LocalNetworkPermission(val hostPort: String, val timedOut: Boolean = false) : NeedsUserCause

    /** A different failure. [detail] is the error text. */
    data class Other(val detail: String) : NeedsUserCause
}
