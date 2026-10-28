package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [LocalNetworkAddresses] against the local network rules of Android 17 (`ConnectivityService` at the tag
 * `android-17.0.0_r1`): the on-link prefixes of the interfaces, multicast, and broadcast.
 */
class LocalNetworkAddressesTest {

    /** The prefixes of a device on a Wi-Fi network with IPv4 and IPv6, and with Tailscale on. */
    private val wifiAndTailscale = listOfNotNull(
        prefix("192.168.1.5", 24), // wlan0
        prefix("2001:db8:1:2::5", 64), // wlan0, global
        prefix("fe80::5", 64), // wlan0, link-local
        prefix("100.64.0.7", 32), // tun0, Tailscale
        prefix("fd7a:115c:a1e0::7", 128), // tun0, Tailscale
    )

    @Test
    fun anIpv4PrefixIsLocalOnlyInsideTheFiveRanges() {
        assertPrefix("192.168.1.5", 24)
        assertPrefix("10.20.30.2", 24)
        assertPrefix("10.20.30.2", 32)
        assertPrefix("172.16.4.1", 12)
        assertPrefix("169.254.7.7", 16)
        assertPrefix("100.64.0.7", 32)
        assertPrefix("100.64.0.7", 10)
        // A prefix that is larger than its range, or outside each range, is not local.
        assertNull(LocalNetworkAddresses.localPrefixOf(address("10.1.2.3"), 7))
        assertNull(LocalNetworkAddresses.localPrefixOf(address("100.64.0.7"), 9))
        assertNull(LocalNetworkAddresses.localPrefixOf(address("203.0.113.5"), 24))
        assertNull(LocalNetworkAddresses.localPrefixOf(address("192.168.1.5"), 0))
    }

    @Test
    fun anIpv6PrefixIsLocalWhenItsLengthIsMoreThanZero() {
        assertPrefix("2001:db8:1:2::5", 64)
        assertPrefix("fd7a:115c:a1e0::7", 128)
        assertPrefix("fe80::5", 64)
        assertNull(LocalNetworkAddresses.localPrefixOf(address("2001:db8::5"), 0))
        assertNull(LocalNetworkAddresses.localPrefixOf(ByteArray(5), 8))
        assertNull(LocalNetworkAddresses.localPrefixOf(address("2001:db8::5"), 129))
    }

    @Test
    fun aHostOnTheWifiSubnetIsLocal() {
        assertLocal("192.168.1.42", "192.168.1.255", "2001:db8:1:2::42", "fe80::42")
    }

    @Test
    fun aTailscalePeerIsNotLocalBecauseTheVpnInterfaceHasOnlyItsOwnAddress() {
        assertNotLocal("100.101.102.103", "100.64.0.8", "fd7a:115c:a1e0::9")
        // The own addresses of the VPN interface are local.
        assertLocal("100.64.0.7", "fd7a:115c:a1e0::7")
    }

    @Test
    fun aWireGuardPeerIsLocalOnlyWhenTheInterfaceHasItsSubnet() {
        assertFalse(LocalNetworkAddresses.isLocal(address("10.20.30.40"), listOfNotNull(prefix("10.20.30.2", 32))))
        assertTrue(LocalNetworkAddresses.isLocal(address("10.20.30.40"), listOfNotNull(prefix("10.20.30.2", 24))))
    }

    @Test
    fun aHostOnARoutedPrivateSubnetIsNotLocalButIsPrivate() {
        listOf("192.168.2.10", "10.0.0.7", "172.20.1.1", "fd00::42", "2001:db8:1:3::42").forEach { text ->
            assertFalse(LocalNetworkAddresses.isLocal(address(text), wifiAndTailscale), "$text must not be local")
        }
        listOf("192.168.2.10", "10.0.0.7", "172.20.1.1", "100.101.102.103", "169.254.1.1", "fd00::42", "fe80::9")
            .forEach { text -> assertTrue(LocalNetworkAddresses.isPrivate(address(text)), "$text must be private") }
        listOf("2001:db8:1:3::42", "8.8.8.8", "127.0.0.1", "::1", "100.128.0.1").forEach { text ->
            assertFalse(LocalNetworkAddresses.isPrivate(address(text)), "$text must not be private")
        }
    }

    @Test
    fun multicastAndBroadcastAddressesAreAlwaysLocal() {
        listOf("224.0.0.251", "239.255.255.250", "255.255.255.255", "ff02::fb", "ff05::c").forEach { text ->
            assertTrue(LocalNetworkAddresses.isLocal(address(text), emptyList()), "$text must be local")
            assertTrue(LocalNetworkAddresses.isPrivate(address(text)), "$text must be private")
        }
    }

    @Test
    fun publicAndLoopbackAddressesAreNotLocal() {
        assertNotLocal("8.8.8.8", "140.82.112.3", "127.0.0.1", "0.0.0.0", "::1", "::", "2606:4700::1111")
    }

