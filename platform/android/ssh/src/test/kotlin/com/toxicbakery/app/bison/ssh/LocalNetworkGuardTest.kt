package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Tests [LocalNetworkGuard] with a resolver of the test, and the result kind of a [LocalNetworkPermissionException].
 * The tests make no DNS query: [InetAddress.getByName] only parses the literal addresses.
 *
 * The device of the tests is on a Wi-Fi network (`192.168.1.5/24` and `2001:db8:1:2::5/64`), and Tailscale is on
 * (`100.64.0.7/32` and `fd7a:115c:a1e0::7/128`).
 */
class LocalNetworkGuardTest {

    /** The names that the resolver of [guard] got, in order. */
    private val resolved = mutableListOf<String>()

    private val timeSource = TestTimeSource()

    /** The names that a test adds to [names]. */
    private val extraNames = mutableMapOf<String, List<String>>()

    /** The addresses of each name for the resolver of [guard]. */
    private val names = mapOf(
        "devbox.lan" to listOf("2001:db8:1:2::42", "192.168.1.42"),
        "ts-box.tailnet.ts.net" to listOf("100.101.102.103", "fd7a:115c:a1e0::9"),
        "wg-box.lan" to listOf("10.20.30.40"),
        "routed-box.lan" to listOf("192.168.2.10"),
        "github.com" to listOf("140.82.112.3"),
    )

    private val devicePrefixes = listOfNotNull(
        prefix("192.168.1.5", 24),
        prefix("2001:db8:1:2::5", 64),
        prefix("fe80::5", 64),
        prefix("100.64.0.7", 32),
        prefix("fd7a:115c:a1e0::7", 128),
    )

    /** The own addresses of the device. */
    private val deviceAddresses = listOf("192.168.1.5", "2001:db8:1:2::5", "fe80::5", "100.64.0.7", "fd7a:115c:a1e0::7")

    private fun guard(
        permitted: Boolean,
        prefixes: List<AddressPrefix> = devicePrefixes,
        isProtected: Boolean = true,
    ): LocalNetworkGuard =
        LocalNetworkGuard(
            hasPermission = { permitted },
            localNetwork = {
                LocalNetworkView(prefixes, deviceAddresses.map { InetAddress.getByName(it).address }, isProtected)
            },
            resolve = { host ->
                resolved += host
                (extraNames[host] ?: names[host])?.map(InetAddress::getByName) ?: listOf(literal(host))
            },
            timeSource = timeSource,
        )

    @Test
    fun withThePermissionTheGuardDoesNotChangeTheConnection() {
        val address = guard(permitted = true).socketAddressFor("devbox.lan", 22)

        assertEquals("devbox.lan", address.hostString)
        assertEquals(22, address.port)
        assertFalse(guard(permitted = true).needsPermission("devbox.lan"))
        assertTrue(resolved.isEmpty(), "The guard resolved $resolved")
    }

    @Test
    fun aDualStackHostOnTheWifiNetworkNeedsThePermissionAlsoWithAVpn() {
        val guard = guard(permitted = false)

        val error = assertFailsWith<LocalNetworkPermissionException> { guard.socketAddressFor("devbox.lan", 2222) }

        assertEquals("devbox.lan:2222", error.hostPort)
        assertFalse(error.timedOut)
        assertTrue(guard.needsPermission("devbox.lan"))
    }

    @Test
    fun aTailscalePeerNeedsNoPermission() {
        val guard = guard(permitted = false)

        val address = guard.socketAddressFor("ts-box.tailnet.ts.net", 22).address
        assertEquals(InetAddress.getByName("100.101.102.103"), address)
        assertFalse(guard.needsPermission("ts-box.tailnet.ts.net"))
        val tailscaleIpv6 = InetAddress.getByName("fd7a:115c:a1e0::9")
        assertEquals(tailscaleIpv6, guard.socketAddressFor("fd7a:115c:a1e0::9", 22).address)
        // Android permits the own address of the VPN interface, because the packet goes through the loopback interface.
        assertEquals(InetAddress.getByName("100.64.0.7"), guard.socketAddressFor("100.64.0.7", 22).address)
    }

