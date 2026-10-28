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

    /** The secret store has no password for the machine. */
    data object PasswordMissing : NeedsUserCause

    /**
     * The app did not get a connection to the host. Only a connect from the user gives this cause, because the
     * reconnect loop tries again after a network error. [detail] is the error text.
     */
    data class Unreachable(val detail: String) : NeedsUserCause

    /** A different failure. [detail] is the error text. */
    data class Other(val detail: String) : NeedsUserCause
}
