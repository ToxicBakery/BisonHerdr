package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KnownHost
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Selects the address of each socket that the client opens, and stops each connection that the local network
 * protection of Android 17 does not permit. [LocalNetworkAddresses] tells which addresses are on the local network.
 *
 * While the app has the permission ([hasPermission]), the guard does not change the connection. Without the
 * permission, the guard resolves the host and selects the first address that is not on the local network. When all
 * addresses of the host are on the local network, or the host has the mDNS suffix `.local`, the guard throws a
 * [LocalNetworkPermissionException] and the client opens no socket. Thus the connection fails at once, and does not
 * wait for a TCP timeout.
 *
 * [onLinkPrefixes] gives the IPv6 prefixes of the default network of the device. [resolve] gives the addresses of a
 * host. A test gives its own functions.
 *
 * The SSH sockets use [socketAddressFor]. A socket that gets an address and not a host, for example the UDP socket of
 * a later Mosh backbone (issue #22), uses [requireAccess] before it sends.
 */
class LocalNetworkGuard(
    private val hasPermission: () -> Boolean,
    private val onLinkPrefixes: () -> List<AddressPrefix> = { emptyList() },
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {

    /**
     * Returns the address for a socket to [host] and [port]. With the permission, it is the host and the port with no
     * change. Without the permission, it is the first address of [host] that is not on the local network.
     *
     * Throws a [LocalNetworkPermissionException] when each address of [host] needs the permission. Throws an
     * [IOException] when [host] has no address.
     */
    fun socketAddressFor(host: String, port: Int): InetSocketAddress {
        if (hasPermission()) return InetSocketAddress(host, port)
        val address = permittedAddressOf(host)
            ?: throw LocalNetworkPermissionException(KnownHost.formatHostPort(host, port))
        return InetSocketAddress(address, port)
    }

    /**
     * Returns true when a connection to [host] needs the permission, and the app does not have it. A host that does not
     * resolve returns false, because its connection then fails with a network error.
     */
    fun needsPermission(host: String): Boolean {
        if (hasPermission()) return false
        return try {
            permittedAddressOf(host) == null
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Throws a [LocalNetworkPermissionException] for [hostPort] when [address] is on the local network and the app does
     * not have the permission.
     */
    fun requireAccess(address: InetAddress, hostPort: String) {
        if (!hasPermission() && LocalNetworkAddresses.isLocal(address.address, onLinkPrefixes())) {
            throw LocalNetworkPermissionException(hostPort)
        }
    }

    /** Returns the first address of [host] that is not on the local network, or null when it has no such address. */
    private fun permittedAddressOf(host: String): InetAddress? {
        if (LocalNetworkAddresses.isLocalName(host)) return null
        val prefixes = onLinkPrefixes()
        return resolve(host).firstOrNull { address -> !LocalNetworkAddresses.isLocal(address.address, prefixes) }
    }
}
