package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Converts the FIDO2 data of a security key to the OpenSSH `PROTOCOL.u2f` wire format.
 *
 * The authenticator signs `authenticatorData || clientDataHash`. The signer sends `SHA256(data)` as the client data
 * hash, and the authenticator data starts with `SHA256(application) || flags || counter`. Thus the authenticator
 * signs the same bytes that OpenSSH verifies: `SHA256(application) || flags || counter || SHA256(data)`.
 */
internal object SecurityKeyWireFormat {

    /** The COSE algorithm EdDSA (Ed25519). */
    const val COSE_ALG_EDDSA: Int = -8

    /** The COSE algorithm ES256 (ECDSA with P-256 and SHA-256). */
    const val COSE_ALG_ES256: Int = -7

    /** The length of the authenticator data with no attested credential and no extensions. */
    const val MIN_AUTHENTICATOR_DATA_LENGTH: Int = 37

    /** The length of a raw Ed25519 signature. */
    const val ED25519_SIGNATURE_LENGTH: Int = 64

    private const val SHA256_LENGTH = 32
    private const val P256_COORDINATE_LENGTH = 32
    private const val UNCOMPRESSED_POINT_PREFIX: Byte = 0x04

    // COSE key labels and values (RFC 9053).
    private const val COSE_KEY_KTY = 1
    private const val COSE_KEY_ALG = 3
    private const val COSE_KEY_CRV = -1
    private const val COSE_KEY_X = -2
    private const val COSE_KEY_Y = -3
    private const val COSE_KTY_OKP = 1
    private const val COSE_KTY_EC2 = 2
    private const val COSE_CRV_P256 = 1
    private const val COSE_CRV_ED25519 = 6

    private const val DER_SEQUENCE = 0x30
    private const val DER_INTEGER = 0x02

    /** Returns the COSE algorithm for [keyType]. Throws a [HardwareKeyException] for a key type that is not FIDO2. */
    fun coseAlgorithmOf(keyType: HardwareKeyType): Int =
        when (keyType) {
            HardwareKeyType.SkSshEd25519 -> COSE_ALG_EDDSA
            HardwareKeyType.SkEcdsaSha2Nistp256 -> COSE_ALG_ES256
            HardwareKeyType.PivSmartCard ->
                throw HardwareKeyException(HardwareKeyException.Reason.NotSupported, keyType.opensshKeyType)
        }

    /**
     * Returns the raw public key from the COSE key [cose] of a new credential: the 32-byte Ed25519 key, or the
     * 65-byte uncompressed P-256 point. Throws a [HardwareKeyException] when the key does not agree with [keyType].
     */
    fun publicKeyFromCose(keyType: HardwareKeyType, cose: Map<Int, *>): ByteArray {
        val algorithm = coseAlgorithmOf(keyType)
        val kty = cose[COSE_KEY_KTY] as? Int
        val alg = cose[COSE_KEY_ALG] as? Int
        val crv = cose[COSE_KEY_CRV] as? Int
        val x = cose[COSE_KEY_X] as? ByteArray
        return when (keyType) {
            HardwareKeyType.SkSshEd25519 -> {
                if (kty != COSE_KTY_OKP || alg != algorithm || crv != COSE_CRV_ED25519 ||
                    x?.size != SecurityKeyPublicKeys.ED25519_PUBLIC_KEY_LENGTH
                ) {
                    throw unexpectedKey(keyType, kty, alg, crv)
                }
                x
            }
            HardwareKeyType.SkEcdsaSha2Nistp256 -> {
                val y = cose[COSE_KEY_Y] as? ByteArray
                if (kty != COSE_KTY_EC2 || alg != algorithm || crv != COSE_CRV_P256 ||
                    x?.size != P256_COORDINATE_LENGTH || y?.size != P256_COORDINATE_LENGTH
                ) {
                    throw unexpectedKey(keyType, kty, alg, crv)
                }
                byteArrayOf(UNCOMPRESSED_POINT_PREFIX) + x + y
            }
            HardwareKeyType.PivSmartCard -> error("coseAlgorithmOf refuses a PIV key")
        }
    }

    /**
     * Returns the OpenSSH signature blob for the assertion of a security key. [authenticatorData] gives the flags
     * and the counter. Throws a [HardwareKeyException] when the authenticator data is too short, or when the key
     * signed for a different application than [applicationId].
     */
    fun signatureBlob(
        keyType: HardwareKeyType,
        applicationId: String,
        authenticatorData: ByteArray,
        signature: ByteArray,
    ): ByteArray {
        if (authenticatorData.size < MIN_AUTHENTICATOR_DATA_LENGTH) {
            throw HardwareKeyException(
                HardwareKeyException.Reason.Failed,
                "The authenticator data has ${authenticatorData.size} bytes",
            )
        }
        val buffer = ByteBuffer.wrap(authenticatorData)
        val rpIdHash = ByteArray(SHA256_LENGTH).also { buffer.get(it) }
        if (!MessageDigest.isEqual(rpIdHash, sha256(applicationId.toByteArray(Charsets.UTF_8)))) {
            throw HardwareKeyException(
                HardwareKeyException.Reason.Failed,
                "The key signed for a different application than $applicationId",
            )
        }
        val flags = buffer.get()
        val counter = buffer.getInt()
        return buildOpenSshSkSignatureBlob(keyType, signature, flags, counter)
    }

