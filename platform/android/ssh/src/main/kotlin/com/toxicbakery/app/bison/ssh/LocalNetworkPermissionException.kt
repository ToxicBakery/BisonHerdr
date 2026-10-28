package com.toxicbakery.app.bison.ssh

/**
 * Android stops the connection to [hostPort], because [hostPort] is on the local network and the app does not have
 * the local network permission (`ACCESS_LOCAL_NETWORK`). [LocalNetworkGuard] throws it before a socket opens, so the
 * connection does not wait for a timeout. [RealSshBackend.toAuthResult] gives [SshAuthResult.LocalNetworkBlocked].
 *
 * It is a [RuntimeException], so JSch lets it go out of the login with no change. It is not an `IOException`,
 * because the reconnect loop tries again after an `IOException`, and a new attempt gives the same result until the
 * user allows the permission.
 */
class LocalNetworkPermissionException(val hostPort: String) : RuntimeException(messageFor(hostPort)) {

    companion object {
        /** Returns the text that the debug log and the connect error show for [hostPort]. */
        fun messageFor(hostPort: String): String =
            "Android stops the connection to $hostPort on the local network. The app does not have the local " +
                "network permission."
    }
}
