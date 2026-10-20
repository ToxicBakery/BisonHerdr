package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.app.bison.model.ProxyJumpConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress

/**
 * Resolved SSH connection route (either direct/Tailscale or via an SSH bastion `direct-tcpip` tunnel).
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
    val directTcpIpPayload: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedSshRoute) return false
        return entryHost == other.entryHost &&
            entryPort == other.entryPort &&
            entryUsername == other.entryUsername &&
            targetHost == other.targetHost &&
            targetPort == other.targetPort &&
            targetUsername == other.targetUsername &&
            viaBastion == other.viaBastion &&
            isTailscaleMesh == other.isTailscaleMesh &&
            (directTcpIpPayload contentEquals other.directTcpIpPayload)
    }

    override fun hashCode(): Int {
        var result = entryHost.hashCode()
        result = 31 * result + entryPort
        result = 31 * result + entryUsername.hashCode()
        result = 31 * result + targetHost.hashCode()
        result = 31 * result + targetPort
        result = 31 * result + targetUsername.hashCode()
        result = 31 * result + viaBastion.hashCode()
        result = 31 * result + isTailscaleMesh.hashCode()
        result = 31 * result + (directTcpIpPayload?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Multi-hop SSH bastion tunneling (`ProxyJump` / RFC 4254 `direct-tcpip` channel forwarding)
 * and Tailscale `100.x.y.z` CGNAT / MagicDNS (`*.ts.net`) resolution helper.
 */
class ProxyJumpTunnel(
    private val sshDebugLog: SshDebugLog? = null,
) {

    /**
     * Resolves the SSH transport route for [machine], including Tailscale CGNAT/MagicDNS detection
     * and RFC 4254 `direct-tcpip` channel open payload construction when [HostMachine.proxyJump] is configured.
     */
    fun resolveRoute(machine: HostMachine): ResolvedSshRoute {
        val proxy = machine.proxyJump
        val normalizedTargetHost = normalizeHost(machine.hostname, proxy?.tailscaleMagicDns == true)
        val isTailscale = isTailscaleAddress(normalizedTargetHost) ||
            (proxy != null && isTailscaleAddress(proxy.bastionHost))

        return if (proxy != null && proxy.bastionHost.isNotBlank()) {
            val payload = buildDirectTcpIpChannelPayload(
                targetHost = normalizedTargetHost,
                targetPort = machine.port,
                originatorAddress = "127.0.0.1",
                originatorPort = 0,
            )
            sshDebugLog?.record(
                machineId = machine.id,
                tag = "ProxyJump",
                message = "Routing ${machine.username}@$normalizedTargetHost:${machine.port} via bastion " +
                    "${proxy.bastionUser}@${proxy.bastionHost}:${proxy.bastionPort}",
            )
            ResolvedSshRoute(
                entryHost = proxy.bastionHost.trim(),
                entryPort = proxy.bastionPort,
                entryUsername = proxy.bastionUser.trim(),
                targetHost = normalizedTargetHost,
                targetPort = machine.port,
                targetUsername = machine.username,
                viaBastion = true,
                isTailscaleMesh = isTailscale,
                directTcpIpPayload = payload,
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
                directTcpIpPayload = null,
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

    /**
     * Encodes the RFC 4254 Section 7.2 `SSH_MSG_CHANNEL_OPEN` (`direct-tcpip`) channel-specific payload:
     * - `string`: host to connect
     * - `uint32`: port to connect
     * - `string`: originator IP address
     * - `uint32`: originator port
     */
    fun buildDirectTcpIpChannelPayload(
        targetHost: String,
        targetPort: Int,
        originatorAddress: String = "127.0.0.1",
        originatorPort: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            dos.writeInt(hostBytes.size)
            dos.write(hostBytes)
            dos.writeInt(targetPort)
            val origBytes = originatorAddress.toByteArray(Charsets.UTF_8)
            dos.writeInt(origBytes.size)
            dos.write(origBytes)
            dos.writeInt(originatorPort)
        }
        return out.toByteArray()
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
