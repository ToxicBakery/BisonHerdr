package com.toxicbakery.app.bison.ssh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.Uri
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Returns true when the app has the local network permission. Before API 37 (Android 17), Android has no local network
 * permission and stops no connection, so the result is true.
 */
fun hasLocalNetworkPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return true
    return context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

/**
 * Returns the [LocalNetworkGuard] of the app. It reads the permission of the app and the networks of [context].
 * Android applies no local network protection while the device has a global HTTP proxy. Then the guard acts as if the
 * app has the permission.
 */
fun androidLocalNetworkGuard(context: Context): LocalNetworkGuard {
    val appContext = context.applicationContext
    val networks = AndroidLocalNetworks(appContext.getSystemService(ConnectivityManager::class.java))
    return LocalNetworkGuard(
        hasPermission = { hasLocalNetworkPermission(appContext) || networks.hasGlobalProxy() },
        localPrefixes = networks::localPrefixes,
    )
}

/**
 * Keeps the link properties of all networks of the device, also the VPN networks, and gives their local prefixes.
 * Android 17 makes the local network list of each interface from the addresses of that interface. A socket can use a
 * network that is not the default network, so the guard needs the prefixes of all networks.
 *
 * The network callback gives the networks a short time after the start. Thus the class starts with the link
 * properties of the default network.
 */
private class AndroidLocalNetworks(private val connectivity: ConnectivityManager?) {

    private val properties = ConcurrentHashMap<Network, LinkProperties>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            properties[network] = linkProperties
        }

        override fun onLost(network: Network) {
            properties.remove(network)
        }
    }

    init {
        connectivity?.let { manager ->
            manager.activeNetwork?.let { network ->
                manager.getLinkProperties(network)?.let { properties.putIfAbsent(network, it) }
            }
            // A request with no capability also gets the VPN networks and the networks with no internet.
            val request = NetworkRequest.Builder().clearCapabilities().build()
            runCatching { manager.registerNetworkCallback(request, callback) }
        }
    }

    /**
     * Returns the local prefixes of the addresses of all networks. A network with an HTTP proxy has no local network
     * protection, so it gives no prefix.
     */
    fun localPrefixes(): List<AddressPrefix> =
        properties.values
            .filterNot { linkProperties -> isProxySet(linkProperties.httpProxy) }
            .flatMap { linkProperties -> linkProperties.linkAddresses }
            .mapNotNull { linkAddress ->
                LocalNetworkAddresses.localPrefixOf(linkAddress.address.address, linkAddress.prefixLength)
            }

    /**
     * Returns true when the device has a global HTTP proxy. The default proxy is the global proxy, or else the proxy
     * of the default network.
     */
    fun hasGlobalProxy(): Boolean {
        val manager = connectivity ?: return false
        val proxy = manager.defaultProxy?.takeIf(::isProxySet) ?: return false
        val networkProxy = manager.activeNetwork?.let { manager.getLinkProperties(it) }?.httpProxy
        return proxy != networkProxy
    }

    /** Returns true when [proxy] has a host or a PAC address, as `ConnectivityService.isProxySet` does. */
    private fun isProxySet(proxy: ProxyInfo?): Boolean =
        proxy != null && (!proxy.host.isNullOrEmpty() || proxy.pacFileUrl != Uri.EMPTY)
}