    @Test
    fun aWireGuardPeerNeedsThePermissionOnlyWhenTheInterfaceHasItsSubnet() {
        assertEquals(
            InetAddress.getByName("10.20.30.40"),
            guard(permitted = false, devicePrefixes + listOfNotNull(prefix("10.20.30.2", 32)))
                .socketAddressFor("wg-box.lan", 22).address,
        )
        assertFailsWith<LocalNetworkPermissionException> {
            guard(permitted = false, devicePrefixes + listOfNotNull(prefix("10.20.30.2", 24)))
                .socketAddressFor("wg-box.lan", 22)
        }
    }

    @Test
    fun aHostOnARoutedPrivateSubnetGetsAConnectAndATimeoutNeedsThePermission() {
        val guard = guard(permitted = false)
        val address = guard.socketAddressFor("routed-box.lan", 22)
        assertEquals(InetAddress.getByName("192.168.2.10"), address.address)
        assertFalse(guard.needsPermission("routed-box.lan"))

        val timeout = SocketTimeoutException("failed to connect to /192.168.2.10 (port 22) after 6000ms")
        val error = assertIs<LocalNetworkPermissionException>(
            guard.connectFailure(address, "routed-box.lan:22", timeout),
        )

        assertEquals("routed-box.lan:22", error.hostPort)
        assertTrue(error.timedOut)
        assertSame(timeout, error.cause)
    }

    @Test
    fun anEpermErrorOnAPrivateAddressNeedsThePermission() {
        val address = InetSocketAddress(InetAddress.getByName("10.0.0.7"), 22)
        val eperm = SocketException("sendto failed: EPERM (Operation not permitted)")

        val guard = guard(permitted = false)

        assertIs<LocalNetworkPermissionException>(guard.connectFailure(address, "10.0.0.7:22", eperm))
        val wrapped = IOException("connect failed", eperm)
        assertIs<LocalNetworkPermissionException>(guard.connectFailure(address, "10.0.0.7:22", wrapped))
    }

    @Test
    fun otherConnectErrorsStayNetworkErrors() {
        val private = InetSocketAddress(InetAddress.getByName("192.168.2.10"), 22)
        val public = InetSocketAddress(InetAddress.getByName("140.82.112.3"), 22)
        val timeout = SocketTimeoutException("connect timed out")
        val refused = ConnectException("Connection refused")

        assertSame(refused, guard(permitted = false).connectFailure(private, "routed-box.lan:22", refused))
        assertSame(timeout, guard(permitted = false).connectFailure(public, "github.com:22", timeout))
        assertSame(timeout, guard(permitted = true).connectFailure(private, "routed-box.lan:22", timeout))
        val unresolved = InetSocketAddress.createUnresolved("routed-box.lan", 22)
        assertSame(timeout, guard(permitted = false).connectFailure(unresolved, "routed-box.lan:22", timeout))
    }

    @Test
    fun anIpLiteralOnTheWifiNetworkNeedsThePermission() {
        val guard = guard(permitted = false)

        listOf("192.168.1.42", "192.168.1.255", "2001:db8:1:2::42", "fe80::1", "224.0.0.251").forEach {
            assertFailsWith<LocalNetworkPermissionException>(it) { guard.socketAddressFor(it, 22) }
        }
        assertEquals("[fe80::1]:22", assertFailsWith<LocalNetworkPermissionException> {
            guard.socketAddressFor("fe80::1", 22)
        }.hostPort)
    }

    @Test
    fun anOwnAddressOfTheDeviceNeedsNoPermission() {
        val guard = guard(permitted = false)

        // For example, herdr in a terminal app on the tablet, reached by the own Wi-Fi address.
        assertEquals(InetAddress.getByName("192.168.1.5"), guard.socketAddressFor("192.168.1.5", 22).address)
        assertFalse(guard.needsPermission("2001:db8:1:2::5"))
    }

    @Test
    fun withAnHttpProxyOnAllNetworksTheGuardRefusesNothing() {
        val guard = guard(permitted = false, isProtected = false)

        assertEquals(InetAddress.getByName("192.168.1.42"), guard.socketAddressFor("192.168.1.42", 22).address)
        assertEquals(InetAddress.getByName("224.0.0.251"), guard.socketAddressFor("224.0.0.251", 5353).address)
        assertFalse(guard.needsPermission("devbox.local"))
        val timeout = SocketTimeoutException("connect timed out")
        val routed = InetSocketAddress(InetAddress.getByName("192.168.2.10"), 22)
        assertSame(timeout, guard.connectFailure(routed, "routed-box.lan:22", timeout))
    }

