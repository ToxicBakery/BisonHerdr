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

        /** The maximum length of a Herdr session name. See [isValidHerdrSessionName]. */
        const val MAX_HERDR_SESSION_NAME_LENGTH: Int = 64

        /**
         * Returns true when Herdr accepts [name] as a session name. The rule is `validate_name` in `src/session.rs`
         * of Herdr 0.9.5. A valid name has 1 to [MAX_HERDR_SESSION_NAME_LENGTH] characters. It has only the ASCII
         * letters, the digits, `.`, `_`, and `-`. The names `.` and `..` are not valid.
         *
         * Herdr refuses a command with a name that is not valid, and writes the error only to standard error.
         */
        fun isValidHerdrSessionName(name: String): Boolean =
            name.length in 1..MAX_HERDR_SESSION_NAME_LENGTH &&
                name != "." &&
                name != ".." &&
                name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    }
}

/**
 * Transport selection policy for connecting to a remote Herdr server.
 */
@Serializable
enum class TransportMode {
    /**
     * Prefer Mosh UDP when it is available. This version has no Mosh transport (issue #22), so the app connects
     * over SSH ([ActiveBackbone.SshEndpoint]). It checks for `mosh-server` and tells when the host has it.
     */
    AutoPreferMosh,

    /**
     * Use only Mosh UDP. This version has no Mosh transport (issue #22), so the app connects over SSH, as for
     * [AutoPreferMosh].
     */
    MoshOnly,

    /** Connect only over SSH ([ActiveBackbone.SshEndpoint]). The app does not look for `mosh-server`. */
    SshEndpointOnly,
}

/**
 * The login method of the SSH session of a machine. A ProxyJump bastion uses the same method.
 */
@Serializable
sealed interface SshAuthMethod {

    /**
     * Returns true when this build can log in with this method. A [HardwareSecurityKey] needs
     * [FeatureFlags.hardwareSecurityKeys] and a FIDO2 key type ([HardwareKeyType.isFido2]). All other methods are
     * always supported. Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 keys.
     */
    fun isSupported(featureFlags: FeatureFlags): Boolean =
        when (this) {
            is HardwareSecurityKey -> featureFlags.hardwareSecurityKeys && keyType.isFido2
            is KeystoreKey, is Password -> true
        }

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
     * A FIDO2 hardware security key with an OpenSSH `sk` credential, for example a YubiKey 5.
     *
     * The "Enroll key" action of the configure screen makes the credential on the key and fills [credentialId],
     * [publicKey], and [residentKey]. These values are public data, so `machines.json` keeps them. The private key
     * stays on the security key. A file from an earlier version has no such fields. Such a machine loads with the
     * default values and is not [isEnrolled].
     *
     * The app uses this method only when [FeatureFlags.hardwareSecurityKeys] is true. See [isSupported].
     * Issue #23 (https://github.com/ToxicBakery/BisonHerdr/issues/23) adds FIDO2 security keys.
     */
    @Serializable
    @SerialName("hardware_security_key")
    data class HardwareSecurityKey(
        val keyType: HardwareKeyType = HardwareKeyType.SkSshEd25519,
        val transport: HardwareKeyTransport = HardwareKeyTransport.UsbAndNfc,
        /** The OpenSSH application string, and the FIDO2 relying party ID. OpenSSH uses `ssh:` by default. */
        val applicationId: String = DEFAULT_APPLICATION_ID,
        /** The FIDO2 credential ID from `makeCredential`, in standard Base64. Null until the key is enrolled. */
        val credentialId: String? = null,
        /**
         * The public key of the credential, in standard Base64. It is the 32-byte Ed25519 key for
         * [HardwareKeyType.SkSshEd25519], and the 65-byte uncompressed P-256 point for
         * [HardwareKeyType.SkEcdsaSha2Nistp256]. Null until the key is enrolled.
         */
        val publicKey: String? = null,
        /** True when the credential is a discoverable (resident) credential on the key. */
        val residentKey: Boolean = false,
    ) : SshAuthMethod {

        /** True when the machine has a credential ID and a public key from an enrollment. */
        val isEnrolled: Boolean
            get() = !credentialId.isNullOrEmpty() && !publicKey.isNullOrEmpty()

        companion object {
            /** The default OpenSSH application string of a security key credential. */
            const val DEFAULT_APPLICATION_ID: String = "ssh:"
        }
    }
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
 * Hardware security key types: the OpenSSH FIDO2 `sk-*` types and the PIV smart card.
 *
 * The app supports only the FIDO2 types ([isFido2]). [PivSmartCard] stays, so that an old `machines.json` file
 * still loads.
 */
@Serializable
enum class HardwareKeyType(val opensshKeyType: String) {
    SkSshEd25519("sk-ssh-ed25519@openssh.com"),
    SkEcdsaSha2Nistp256("sk-ecdsa-sha2-nistp256@openssh.com"),
    PivSmartCard("ssh-rsa"),
    ;

    /** True for an OpenSSH FIDO2 `sk-*` type. The app can enroll and use only these types. */
    val isFido2: Boolean
        get() = this != PivSmartCard
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
 * Multi-hop SSH bastion (`ProxyJump` / `direct-tcpip`) configuration.
 *
 * The bastion resolves the host name of the target machine. Thus a bastion that uses Tailscale MagicDNS resolves a
 * MagicDNS short name with no added option. An earlier version had a `tailscaleMagicDns` field that added `.ts.net`
 * to a short name. A real MagicDNS name is `<machine>.<tailnet>.ts.net`, so that name never resolved. The store
 * JSON (`appJson`) ignores unknown keys, so an old `machines.json` with the field still loads. The next save
 * removes the field.
 */
@Serializable
data class ProxyJumpConfig(
    val bastionHost: String,
    val bastionPort: Int = 22,
    val bastionUser: String,
)
