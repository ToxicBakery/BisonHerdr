package com.toxicbakery.app.bison.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration profile for a remote Herdr host machine.
 */
@Serializable
data class HostMachine(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authMethod: SshAuthMethod = SshAuthMethod.KeystoreKey(),
    val transportMode: TransportMode = TransportMode.AutoPreferMosh,
    val moshServerPath: String? = null,
    val moshUdpPortRange: String = DEFAULT_MOSH_UDP_PORT_RANGE,
    val herdrSessionName: String? = null,
    val proxyJump: ProxyJumpConfig? = null,
    val themeOverrideId: String? = null,
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
) {
    /** Formatted `user@host` or `user@host:port` string for display in HUD pills and cards. */
    val displayAddress: String
        get() = if (port == DEFAULT_SSH_PORT) "$username@$hostname" else "$username@$hostname:$port"

    /** True when [port] is a valid TCP port (1..65535). */
    val isValidPort: Boolean
        get() = port in 1..65535

    /** Parses [moshUdpPortRange] (`"start:end"`) into an [IntRange], or `60000..61000` on invalid syntax. */
    fun parsedMoshPortRange(): IntRange {
        val parts = moshUdpPortRange.split(':')
        if (parts.size == 2) {
            val start = parts[0].trim().toIntOrNull()
            val end = parts[1].trim().toIntOrNull()
            if (start != null && end != null && start in 1..65535 && end in start..65535) {
                return start..end
            }
        }
        return 60000..61000
    }

    companion object {
        const val DEFAULT_SSH_PORT: Int = 22
        const val DEFAULT_MOSH_UDP_PORT_RANGE: String = "60000:61000"
        const val DEFAULT_FONT_SIZE_SP: Float = 13f
    }
}

/**
 * Transport selection policy for connecting to a remote Herdr server.
 */
@Serializable
enum class TransportMode {
    /** Probe `mosh-server` and negotiate UDP SSP first; fall back to SSH Endpoint Gen 1 if UDP is blocked. */
    AutoPreferMosh,

    /** Require Mosh UDP SSP backbone and fail connection if `mosh-server` or UDP is unreachable. */
    MoshOnly,

    /** Connect exclusively over SSH TCP using `herdr remote-client-bridge` (Endpoint Generation 1). */
    SshEndpointOnly,
}

/**
 * Authentication strategy for SSH2 bootstrap and Herdr control sideband channels.
 */
@Serializable
sealed interface SshAuthMethod {

    /**
     * True when this version of the app can log in with this method. This version cannot log in with
     * [HardwareSecurityKey]. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 keys.
     */
    val isSupported: Boolean
        get() = this !is HardwareSecurityKey

    @Serializable
    @SerialName("keystore_key")
    data class KeystoreKey(
        val keyAlias: String = KeyAlgorithm.Ed25519.defaultKeystoreAlias,
        val algorithm: KeyAlgorithm = KeyAlgorithm.Ed25519,
        val requireBiometric: Boolean = false,
    ) : SshAuthMethod

    @Serializable
    @SerialName("password")
    data class Password(
        val encryptedPasswordRef: String = "",
        val keyPassphraseRef: String? = null,
    ) : SshAuthMethod

    /**
     * A FIDO2 or PIV hardware security key.
     *
     * This version does not support this method, and [isSupported] is false. The configure screen does
     * not offer it, and the transport does not connect a machine that uses it. The model stays, so that
     * an old `machines.json` file still loads. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23)
     * adds FIDO2 security keys.
     */
    @Serializable
    @SerialName("hardware_security_key")
    data class HardwareSecurityKey(
        val keyType: HardwareKeyType = HardwareKeyType.SkSshEd25519,
        val transport: HardwareKeyTransport = HardwareKeyTransport.UsbAndNfc,
        val applicationId: String = "ssh:",
    ) : SshAuthMethod
}

/**
 * Supported asymmetric key algorithms for Android Keystore SSH keys.
 */
@Serializable
enum class KeyAlgorithm(
    val sshKeyType: String,
    /** The default key store alias for this algorithm. Each algorithm has its own alias. */
    val defaultKeystoreAlias: String,
) {
    Ed25519("ssh-ed25519", "bison_default_ed25519"),
    Rsa4096("rsa-sha2-512", "bison_default_rsa4096"),
    EcdsaP256("ecdsa-sha2-nistp256", "bison_default_ecdsa_p256"),
}

/**
 * Supported hardware security key types (OpenSSH FIDO2 `sk-*` and PIV smartcard).
 */
@Serializable
enum class HardwareKeyType(val opensshKeyType: String) {
    SkSshEd25519("sk-ssh-ed25519@openssh.com"),
    SkEcdsaSha2Nistp256("sk-ecdsa-sha2-nistp256@openssh.com"),
    PivSmartCard("ssh-rsa"),
}

/**
 * Physical transport allowed when communicating with a hardware security key (YubiKey / FIDO2).
 */
@Serializable
enum class HardwareKeyTransport {
    UsbAndNfc,
    NfcOnly,
    UsbOnly,
}

/**
 * Multi-hop SSH bastion (`ProxyJump` / `direct-tcpip`) and Tailscale MagicDNS configuration.
 */
@Serializable
data class ProxyJumpConfig(
    val bastionHost: String,
    val bastionPort: Int = 22,
    val bastionUser: String,
    val tailscaleMagicDns: Boolean = false,
)