    @Test
    fun anMdnsNameNeedsThePermissionWithNoQuery() {
        val guard = guard(permitted = false)

        assertFailsWith<LocalNetworkPermissionException> { guard.socketAddressFor("devbox.local", 22) }
        assertTrue(guard.needsPermission("DevBox.Local."))
        assertTrue(resolved.isEmpty(), "The guard resolved $resolved")
    }

    @Test
    fun withoutThePermissionTheGuardSelectsTheFirstAddressThatIsNotLocal() {
        val guard = guard(permitted = false, prefixes = listOfNotNull(prefix("fd00::5", 64)))

        extraNames["dual-box.example"] = listOf("fd00::42", "203.0.113.7")
        assertEquals(InetAddress.getByName("203.0.113.7"), guard.socketAddressFor("dual-box.example", 22).address)
        assertEquals(InetAddress.getByName("140.82.112.3"), guard.socketAddressFor("github.com", 22).address)
        assertFalse(guard.needsPermission("github.com"))
    }

    @Test
    fun theNextSocketUsesTheResolutionOfTheCheckOneTime() {
        val guard = guard(permitted = false)

        assertFalse(guard.needsPermission("routed-box.lan"))
        guard.socketAddressFor("routed-box.lan", 22)
        assertEquals(listOf("routed-box.lan"), resolved)

        // The next socket resolves again.
        guard.socketAddressFor("routed-box.lan", 22)
        assertEquals(listOf("routed-box.lan", "routed-box.lan"), resolved)

        // An old resolution is not used.
        assertFalse(guard.needsPermission("routed-box.lan"))
        timeSource += 11.seconds
        guard.socketAddressFor("routed-box.lan", 22)
        assertEquals(4, resolved.size)
    }

    @Test
    fun aNameWithNoAddressFailsWithANetworkError() {
        val guard = guard(permitted = false)

        assertFailsWith<UnknownHostException> { guard.socketAddressFor("missing.example", 22) }
        // The connection then fails with its own network error, so the app does not ask for the permission.
        assertFalse(guard.needsPermission("missing.example"))
    }

    @Test
    fun aSocketWithAnAddressChecksTheAddress() {
        val local = InetAddress.getByName("192.168.1.42")
        val peer = InetAddress.getByName("100.101.102.103")

        val error = assertFailsWith<LocalNetworkPermissionException> {
            guard(permitted = false).requireAccess(local, "devbox.lan:60001")
        }
        assertEquals("devbox.lan:60001", error.hostPort)
        guard(permitted = false).requireAccess(peer, "ts-box:60001")
        guard(permitted = true).requireAccess(local, "devbox.lan:60001")
    }

    @Test
    fun aLocalNetworkErrorIsItsOwnResultAndNotANetworkError() {
        val error = LocalNetworkPermissionException("devbox.lan:22")
        val expected = SshAuthResult.LocalNetworkBlocked("devbox.lan:22")

        assertEquals(expected, RealSshBackend.toAuthResult(error))
        // JSch wraps an error of the socket factory that is not a RuntimeException.
        assertEquals(expected, RealSshBackend.toAuthResult(JSchException("Session.connect: $error", error)))
        assertEquals(
            expected,
            RealSshBackend.toAuthResult(BastionConnectException("jump@devbox.lan:22", JSchException("stopped", error))),
        )
        // A timeout keeps its kind, although its cause is an IOException.
        val timedOut = LocalNetworkPermissionException("routed-box.lan:22", timedOut = true, SocketTimeoutException())
        assertEquals(
            SshAuthResult.LocalNetworkBlocked("routed-box.lan:22", timedOut = true),
            RealSshBackend.toAuthResult(timedOut),
        )
    }

    /** Parses an address literal. A name that is not in [names] or [extraNames] has no address. */
    private fun literal(host: String): InetAddress =
        if (host.first().isDigit() || ':' in host) InetAddress.getByName(host) else throw UnknownHostException(host)

    private fun prefix(text: String, length: Int): AddressPrefix? =
        LocalNetworkAddresses.localPrefixOf(InetAddress.getByName(text).address, length)
}
