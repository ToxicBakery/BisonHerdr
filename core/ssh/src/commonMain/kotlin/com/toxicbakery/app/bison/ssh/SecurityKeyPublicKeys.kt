package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod
import kotlin.io.encoding.Base64

/**
 * Makes the OpenSSH public key of an enrolled [SshAuthMethod.HardwareSecurityKey] (`PROTOCOL.u2f`).
 *
 * - `sk-ssh-ed25519@openssh.com`: `string` key type, `string` 32-byte public key, `string` application.
 * - `sk-ecdsa-sha2-nistp256@openssh.com`: `string` key type, `string` `nistp256`, `string` 65-byte uncompressed
 *   point, `string` application.
 */
object SecurityKeyPublicKeys {

    /** The comment at the end of the `authorized_keys` line. */
    const val DEFAULT_COMMENT: String = "bison-tablet-security-key"

    /** The length of an Ed25519 public key. */
    const val ED25519_PUBLIC_KEY_LENGTH: Int = 32

    /** The length of an uncompressed P-256 point: the `0x04` prefix, then x and y. */
    const val P256_POINT_LENGTH: Int = 65

    private const val P256_CURVE_NAME = "nistp256"
    private const val UNCOMPRESSED_POINT_PREFIX: Byte = 0x04

    /**
     * Returns the OpenSSH public key blob of [key]. Throws a [HardwareKeyException] with
     * [HardwareKeyException.Reason.NotEnrolled] when [key] has no public key, and with
     * [HardwareKeyException.Reason.NotSupported] for a key type that is not FIDO2. Throws an
     * [IllegalArgumentException] when the public key has an incorrect length.
     */
    fun blob(key: SshAuthMethod.HardwareSecurityKey): ByteArray {
        val encoded = key.publicKey
        if (encoded.isNullOrEmpty()) throw HardwareKeyException(HardwareKeyException.Reason.NotEnrolled)
        val publicKey = Base64.decode(encoded)
        val application = key.applicationId.encodeToByteArray()
        val out = SshWireWriter()
        when (key.keyType) {
            HardwareKeyType.SkSshEd25519 -> {
                require(publicKey.size == ED25519_PUBLIC_KEY_LENGTH) {
                    "An Ed25519 public key has $ED25519_PUBLIC_KEY_LENGTH bytes (got ${publicKey.size})"
                }
                out.putString(key.keyType.opensshKeyType.encodeToByteArray())
                out.putString(publicKey)
                out.putString(application)
            }
            HardwareKeyType.SkEcdsaSha2Nistp256 -> {
                require(publicKey.size == P256_POINT_LENGTH && publicKey[0] == UNCOMPRESSED_POINT_PREFIX) {
                    "A P-256 public key is a $P256_POINT_LENGTH-byte uncompressed point (got ${publicKey.size} bytes)"
                }
                out.putString(key.keyType.opensshKeyType.encodeToByteArray())
                out.putString(P256_CURVE_NAME.encodeToByteArray())
                out.putString(publicKey)
                out.putString(application)
            }
            HardwareKeyType.PivSmartCard ->
                throw HardwareKeyException(HardwareKeyException.Reason.NotSupported, key.keyType.opensshKeyType)
        }
        return out.toByteArray()
    }

    /**
     * Returns the `authorized_keys` line of [key]: the key type, the Base64 blob, and [comment]. Throws the same
     * errors as [blob].
     */
    fun authorizedKeyLine(key: SshAuthMethod.HardwareSecurityKey, comment: String = DEFAULT_COMMENT): String =
        "${key.keyType.opensshKeyType} ${Base64.encode(blob(key))} $comment"

    /** Writes the SSH wire types `string` and `uint32`. */
    private class SshWireWriter {
        private val bytes = ArrayList<Byte>()

        fun putUInt32(value: Int) {
            bytes += (value ushr 24).toByte()
            bytes += (value ushr 16).toByte()
            bytes += (value ushr 8).toByte()
            bytes += value.toByte()
        }

        fun putString(value: ByteArray) {
            putUInt32(value.size)
            value.forEach { bytes += it }
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
