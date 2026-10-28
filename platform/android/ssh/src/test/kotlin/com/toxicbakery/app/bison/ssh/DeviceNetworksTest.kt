package com.toxicbakery.app.bison.ssh

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [DeviceNetworks]: the networks of the callback, the networks with an HTTP proxy, the fallback before the
 * first callback, and the global proxy rule. The app glue gives the link properties of Android to this class.
 */
class DeviceNetworksTest {

    private val wifi = network("192.168.1.5" to 24, "2001:db8:1:2::5" to 64)
    private val tailscale = network("100.64.0.7" to 32, "fd7a:115c:a1e0::7" to 128)
    private val networks = DeviceNetworks<String>()

    @Test
    fun theViewHasThePrefixesOfAllNetworksAlsoWhileAVpnIsOn() {
        networks.update("wlan0", wifi)
        networks.update("tun0", tailscale)

        val view = networks.view { error("The fallback is not necessary") }

        assertLocal(view, "192.168.1.42", "2001:db8:1:2::42")
        assertNotLocal(view, "100.101.102.103", "192.168.2.10")
        // The own addresses of the two networks are not local.
        assertNotLocal(view, "192.168.1.5", "100.64.0.7", "fd7a:115c:a1e0::7")
    }

    @Test
    fun aLostNetworkGivesNoPrefix() {
        networks.update("wlan0", wifi)
        networks.update("tun0", tailscale)

        networks.remove("wlan0")

        assertNotLocal(networks.view { null }, "192.168.1.42", "2001:db8:1:2::42")
    }

    @Test
    fun aNewStateOfANetworkReplacesTheOldState() {
        networks.update("wlan0", wifi)

        networks.update("wlan0", network("10.0.0.5" to 24))

        val view = networks.view { null }
        assertLocal(view, "10.0.0.42")
        assertNotLocal(view, "192.168.1.42")
    }

    @Test
    fun beforeTheFirstCallbackTheViewReadsTheFallbackEachTimeAndKeepsNothing() {
        var fallback: DeviceNetwork? = wifi

        assertLocal(networks.view { fallback }, "192.168.1.42")
        // The default network disconnected before the first callback. The view does not keep it.
        fallback = null
        assertNotLocal(networks.view { fallback }, "192.168.1.42")
    }

    @Test
    fun aNetworkWithAProxyGivesNoPrefixButItsAddressesStayOwnAddresses() {
        networks.update("wlan0", network("192.168.1.5" to 24, hasProxy = true))
        networks.update("tun0", tailscale)
        // A network with no proxy on the same subnet, for example Ethernet.
        networks.update("eth0", network("192.168.1.7" to 24))

        val view = networks.view { null }

        assertTrue(view.isProtected)
        // The prefix of eth0 contains both addresses, but 192.168.1.5 is an own address of wlan0.
        assertLocal(view, "192.168.1.42")
        assertNotLocal(view, "192.168.1.5", "192.168.1.7")
        // Multicast stays local, because a network has the protection.
        assertLocal(view, "224.0.0.251")
    }

    @Test
    fun theProtectionFollowsTheDefaultNetwork() {
        networks.update("wlan0", network("192.168.1.5" to 24, hasProxy = true))
        networks.update("rmnet0", network("10.64.1.5" to 30))

        // The Wi-Fi network with a proxy carries the traffic, so its multicast is not stopped.
        val wifiDefault = networks.view(defaultKey = "wlan0") { null }
        assertFalse(wifiDefault.isProtected)
        assertNotLocal(wifiDefault, "224.0.0.251")

        val cellularDefault = networks.view(defaultKey = "rmnet0") { null }
        assertTrue(cellularDefault.isProtected)
        assertLocal(cellularDefault, "224.0.0.251")

        // With no known default network, a network with no proxy turns the protection on.
        assertTrue(networks.view(defaultKey = "unknown") { null }.isProtected)
    }

    @Test
    fun beforeTheFirstCallbackTheProtectionFollowsTheFallback() {
        assertFalse(networks.view { network("192.168.1.5" to 24, hasProxy = true) }.isProtected)
        assertTrue(networks.view { network("192.168.1.5" to 24) }.isProtected)
    }

    @Test
    fun withAProxyOnAllNetworksNothingIsLocal() {
        networks.update("wlan0", network("192.168.1.5" to 24, hasProxy = true))

        val view = networks.view { null }

        assertFalse(view.isProtected)
        assertNotLocal(view, "192.168.1.42", "224.0.0.251", "255.255.255.255")
    }

    @Test
    fun withNoNetworkTheProtectionApplies() {
        assertTrue(networks.view { null }.isProtected)
    }

    @Test
    fun theDefaultProxyIsGlobalOnlyWhenItIsNotTheProxyOfTheDefaultNetwork() {
        assertFalse(DeviceNetworks.isGlobalProxy<String>(defaultProxy = null, defaultNetworkProxy = null))
        assertTrue(DeviceNetworks.isGlobalProxy(defaultProxy = "global:3128", defaultNetworkProxy = null))
        assertTrue(DeviceNetworks.isGlobalProxy(defaultProxy = "global:3128", defaultNetworkProxy = "wifi:8080"))
        assertFalse(DeviceNetworks.isGlobalProxy(defaultProxy = "wifi:8080", defaultNetworkProxy = "wifi:8080"))
    }

    private fun network(vararg addresses: Pair<String, Int>, hasProxy: Boolean = false): DeviceNetwork =
        DeviceNetwork(addresses.map { (text, length) -> InetAddress.getByName(text).address to length }, hasProxy)

    private fun assertLocal(view: LocalNetworkView, vararg addresses: String) {
        addresses.forEach { text ->
            assertTrue(LocalNetworkAddresses.isLocal(InetAddress.getByName(text).address, view), "$text must be local")
        }
    }

    private fun assertNotLocal(view: LocalNetworkView, vararg addresses: String) {
        addresses.forEach { text ->
            val address = InetAddress.getByName(text).address
            assertFalse(LocalNetworkAddresses.isLocal(address, view), "$text must not be local")
        }
    }
}
