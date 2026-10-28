package com.toxicbakery.app.bison.ssh

/**
 * Tells if an address or a host name is on the local network, as Android 17 local network protection defines it.
 * An app that targets API 37 must have the `ACCESS_LOCAL_NETWORK` runtime permission for each TCP or UDP connection
 * to such an address. Without the permission, Android drops the packets: a TCP connect fails after its timeout, and a
 * UDP send fails with `EPERM`. See https://developer.android.com/privacy-and-security/local-network-definition.
 *
 * These addresses are on the local network:
 * - IPv4: the link-local range `169.254.0.0/16`, the CGNAT range `100.64.0.0/10` (Tailscale uses it), and the
 *   RFC 1918 ranges `10.0.0.0/8`, `172.16.0.0/12`, and `192.168.0.0/16`.
 * - IPv6: the link-local range `fe80::/10`, the unique local range `fc00::/7`, and the prefixes of the networks of
 *   the device (the `onLinkPrefixes` of [isLocal]).
 * - The multicast ranges `224.0.0.0/4` and `ff00::/8`, and the IPv4 broadcast address `255.255.255.255`.
 * - An IPv4-mapped IPv6 address of a local IPv4 address.
 *
 * A host name with the mDNS suffix `.local` is also on the local network ([isLocalName]), because Android stops the
 * mDNS query for it. The loopback addresses are not on the local network.
 *
 * Android applies a range only to an interface that has an address in it. Thus this check can find an address local
 * when Android does not stop the connection, and the app then asks for a permission that it does not need. But the
 * app never waits for the timeout of a connection that Android stops.
 *
 * Two ranges are local here, but the documents of Android are not clear about them. The reference of
 * `ACCESS_LOCAL_NETWORK` excludes VPN connections, but the list has the CGNAT range, and Tailscale uses that range
 * on its VPN interface. The list does not name the unique local range, but home networks and the IPv6 addresses of
 * Tailscale (`fd7a:115c:a1e0::/48`) use it.
 */
object LocalNetworkAddresses {

    private const val MDNS_SUFFIX = ".local"

    private val IPV4_RANGES = listOf(
        ipv4Prefix(169, 254, 0, 0, length = 16), // Link-local
        ipv4Prefix(100, 64, 0, 0, length = 10), // CGNAT, Tailscale
        ipv4Prefix(10, 0, 0, 0, length = 8), // RFC 1918
        ipv4Prefix(172, 16, 0, 0, length = 12), // RFC 1918
        ipv4Prefix(192, 168, 0, 0, length = 16), // RFC 1918
        ipv4Prefix(224, 0, 0, 0, length = 4), // Multicast
        ipv4Prefix(255, 255, 255, 255, length = 32), // Broadcast
    )

    private val IPV6_RANGES = listOf(
        ipv6Prefix(0xfe, 0x80, length = 10), // Link-local
        ipv6Prefix(0xfc, 0x00, length = 7), // Unique local
        ipv6Prefix(0xff, 0x00, length = 8), // Multicast
    )

    /** The first 12 bytes of an IPv4-mapped IPv6 address (`::ffff:0:0/96`). */
    private val IPV4_MAPPED_PREFIX = ByteArray(IPV4_MAPPED_PREFIX_SIZE) { index ->
        if (index >= IPV4_MAPPED_PREFIX_SIZE - 2) 0xff.toByte() else 0
    }

    /**
     * Returns true when [host] has the mDNS suffix `.local`, also with a final dot and in each letter case. The query
     * for such a name is a multicast on the local network, so Android stops it when the app does not have the
     * permission.
     */
    fun isLocalName(host: String): Boolean =
        host.trim().trimEnd('.').lowercase().endsWith(MDNS_SUFFIX)

    /**
     * Returns true when [address] is on the local network. [address] has 4 bytes (IPv4) or 16 bytes (IPv6). Each
     * other size is not local. [onLinkPrefixes] are the prefixes of the networks of the device, for example the IPv6
     * prefix of the Wi-Fi network. An address in one of them is local.
     */
    fun isLocal(address: ByteArray, onLinkPrefixes: List<AddressPrefix> = emptyList()): Boolean {
        val ipv4 = when (address.size) {
            IPV4_SIZE -> address
            IPV6_SIZE -> address.takeIf { it.isIpv4Mapped() }?.copyOfRange(IPV4_MAPPED_PREFIX_SIZE, IPV6_SIZE)
            else -> return false
        }
        if (ipv4 != null) return IPV4_RANGES.any { it.contains(ipv4) } || onLinkPrefixes.any { it.contains(ipv4) }
        return IPV6_RANGES.any { it.contains(address) } || onLinkPrefixes.any { it.contains(address) }
    }

    private fun ByteArray.isIpv4Mapped(): Boolean =
        IPV4_MAPPED_PREFIX.indices.all { index -> this[index] == IPV4_MAPPED_PREFIX[index] }

    private fun ipv4Prefix(first: Int, second: Int, third: Int, fourth: Int, length: Int): AddressPrefix =
        AddressPrefix(byteArrayOf(first.toByte(), second.toByte(), third.toByte(), fourth.toByte()), length)

    private fun ipv6Prefix(first: Int, second: Int, length: Int): AddressPrefix =
        AddressPrefix(ByteArray(IPV6_SIZE).also { it[0] = first.toByte(); it[1] = second.toByte() }, length)
}

/**
 * An IP prefix: the first [length] bits of an address. The address has 4 bytes (IPv4) or 16 bytes (IPv6). The
 * prefix contains only addresses of the same size.
 */
class AddressPrefix(address: ByteArray, val length: Int) {

    private val bytes: ByteArray = address.copyOf()

    init {
        require(bytes.size == IPV4_SIZE || bytes.size == IPV6_SIZE) { "An address has 4 or 16 bytes: ${bytes.size}" }
        require(length in 0..bytes.size * Byte.SIZE_BITS) { "The prefix length $length is out of range" }
    }

    /** Returns true when the first [length] bits of [address] are the same as the bits of this prefix. */
    fun contains(address: ByteArray): Boolean {
        if (address.size != bytes.size) return false
        val fullBytes = length / Byte.SIZE_BITS
        for (index in 0 until fullBytes) {
            if (address[index] != bytes[index]) return false
        }
        val remainingBits = length % Byte.SIZE_BITS
        if (remainingBits == 0) return true
        val mask = (0xff shl (Byte.SIZE_BITS - remainingBits)) and 0xff
        return (address[fullBytes].toInt() and mask) == (bytes[fullBytes].toInt() and mask)
    }

    override fun toString(): String =
        "AddressPrefix(${bytes.joinToString(".") { (it.toInt() and 0xff).toString() }}/$length)"
}

private const val IPV4_SIZE = 4
private const val IPV6_SIZE = 16
private const val IPV4_MAPPED_PREFIX_SIZE = 12
