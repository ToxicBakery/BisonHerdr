package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [LocalNetworkAddresses] against the local network definition of Android 17:
 * https://developer.android.com/privacy-and-security/local-network-definition
 */
class LocalNetworkAddressesTest {

    @Test
    fun theRfc1918RangesAreLocalUpToTheirLimits() {
        assertLocal("10.0.0.0", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.254")
        assertNotLocal("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0")
    }

    @Test
    fun theCgnatRangeOfTailscaleIsLocal() {
        assertLocal("100.64.0.0", "100.100.100.100", "100.101.102.103", "100.127.255.255")
        assertNotLocal("100.63.255.255", "100.128.0.0")
    }

    @Test
    fun linkLocalMulticastAndBroadcastAddressesAreLocal() {
        assertLocal("169.254.0.1", "169.254.255.255", "224.0.0.251", "239.255.255.250", "255.255.255.255")
        assertNotLocal("169.253.255.255", "169.255.0.0", "240.0.0.1", "255.255.255.254")
    }

    @Test
    fun publicAndLoopbackIpv4AddressesAreNotLocal() {
        assertNotLocal("8.8.8.8", "140.82.112.3", "1.1.1.1", "127.0.0.1", "0.0.0.0")
    }

    @Test
    fun ipv6LinkLocalUniqueLocalAndMulticastAddressesAreLocal() {
        assertLocal("fe80::1", "febf:ffff::1", "fc00::1", "fd00::5", "fd7a:115c:a1e0::1", "ff02::fb", "ff05::c")
        assertNotLocal("fec0::1", "fe7f::1", "fb00::1", "2001:db8::1", "2606:4700::1111", "::1", "::")
    }

    @Test
    fun anIpv4MappedIpv6AddressHasTheResultOfItsIpv4Address() {
        assertLocal("::ffff:192.168.1.5", "::ffff:100.64.1.1")
        assertNotLocal("::ffff:8.8.8.8", "::ffff:127.0.0.1")
    }

    @Test
    fun anAddressInAPrefixOfTheDeviceIsLocal() {
        val prefixes = listOf(AddressPrefix(address("2001:db8:1:2::"), 64))

        assertTrue(LocalNetworkAddresses.isLocal(address("2001:db8:1:2::5"), prefixes))
        assertTrue(LocalNetworkAddresses.isLocal(address("2001:db8:1:2:ffff:ffff:ffff:ffff"), prefixes))
        assertFalse(LocalNetworkAddresses.isLocal(address("2001:db8:1:3::5"), prefixes))
        assertFalse(LocalNetworkAddresses.isLocal(address("2001:db8:1:2::5")))
        // A prefix of one size does not contain an address of the other size.
        assertFalse(LocalNetworkAddresses.isLocal(address("8.8.8.8"), prefixes))
    }

    @Test
    fun anAddressWithAnUnknownSizeIsNotLocal() {
        assertFalse(LocalNetworkAddresses.isLocal(ByteArray(0)))
        assertFalse(LocalNetworkAddresses.isLocal(byteArrayOf(192.toByte(), 168.toByte(), 1)))
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
    }

    @Test
    fun aPrefixNeedsAValidAddressAndLength() {
        assertFailsWith<IllegalArgumentException> { AddressPrefix(ByteArray(5), 8) }
        assertFailsWith<IllegalArgumentException> { AddressPrefix(address("10.0.0.0"), 33) }
        assertFailsWith<IllegalArgumentException> { AddressPrefix(address("fe80::"), 129) }
    }

    private fun assertLocal(vararg addresses: String) {
        addresses.forEach { text -> assertTrue(LocalNetworkAddresses.isLocal(address(text)), "$text must be local") }
    }

    private fun assertNotLocal(vararg addresses: String) {
        addresses.forEach { text ->
            assertFalse(LocalNetworkAddresses.isLocal(address(text)), "$text must not be local")
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
