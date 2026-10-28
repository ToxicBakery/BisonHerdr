package com.toxicbakery.app.bison.ssh

/**
 * The app cannot connect to [hostPort], because it does not have the local network permission (`ACCESS_LOCAL_NETWORK`).
 * [RealSshBackend.toAuthResult] gives [SshAuthResult.LocalNetworkBlocked].
 *
 * - When [timedOut] is false, [LocalNetworkGuard] threw it before the socket opened. Android stops each connection to
 *   [hostPort], so the connection does not wait for a timeout.
 * - When [timedOut] is true, the connect to the private address of [hostPort] failed after its time limit. Android
 *   can stop such a connection. [cause] is the error of the connect.
 *
 * It is a [RuntimeException], so JSch lets it go out of the login with no change. It is not an `IOException`, because
 * the reconnect loop tries again after an `IOException`. A new attempt gives the same result until the user allows
 * the permission.
 */
class LocalNetworkPermissionException(
    val hostPort: String,
    val timedOut: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(messageFor(hostPort, timedOut), cause) {

    companion object {
        /** Returns the text that the debug log and the connect error show for [hostPort]. */
        fun messageFor(hostPort: String, timedOut: Boolean): String =
            if (timedOut) {
                "The connection to the private address $hostPort did not complete in the time limit. The app does " +
                    "not have the local network permission, and Android can stop such a connection."
            } else {
                "The app did not connect to $hostPort, because it is on the local network. The app does not have the " +
                    "local network permission."
            }
    }
}
