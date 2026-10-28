package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import java.net.InetSocketAddress

/**
 * The SSH route to a machine: a direct connection, or a connection through an SSH bastion (`ProxyJump`).
 *
 * For a direct route, the entry fields and the target fields are the same. For a bastion route ([viaBastion] is
 * true), the entry fields are the bastion. The client logs in to the bastion first, and then logs in to the target
 * over a `direct-tcpip` channel of the bastion session. All commands run on the target.
 */
data class ResolvedSshRoute(
    val entryHost: String,
    val entryPort: Int,
    val entryUsername: String,
    val targetHost: String,
    val targetPort: Int,
    val targetUsername: String,
    val viaBastion: Boolean,
    val isTailscaleMesh: Boolean,
) {
    /** The `user@host:port` text of the bastion, for log lines and errors. Use it only when [viaBastion] is true. */
    val bastionAddress: String
        get() = "$entryUsername@${KnownHost.formatHostPort(entryHost, entryPort)}"

    /** The `host:port` text of the target, for log lines and errors. */
    val targetAddress: String
        get() = KnownHost.formatHostPort(targetHost, targetPort)
}

/**
 * Multi-hop SSH bastion tunneling (`ProxyJump` / RFC 4254 `direct-tcpip` channel forwarding)
 * and Tailscale `100.x.y.z` CGNAT / MagicDNS (`*.ts.net`) resolution helper.
 */
class ProxyJumpTunnel(
    private val sshDebugLog: SshDebugLog? = null,
) {

    /**
     * Resolves the SSH transport route for [machine], including Tailscale CGNAT/MagicDNS detection.
     * When [HostMachine.proxyJump] has a bastion host, the entry fields of the route are the bastion. A blank
     * bastion user gives the user of [machine].
     */
    fun resolveRoute(machine: HostMachine): ResolvedSshRoute {
        val proxy = machine.proxyJump
        val normalizedTargetHost = normalizeHost(machine.hostname, proxy?.tailscaleMagicDns == true)
        val isTailscale = isTailscaleAddress(normalizedTargetHost) ||
            (proxy != null && isTailscaleAddress(proxy.bastionHost))

        return if (proxy != null && proxy.bastionHost.isNotBlank()) {
            val bastionUser = proxy.bastionUser.trim().ifBlank { machine.username }
            sshDebugLog?.record(
                machineId = machine.id,
                tag = "ProxyJump",
                message = "Routing ${machine.username}@$normalizedTargetHost:${machine.port} via bastion " +
                    "$bastionUser@${proxy.bastionHost}:${proxy.bastionPort}",
            )
            ResolvedSshRoute(
                entryHost = proxy.bastionHost.trim(),
                entryPort = proxy.bastionPort,
                entryUsername = bastionUser,
                targetHost = normalizedTargetHost,
                targetPort = machine.port,
                targetUsername = machine.username,
                viaBastion = true,
                isTailscaleMesh = isTailscale,
            )
        } else {
            if (isTailscale) {
                sshDebugLog?.record(
                    machineId = machine.id,
                    tag = "Tailscale",
                    message = "Detected Tailscale mesh endpoint $normalizedTargetHost:${machine.port}",
                )
            }
            ResolvedSshRoute(
                entryHost = normalizedTargetHost,
                entryPort = machine.port,
                entryUsername = machine.username,
                targetHost = normalizedTargetHost,
                targetPort = machine.port,
                targetUsername = machine.username,
                viaBastion = false,
                isTailscaleMesh = isTailscale,
            )
        }
    }

    /**
     * Returns `true` when [host] is a Tailscale CGNAT IPv4 address (`100.64.0.0/10`, i.e. `100.64.x.y`..`100.127.x.y`)
     * or a Tailscale MagicDNS hostname (`*.ts.net`).
     */
    fun isTailscaleAddress(host: String): Boolean {
        val trimmed = host.trim().lowercase()
        if (trimmed.endsWith(".ts.net")) return true
        val octets = trimmed.split('.')
        if (octets.size == 4) {
            val first = octets[0].toIntOrNull() ?: return false
            val second = octets[1].toIntOrNull() ?: return false
            val third = octets[2].toIntOrNull() ?: return false
            val fourth = octets[3].toIntOrNull() ?: return false
            if (first == 100 && second in 64..127 && third in 0..255 && fourth in 0..255) {
                return true
            }
        }
        return false
    }

    fun toSocketAddress(route: ResolvedSshRoute): InetSocketAddress =
        InetSocketAddress.createUnresolved(route.entryHost, route.entryPort)

    private fun normalizeHost(host: String, tailscaleMagicDns: Boolean): String {
        val trimmed = host.trim()
        if (tailscaleMagicDns && '.' !in trimmed && trimmed.isNotEmpty()) {
            return "$trimmed.ts.net"
        }
        return trimmed
    }

    companion object {
        fun describeProxyJump(config: ProxyJumpConfig): String =
            "${config.bastionUser}@${config.bastionHost}:${config.bastionPort}"
    }
}
