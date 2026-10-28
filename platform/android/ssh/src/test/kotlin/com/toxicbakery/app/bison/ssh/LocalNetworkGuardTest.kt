package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.JSchException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [LocalNetworkGuard] with a resolver of the test, and the result kind of a [LocalNetworkPermissionException].
 * The tests make no DNS query: [InetAddress.getByName] only parses the literal addresses.
 */
class LocalNetworkGuardTest {

    /** The names that the resolver of [guard] got, in order. */
    private val resolved = mutableListOf<String>()

    /** The addresses of each name for the resolver of [guard]. */
    private val names = mapOf(
        "devbox.lan" to listOf("192.168.1.42"),
        "ts-box.tailnet.ts.net" to listOf("100.101.102.103"),
        "ula-box.lan" to listOf("fd00::42"),
        "dual-box.example" to listOf("fd00::42", "203.0.113.7"),
        "v6-box.lan" to listOf("2001:db8:1:2::42"),
        "github.com" to listOf("140.82.112.3"),
    )

    private fun guard(permitted: Boolean): LocalNetworkGuard =
        LocalNetworkGuard(
            hasPermission = { permitted },
            onLinkPrefixes = { listOf(AddressPrefix(InetAddress.getByName("2001:db8:1:2::").address, 64)) },
            resolve = { host ->
                resolved += host
                names[host]?.map(InetAddress::getByName) ?: listOf(literal(host))
            },
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
    fun aNameThatResolvesToALocalAddressNeedsThePermission() {
        val guard = guard(permitted = false)

        listOf("devbox.lan", "ula-box.lan", "v6-box.lan").forEach { host ->
            val error = assertFailsWith<LocalNetworkPermissionException> { guard.socketAddressFor(host, 2222) }
            assertEquals("$host:2222", error.hostPort)
            assertTrue(guard.needsPermission(host), host)
        }
    }

    @Test
    fun aTailscaleAddressNeedsThePermission() {
        val guard = guard(permitted = false)

        assertFailsWith<LocalNetworkPermissionException> { guard.socketAddressFor("ts-box.tailnet.ts.net", 22) }
        assertFailsWith<LocalNetworkPermissionException> { guard.socketAddressFor("100.64.0.12", 22) }
        assertTrue(guard.needsPermission("100.127.0.1"))
    }

    @Test
    fun anIpLiteralOnTheLocalNetworkNeedsThePermission() {
        val guard = LocalNetworkGuard(hasPermission = { false })

        listOf("192.168.1.42", "10.0.0.7", "172.20.1.1", "169.254.10.10", "fe80::1", "fd7a:115c:a1e0::5").forEach {
            assertFailsWith<LocalNetworkPermissionException>(it) { guard.socketAddressFor(it, 22) }
        }
        assertEquals("[fe80::1]:22", assertFailsWith<LocalNetworkPermissionException> {
            guard.socketAddressFor("fe80::1", 22)
        }.hostPort)
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
        val guard = guard(permitted = false)

        assertEquals(InetAddress.getByName("203.0.113.7"), guard.socketAddressFor("dual-box.example", 22).address)
        assertEquals(InetAddress.getByName("140.82.112.3"), guard.socketAddressFor("github.com", 22).address)
        assertFalse(guard.needsPermission("github.com"))
        assertFalse(guard.needsPermission("dual-box.example"))
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
        val public = InetAddress.getByName("140.82.112.3")

        val error = assertFailsWith<LocalNetworkPermissionException> {
            guard(permitted = false).requireAccess(local, "devbox.lan:60001")
        }
        assertEquals("devbox.lan:60001", error.hostPort)
        guard(permitted = false).requireAccess(public, "github.com:60001")
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
            RealSshBackend.toAuthResult(BastionConnectException("jump@devbox.lan:22", JSchException("blocked", error))),
        )
    }

    /** Parses an address literal. A name that is not in [names] has no address. */
    private fun literal(host: String): InetAddress =
        if (host.first().isDigit() || ':' in host) InetAddress.getByName(host) else throw UnknownHostException(host)

    @Test
    fun theDefaultResolverParsesALiteralWithNoQuery() {
        val guard = LocalNetworkGuard(hasPermission = { false })

        assertEquals(InetAddress.getByName("8.8.8.8"), guard.socketAddressFor("8.8.8.8", 22).address)
    }
}