    /**
     * Writes the OpenSSH `PROTOCOL.u2f` signature blob:
     * - `string`: key type (`sk-ssh-ed25519@openssh.com` or `sk-ecdsa-sha2-nistp256@openssh.com`)
     * - `string`: signature. For Ed25519, the raw 64-byte signature. For P-256, `mpint r` and `mpint s` (RFC 5656).
     * - `byte`: flags from the authenticator data (`SSH_SK_USER_PRESENCE_REQD = 0x01`)
     * - `uint32`: counter from the authenticator data
     *
     * [signature] is the signature of the authenticator: raw for Ed25519, ASN.1 DER for P-256.
     */
    fun buildOpenSshSkSignatureBlob(
        keyType: HardwareKeyType,
        signature: ByteArray,
        flags: Byte,
        counter: Int,
    ): ByteArray {
        val sshSignature = when (keyType) {
            HardwareKeyType.SkSshEd25519 -> {
                if (signature.size != ED25519_SIGNATURE_LENGTH) {
                    throw HardwareKeyException(
                        HardwareKeyException.Reason.Failed,
                        "An Ed25519 signature has $ED25519_SIGNATURE_LENGTH bytes (got ${signature.size})",
                    )
                }
                signature
            }
            HardwareKeyType.SkEcdsaSha2Nistp256 -> derToSshEcdsaSignature(signature)
            HardwareKeyType.PivSmartCard ->
                throw HardwareKeyException(HardwareKeyException.Reason.NotSupported, keyType.opensshKeyType)
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            val typeBytes = keyType.opensshKeyType.toByteArray(Charsets.US_ASCII)
            dos.writeInt(typeBytes.size)
            dos.write(typeBytes)
            dos.writeInt(sshSignature.size)
            dos.write(sshSignature)
            dos.writeByte(flags.toInt())
            dos.writeInt(counter)
        }
        return out.toByteArray()
    }

    /**
     * Converts an ASN.1 DER ECDSA signature (`SEQUENCE { INTEGER r, INTEGER s }`) to the SSH form
     * `mpint r || mpint s`. The SSH `mpint` is the shortest two's complement form: it starts with a zero byte only
     * when the high bit of the next byte is set. The function also accepts extra zero bytes at the start of the DER
     * integers. Throws a [HardwareKeyException] when [der] is not a DER ECDSA signature.
     */
    fun derToSshEcdsaSignature(der: ByteArray): ByteArray {
        val reader = DerReader(der)
        reader.expectTag(DER_SEQUENCE)
        val sequenceLength = reader.readLength()
        if (sequenceLength != reader.remaining) throw invalidDer("The sequence length is not correct")
        val r = reader.readPositiveInteger()
        val s = reader.readPositiveInteger()
        if (reader.remaining != 0) throw invalidDer("The signature has extra bytes")
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            for (value in listOf(r, s)) {
                val mpint = if (value.signum() == 0) ByteArray(0) else value.toByteArray()
                dos.writeInt(mpint.size)
                dos.write(mpint)
            }
        }
        return out.toByteArray()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun unexpectedKey(keyType: HardwareKeyType, kty: Int?, alg: Int?, crv: Int?): HardwareKeyException =
        HardwareKeyException(
            HardwareKeyException.Reason.NotSupported,
            "The key gave a COSE key (kty=$kty, alg=$alg, crv=$crv) that is not a ${keyType.opensshKeyType} key",
        )

    private fun invalidDer(detail: String): HardwareKeyException =
        HardwareKeyException(HardwareKeyException.Reason.Failed, "Incorrect ECDSA signature: $detail")

    /** Reads the DER items of an ECDSA signature. */
    private class DerReader(private val bytes: ByteArray) {
        private var position = 0

        val remaining: Int
            get() = bytes.size - position

        fun expectTag(tag: Int) {
            if (readByte() != tag) throw invalidDer("Expected the tag 0x${tag.toString(16)}")
        }

        /** Reads a DER length in the short form or in the long form with one or two bytes. */
        fun readLength(): Int {
            val first = readByte()
            if (first < 0x80) return first
            val count = first and 0x7f
            if (count !in 1..2) throw invalidDer("The length has $count bytes")
            var length = 0
            repeat(count) { length = (length shl 8) or readByte() }
            return length
        }

        /** Reads an INTEGER as an unsigned value. A negative DER integer is not a valid `r` or `s`. */
        fun readPositiveInteger(): BigInteger {
            expectTag(DER_INTEGER)
            val length = readLength()
            if (length == 0 || length > remaining) throw invalidDer("The integer length is $length")
            val content = bytes.copyOfRange(position, position + length)
            position += length
            if (content[0].toInt() and 0x80 != 0) throw invalidDer("The integer is negative")
            return BigInteger(1, content)
        }

        private fun readByte(): Int {
            if (position >= bytes.size) throw invalidDer("The signature is too short")
            return bytes[position++].toInt() and 0xff
        }
    }
}
