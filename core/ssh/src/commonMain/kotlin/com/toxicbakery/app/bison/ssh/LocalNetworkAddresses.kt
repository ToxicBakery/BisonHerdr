package com.toxicbakery.app.bison.ssh

/**
 * Tells if an address or a host name is on the local network, as the local network protection of Android 17 defines
 * it. An app that targets API 37 must have the `ACCESS_LOCAL_NETWORK` runtime permission for a TCP or UDP connection to
 * such an address. Without the permission, Android drops the packets. A TCP connect then fails after its timeout, and
 * a UDP send fails with `EPERM`. See https://developer.android.com/privacy-and-security/local-network-definition.
 *
 * Android 17 (`ConnectivityService` at the tag `android-17.0.0_r1`) makes a list for each interface of the device. The
 * list has the on-link prefix of each address of the interface ([localPrefixOf]):
 * - An IPv4 prefix only when it is in one of the five ranges: `169.254.0.0/16`, `100.64.0.0/10` (CGNAT), `10.0.0.0/8`,
 *   `172.16.0.0/12`, and `192.168.0.0/16`.
 * - Each IPv6 prefix with a length that is more than 0.
 *
 * The list also has the multicast ranges `224.0.0.0/4` and `ff00::/8`, and the IPv4 broadcast address. Android drops a
 * packet only when its address is in the list of the interface that sends it. Android makes no list for a network
 * with an HTTP proxy, or when the device has a global HTTP proxy.
 *
 * Thus a Tailscale or WireGuard peer is not local when the VPN interface has only its own address (`/32` or `/128`).
 * A host on a different private subnet behind a router is also not local. For such an address, [isPrivate] tells only
 * that Android can stop the connection on a network that has its range.
 *
 * A host name with the mDNS suffix `.local` is also local ([isLocalName]), because its query is a multicast. Loopback
 * addresses are not local.
 */
object LocalNetworkAddresses {

    private const val MDNS_SUFFIX = ".local"

    /** The IPv4 ranges that Android puts in its lists (`NetworkStackConstants.IPV4_LOCAL_PREFIXES`). */
    private val IPV4_LOCAL_RANGES = listOf(
        ipv4Prefix(169, 254, 0, 0, length = 16), // Link-local
        ipv4Prefix(100, 64, 0, 0, length = 10), // CGNAT, Tailscale
        ipv4Prefix(10, 0, 0, 0, length = 8), // RFC 1918
        ipv4Prefix(172, 16, 0, 0, length = 12), // RFC 1918
        ipv4Prefix(192, 168, 0, 0, length = 16), // RFC 1918
    )

    /** The private IPv6 ranges: Android puts a prefix in them in its list when an interface has an address there. */
    private val IPV6_PRIVATE_RANGES = listOf(
        ipv6Prefix(0xfe, 0x80, length = 10), // Link-local
        ipv6Prefix(0xfc, 0x00, length = 7), // Unique local
    )

    /** The ranges that Android puts in the list of each interface (`MULTICAST_AND_BROADCAST_PREFIXES`). */
    private val MULTICAST_AND_BROADCAST = listOf(
        ipv4Prefix(224, 0, 0, 0, length = 4), // Multicast
        ipv4Prefix(255, 255, 255, 255, length = 32), // Broadcast
        ipv6Prefix(0xff, 0x00, length = 8), // Multicast
    )

    /** The first 12 bytes of an IPv4-mapped IPv6 address (`::ffff:0:0/96`). */
    private val IPV4_MAPPED_PREFIX = ByteArray(IPV4_MAPPED_PREFIX_SIZE) { index ->
        if (index >= IPV4_MAPPED_PREFIX_SIZE - 2) 0xff.toByte() else 0
    }

    /**
     * Returns true when [host] has the mDNS suffix `.local`, also with a final dot and in each letter case. The query
     * for such a name is a multicast on the local network. Thus Android stops it when the app does not have the
     * permission.
     */
    fun isLocalName(host: String): Boolean =
        host.trim().trimEnd('.').lowercase().endsWith(MDNS_SUFFIX)

    /**
     * Returns the prefix that Android puts in the list of an interface for its address [address] with the length
     * [prefixLength]. Returns null when Android puts no prefix in the list for it. [address] has 4 bytes (IPv4) or 16
     * bytes (IPv6).
     */
    fun localPrefixOf(address: ByteArray, prefixLength: Int): AddressPrefix? {
        if (address.size != IPV4_SIZE && address.size != IPV6_SIZE) return null
        if (prefixLength !in 1..address.size * Byte.SIZE_BITS) return null
        val prefix = AddressPrefix(address, prefixLength)
        if (address.size == IPV6_SIZE) return prefix
        val inRange = IPV4_LOCAL_RANGES.any { range -> prefixLength >= range.length && range.contains(address) }
        return prefix.takeIf { inRange }
    }

    /**
     * Returns true when Android stops a connection to [address] without the permission. [address] has 4 bytes (IPv4)
     * or 16 bytes (IPv6). Each other size is not local. [localPrefixes] are the prefixes of the interfaces of the
     * device, from [localPrefixOf]. Multicast and broadcast addresses are always local.
     */
    fun isLocal(address: ByteArray, localPrefixes: List<AddressPrefix>): Boolean {
        val candidate = ipv4Of(address) ?: address.takeIf { it.size == IPV6_SIZE } ?: return false
        return MULTICAST_AND_BROADCAST.any { it.contains(candidate) } || localPrefixes.any { it.contains(candidate) }
    }

    /**
     * Returns true when [address] is in a range that Android can put in the list of an interface. These ranges are
     * the five IPv4 ranges, the IPv6 link-local and unique local ranges, multicast, and broadcast. Android stops a
     * connection to such an address only when an interface of the device has a prefix of it. See [isLocal].
     */
    fun isPrivate(address: ByteArray): Boolean {
        val ipv4 = ipv4Of(address)
        return when {
            isLocal(address, emptyList()) -> true
            ipv4 != null -> IPV4_LOCAL_RANGES.any { it.contains(ipv4) }
            else -> IPV6_PRIVATE_RANGES.any { it.contains(address) }
        }
    }

    /** Returns [address] when it is an IPv4 address, the IPv4 address in an IPv4-mapped IPv6 address, or null. */
    private fun ipv4Of(address: ByteArray): ByteArray? = when {
        address.size == IPV4_SIZE -> address
        address.size == IPV6_SIZE && IPV4_MAPPED_PREFIX.indices.all { address[it] == IPV4_MAPPED_PREFIX[it] } ->
            address.copyOfRange(IPV4_MAPPED_PREFIX_SIZE, IPV6_SIZE)
        else -> null
    }

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
