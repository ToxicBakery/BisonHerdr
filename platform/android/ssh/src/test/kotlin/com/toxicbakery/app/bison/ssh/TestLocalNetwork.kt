package com.toxicbakery.app.bison.ssh

/**
 * Returns a [LocalNetworkGuard] for a test that does not test the local network. The app has the permission, so the
 * guard does not change a connection.
 */
internal fun permittedLocalNetwork(): LocalNetworkGuard =
    LocalNetworkGuard(hasPermission = { true }, localNetwork = { LocalNetworkView.EMPTY })