    @Test
    fun anIpv4MappedIpv6AddressHasTheResultOfItsIpv4Address() {
        assertLocal("::ffff:192.168.1.5")
        assertNotLocal("::ffff:192.168.2.5", "::ffff:8.8.8.8")
        assertTrue(LocalNetworkAddresses.isPrivate(address("::ffff:10.0.0.1")))
        assertFalse(LocalNetworkAddresses.isPrivate(address("::ffff:8.8.8.8")))
    }

    @Test
    fun anAddressWithAnUnknownSizeIsNotLocal() {
        assertFalse(LocalNetworkAddresses.isLocal(ByteArray(0), wifiAndTailscale))
        assertFalse(LocalNetworkAddresses.isLocal(byteArrayOf(192.toByte(), 168.toByte(), 1), wifiAndTailscale))
        assertFalse(LocalNetworkAddresses.isPrivate(ByteArray(5)))
    }

    @Test
    fun aNameWithTheMdnsSuffixIsLocal() {
        assertTrue(LocalNetworkAddresses.isLocalName("devbox.local"))
        assertTrue(LocalNetworkAddresses.isLocalName("DevBox.LOCAL."))
        assertTrue(LocalNetworkAddresses.isLocalName(" nas.lan.local "))
        assertFalse(LocalNetworkAddresses.isLocalName("local"))
        assertFalse(LocalNetworkAddresses.isLocalName("devbox.localdomain"))
        assertFalse(LocalNetworkAddresses.isLocalName("devbox.local.example.com"))
        assertFalse(LocalNetworkAddresses.isLocalName("devbox.tailnet.ts.net"))
        assertFalse(LocalNetworkAddresses.isLocalName("192.168.1.5"))
    }

    @Test
    fun aPrefixComparesOnlyItsBits() {
        val prefix = AddressPrefix(address("172.16.0.0"), 12)

        assertTrue(prefix.contains(address("172.16.0.0")))
        assertTrue(prefix.contains(address("172.31.255.255")))
        assertFalse(prefix.contains(address("172.32.0.0")))
        assertTrue(AddressPrefix(address("10.1.2.3"), 0).contains(address("8.8.8.8")))
        assertTrue(AddressPrefix(address("10.1.2.3"), 32).contains(address("10.1.2.3")))
        assertFalse(AddressPrefix(address("10.1.2.3"), 32).contains(address("10.1.2.4")))
        assertFalse(AddressPrefix(address("10.1.2.3"), 8).contains(address("::ffff:10.1.2.3")))
    }

    @Test
    fun aPrefixNeedsAValidAddressAndLength() {
        assertFailsWith<IllegalArgumentException> { AddressPrefix(ByteArray(5), 8) }
        assertFailsWith<IllegalArgumentException> { AddressPrefix(address("10.0.0.0"), 33) }
        assertFailsWith<IllegalArgumentException> { AddressPrefix(address("fe80::"), 129) }
    }

    private fun prefix(text: String, length: Int): AddressPrefix? =
        LocalNetworkAddresses.localPrefixOf(address(text), length)

    private fun assertPrefix(text: String, length: Int) {
        assertNotNull(prefix(text, length), "$text/$length must give a local prefix")
    }

    private fun assertLocal(vararg addresses: String) {
        addresses.forEach { text ->
            assertTrue(LocalNetworkAddresses.isLocal(address(text), wifiAndTailscale), "$text must be local")
        }
    }

    private fun assertNotLocal(vararg addresses: String) {
        addresses.forEach { text ->
            assertFalse(LocalNetworkAddresses.isLocal(address(text), wifiAndTailscale), "$text must not be local")
        }
    }

    private companion object {
        /** Parses an IPv4 address, or an IPv6 address with an optional `::` and an optional IPv4 end. */
        fun address(text: String): ByteArray {
            if (':' !in text) return text.split('.').map { it.toInt().toByte() }.toByteArray()
            val groups = mutableListOf<Int>()
            val ipv4End = text.substringAfterLast(':').takeIf { '.' in it }?.let(::address)
            val hexText = if (ipv4End != null) text.substringBeforeLast(':') + ":" else text
            val (head, tail) = if ("::" in hexText) {
                hexText.substringBefore("::") to hexText.substringAfter("::")
            } else {
                hexText to null
            }
            fun parse(part: String) = part.split(':').filter { it.isNotEmpty() }.map { it.toInt(16) }
            val headGroups = parse(head)
            val tailGroups = tail?.let(::parse).orEmpty()
            val total = if (ipv4End != null) 6 else 8
            groups += headGroups
            if (tail != null) repeat(total - headGroups.size - tailGroups.size) { groups += 0 }
            groups += tailGroups
            val bytes = groups.flatMap { listOf((it shr 8).toByte(), it.toByte()) }
            return (bytes + (ipv4End?.toList() ?: emptyList())).toByteArray()
        }
    }
}
