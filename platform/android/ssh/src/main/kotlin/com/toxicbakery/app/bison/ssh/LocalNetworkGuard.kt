package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.KnownHost
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Selects the address of each socket that the client opens, and finds the connections that the local network
 * protection of Android 17 stops. [LocalNetworkAddresses] has the rules.
 *
 * While the app has the permission ([hasPermission]), the guard does not change the connection. Without the
 * permission, the guard resolves the host. It selects the first address that Android does not stop.
 * - When Android stops each address of the host, or the host has the mDNS suffix `.local`, the guard throws a
 *   [LocalNetworkPermissionException]. The client opens no socket, so the connection fails at once.
 * - When the connect to a private address fails after its time limit, [connectFailure] gives a
 *   [LocalNetworkPermissionException] with `timedOut`. Android can stop such a connection on a network that the app
 *   does not see.
 *
 * [localPrefixes] gives the prefixes of all interfaces of the device ([LocalNetworkAddresses.localPrefixOf]).
 * [resolve] gives the addresses of a host. [timeSource] measures the age of a resolution that [needsPermission] keeps
 * for the next socket. A test gives its own functions.
 *
 * The SSH sockets use [socketAddressFor] and [connectFailure]. A socket that gets an address and not a host can use
 * [requireAccess] before it sends. An example is the UDP socket of a later Mosh backbone (issue #22).
 */
class LocalNetworkGuard(
    private val hasPermission: () -> Boolean,
    private val localPrefixes: () -> List<AddressPrefix>,
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /** The addresses of a host that [needsPermission] resolved, and the time of the resolution. */
    private class Resolution(val addresses: List<InetAddress>, val time: TimeMark)

    /** The resolution of each host from [needsPermission]. The next socket to the host uses it one time. */
    private val resolutions = ConcurrentHashMap<String, Resolution>()

    /**
     * Returns the address for a socket to [host] and [port]. With the permission, it is the host and the port with no
     * change. Without the permission, it is the first address of [host] that Android does not stop.
     *
     * Throws a [LocalNetworkPermissionException] when Android stops each address of [host]. Throws an [IOException]
     * when [host] has no address.
     */
    fun socketAddressFor(host: String, port: Int): InetSocketAddress {
        if (hasPermission()) return InetSocketAddress(host, port)
        val address = permittedAddressOf(host, addressesOf(host))
            ?: throw LocalNetworkPermissionException(KnownHost.formatHostPort(host, port))
        return InetSocketAddress(address, port)
    }

    /**
     * Returns the error to throw when the connect to [address] failed with [error]. [hostPort] is the host and the
     * port of the connection.
     *
     * The result is a [LocalNetworkPermissionException] with `timedOut` when all of these are true:
     * - The app does not have the permission.
     * - [address] is private ([LocalNetworkAddresses.isPrivate]).
     * - [error] is a timeout, or an `EPERM` error.
     *
     * In all other cases, the result is [error].
     */
    fun connectFailure(address: InetSocketAddress, hostPort: String, error: IOException): Exception {
        val resolved = address.address ?: return error
        if (hasPermission() || !LocalNetworkAddresses.isPrivate(resolved.address) || !isDenial(error)) return error
        return LocalNetworkPermissionException(hostPort, timedOut = true, cause = error)
    }

    /**
     * Returns true when Android stops a connection to [host], and the app does not have the permission. A host that
     * does not resolve returns false, because its connection then fails with a network error. The next socket to
     * [host] uses this resolution when it is recent.
     */
    fun needsPermission(host: String): Boolean {
        if (hasPermission()) return false
        if (LocalNetworkAddresses.isLocalName(host)) return true
        return try {
            val addresses = resolve(host)
            resolutions[host] = Resolution(addresses, timeSource.markNow())
            permittedAddressOf(host, addresses) == null
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Throws a [LocalNetworkPermissionException] for [hostPort] when Android stops a connection to [address], and the
     * app does not have the permission.
     */
    fun requireAccess(address: InetAddress, hostPort: String) {
        if (!hasPermission() && LocalNetworkAddresses.isLocal(address.address, localPrefixes())) {
            throw LocalNetworkPermissionException(hostPort)
        }
    }

    /** Returns the recent resolution of [host] from [needsPermission] one time, or resolves [host] again. */
    private fun addressesOf(host: String): List<InetAddress> {
        if (LocalNetworkAddresses.isLocalName(host)) return emptyList()
        val recent = resolutions.remove(host)?.takeIf { it.time.elapsedNow() < RESOLUTION_REUSE_LIMIT }
        return recent?.addresses ?: resolve(host)
    }

    /** Returns the first of [addresses] that Android does not stop, or null when [host] has no such address. */
    private fun permittedAddressOf(host: String, addresses: List<InetAddress>): InetAddress? {
        if (LocalNetworkAddresses.isLocalName(host)) return null
        val prefixes = localPrefixes()
        return addresses.firstOrNull { address -> !LocalNetworkAddresses.isLocal(address.address, prefixes) }
    }

    /** Returns true when [error] is a connect timeout, or an `EPERM` error in its text or in the text of a cause. */
    private fun isDenial(error: IOException): Boolean =
        error is SocketTimeoutException ||
            generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { EPERM in it.message.orEmpty() }

    private companion object {
        /** The maximum age of a resolution from [needsPermission] that the next socket uses. */
        val RESOLUTION_REUSE_LIMIT: Duration = 10.seconds

        /** The text of the `EPERM` error code, for example in `sendto failed: EPERM (Operation not permitted)`. */
        const val EPERM = "EPERM"

        /** The maximum number of causes that [isDenial] examines. */
        const val MAX_CAUSE_DEPTH = 8
    }
}
