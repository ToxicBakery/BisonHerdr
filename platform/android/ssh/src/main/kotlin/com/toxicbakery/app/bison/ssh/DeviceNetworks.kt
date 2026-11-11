package com.toxicbakery.app.bison.ssh

import java.util.concurrent.ConcurrentHashMap

/**
 * One network of the device for the local network rules. [addresses] are the addresses of its interfaces with their
 * prefix lengths. [hasProxy] is true when the network has an HTTP proxy. Android 17 does not apply the local network
 * protection on such a network.
 */
class DeviceNetwork(val addresses: List<Pair<ByteArray, Int>>, val hasProxy: Boolean)

/**
 * Keeps the networks of the device by a key, for example the `Network` of a network callback, and gives their
 * [LocalNetworkView]. The callback calls [update] and [remove].
 */
class DeviceNetworks<K : Any> {

    private val networks = ConcurrentHashMap<K, DeviceNetwork>()

    /** Puts the new state of the network [key]. */
    fun update(key: K, network: DeviceNetwork) {
        networks[key] = network
    }

    /** Removes the network [key], for example after the device lost it. */
    fun remove(key: K) {
        networks.remove(key)
    }

    /**
     * Returns the view of the known networks. [defaultKey] is the key of the default network, or null when it is not
     * known. With no known network, the view uses [fallback] as the default network. An example is the default network
     * before the first callback. The class does not keep [fallback], so it cannot become stale.
     */
    fun view(defaultKey: K? = null, fallback: () -> DeviceNetwork?): LocalNetworkView {
        val known = networks.toMap()
        if (known.isEmpty()) {
            val default = fallback()
            return localNetworkViewOf(listOfNotNull(default), default)
        }
        return localNetworkViewOf(known.values, defaultKey?.let(known::get))
    }

    companion object {
        /**
         * Returns the [LocalNetworkView] of [networks]. Only a network with no proxy gives local prefixes. The own
         * addresses come from all networks.
         *
         * The protection flag follows [defaultNetwork], because the default network carries multicast, the `.local`
         * queries, and the traffic to a routed address. When [defaultNetwork] is null, the flag is true when a network
         * has no proxy, or when there is no network.
         */
        fun localNetworkViewOf(
            networks: Collection<DeviceNetwork>,
            defaultNetwork: DeviceNetwork? = null,
        ): LocalNetworkView {
            val protectedNetworks = networks.filterNot { it.hasProxy }
            val isProtected = defaultNetwork?.let { !it.hasProxy }
                ?: (networks.isEmpty() || protectedNetworks.isNotEmpty())
            return LocalNetworkView(
                prefixes = protectedNetworks.flatMap { it.addresses }.mapNotNull { (address, length) ->
                    LocalNetworkAddresses.localPrefixOf(address, length)
                },
                ownAddresses = networks.flatMap { network -> network.addresses.map { it.first } },
                isProtected = isProtected,
            )
        }

        /**
         * Returns true when the device has a global HTTP proxy. [defaultProxy] is the result of
         * `ConnectivityManager.getDefaultProxy`: the global proxy, or else the proxy of the default network.
         * [defaultNetworkProxy] is the proxy of the default network. The two values are null when no proxy is set.
         *
         * When the global proxy is equal to the proxy of the default network, the public API cannot tell them apart.
         * The result is then false. Thus the app can refuse an address on a different network, although Android
         * permits it.
         */
        fun <T : Any> isGlobalProxy(defaultProxy: T?, defaultNetworkProxy: T?): Boolean =
            defaultProxy != null && defaultProxy != defaultNetworkProxy
    }
}
