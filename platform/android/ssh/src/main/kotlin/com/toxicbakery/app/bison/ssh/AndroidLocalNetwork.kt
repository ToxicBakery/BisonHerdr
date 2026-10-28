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
 * app has the permission. Before API 37, the guard reads no network.
 */
fun androidLocalNetworkGuard(context: Context): LocalNetworkGuard {
    val appContext = context.applicationContext
    val networks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        AndroidLocalNetworks(appContext.getSystemService(ConnectivityManager::class.java))
    } else {
        null
    }
    return LocalNetworkGuard(
        hasPermission = { hasLocalNetworkPermission(appContext) || networks?.hasGlobalProxy() == true },
        localNetwork = { networks?.view() ?: LocalNetworkView.EMPTY },
    )
}

/**
 * Keeps the link properties of all networks of the device in [DeviceNetworks], also of the VPN networks. A socket can
 * use a network that is not the default network, so the guard needs all networks.
 *
 * The network callback gives the networks a short time after the start. Until then, the view reads the default
 * network each time, and keeps nothing.
 */
private class AndroidLocalNetworks(private val connectivity: ConnectivityManager?) {

    private val networks = DeviceNetworks<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networks.update(network, deviceNetworkOf(linkProperties))
        }

        override fun onLost(network: Network) {
            networks.remove(network)
        }
    }

    init {
        // A request with no capability also gets the VPN networks and the networks with no internet.
        val request = NetworkRequest.Builder().clearCapabilities().build()
        connectivity?.let { manager -> runCatching { manager.registerNetworkCallback(request, callback) } }
    }

    /** Returns the view of all networks, or of the default network before the first callback. */
    fun view(): LocalNetworkView =
        networks.view(defaultKey = connectivity?.activeNetwork) { defaultLinkProperties()?.let(::deviceNetworkOf) }

    /** Returns true when the device has a global HTTP proxy. See [DeviceNetworks.isGlobalProxy]. */
    fun hasGlobalProxy(): Boolean {
        val manager = connectivity ?: return false
        return DeviceNetworks.isGlobalProxy(
            defaultProxy = manager.defaultProxy?.takeIf(::isProxySet),
            defaultNetworkProxy = defaultLinkProperties()?.httpProxy?.takeIf(::isProxySet),
        )
    }

    private fun defaultLinkProperties(): LinkProperties? =
        connectivity?.let { manager -> manager.activeNetwork?.let(manager::getLinkProperties) }

    private fun deviceNetworkOf(linkProperties: LinkProperties): DeviceNetwork =
        DeviceNetwork(
            addresses = linkProperties.linkAddresses.map { it.address.address to it.prefixLength },
            hasProxy = isProxySet(linkProperties.httpProxy),
        )

    /** Returns true when [proxy] has a host or a PAC address, as `ConnectivityService.isProxySet` does. */
    private fun isProxySet(proxy: ProxyInfo?): Boolean =
        proxy != null && (!proxy.host.isNullOrEmpty() || proxy.pacFileUrl != Uri.EMPTY)
}
