package com.toxicbakery.app.bison.model

import kotlinx.serialization.Serializable

/**
 * The SSH host key that the user accepted for one [host] and [port].
 *
 * The app compares the key that a server sends with this entry before it sends credentials.
 */
@Serializable
data class KnownHost(
    /** The host name or the IP address that the client connects to. */
    val host: String,
    /** The TCP port that the client connects to. */
    val port: Int,
    /** The SSH key type, for example `ssh-ed25519` or `ecdsa-sha2-nistp256`. */
    val keyType: String,
    /** The OpenSSH SHA-256 fingerprint of [publicKey], in the form `SHA256:<base64>`. */
    val fingerprint: String,
    /** The public key blob in Base64, the same as the key field of an OpenSSH `known_hosts` line. */
    val publicKey: String,
) {
    /** The `host:port` text that identifies this entry. An IPv6 address gets square brackets. */
    val hostPort: String
        get() = formatHostPort(host, port)

    /**
     * Returns true when this entry is for [host] and [port]. The host comparison ignores letter case and
     * the spaces at the two ends, because a host name is not case-sensitive.
     */
    fun isFor(host: String, port: Int): Boolean =
        this.port == port && this.host.trim().equals(host.trim(), ignoreCase = true)

    companion object {
        /** Returns the `host:port` text for [host] and [port]. An IPv6 address gets square brackets. */
        fun formatHostPort(host: String, port: Int): String =
            if (':' in host) "[$host]:$port" else "$host:$port"
    }
}
