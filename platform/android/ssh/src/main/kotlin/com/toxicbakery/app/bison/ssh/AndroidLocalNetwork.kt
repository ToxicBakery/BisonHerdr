package com.toxicbakery.app.bison.ssh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import java.net.Inet6Address

/**
 * Returns true when the app can connect to the local network. Before API 37 (Android 17), Android has no local network
 * permission and stops no connection, so the result is true.
 */
fun hasLocalNetworkPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return true
    return context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

/** Returns the [LocalNetworkGuard] of the app. It reads the permission and the default network of [context]. */
fun androidLocalNetworkGuard(context: Context): LocalNetworkGuard {
    val appContext = context.applicationContext
    return LocalNetworkGuard(
        hasPermission = { hasLocalNetworkPermission(appContext) },
        onLinkPrefixes = { defaultNetworkIpv6Prefixes(appContext) },
    )
}

/**
 * Returns the IPv6 prefixes of the addresses of the default network, for example the `/64` prefix of the Wi-Fi
 * network. Android puts each IPv6 prefix of an interface in its local network list. The IPv4 ranges of that list are
 * fixed, so this function returns no IPv4 prefix.
 */
private fun defaultNetworkIpv6Prefixes(context: Context): List<AddressPrefix> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return emptyList()
    return properties.linkAddresses
        .filter { linkAddress -> linkAddress.address is Inet6Address && linkAddress.prefixLength > 0 }
        .map { linkAddress -> AddressPrefix(linkAddress.address.address, linkAddress.prefixLength) }
}
