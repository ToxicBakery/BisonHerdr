package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyType
import com.toxicbakery.app.bison.model.SshAuthMethod
import java.io.DataInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlin.io.encoding.Base64
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Checks an OpenSSH `sk` signature blob the same way as an OpenSSH server, and more strictly than MINA SSHD.
 *
 * - The blob has `string type, string signature, byte flags, uint32 counter`, and no more bytes.
 * - The user presence flag is set.
 * - The signed message is `SHA256(application) || flags || counter || SHA256(data)`.
 * - A P-256 signature is `mpint r, mpint s` in the RFC 4251 form: an `mpint` with the high bit set in its first byte
 *   is negative (OpenSSH `sshbuf_get_bignum2` refuses it), and an `mpint` must not have an unnecessary zero byte at
 *   the start. MINA SSHD and a signed `BigInteger` accept both, so they cannot find such a defect.
 */
internal object OpenSshSkSignatureCheck {

    /** The values of a blob that passed the check. */
    data class Checked(val type: String, val flags: Byte, val counter: Int)

    /** Checks [blob] for [data] and [key]. Throws an [AssertionError] when the check fails. */
    fun check(key: SshAuthMethod.HardwareSecurityKey, data: ByteArray, blob: ByteArray): Checked {
        val input = DataInputStream(blob.inputStream())
        val type = String(readString(input))
        val signature = readString(input)
        val flags = input.readByte()
        val counter = input.readInt()
        assertThat(input.available() == 0) { "The blob has extra bytes" }
        assertThat(type == key.keyType.opensshKeyType) { "The blob type is $type" }
        assertThat(flags.toInt() and 0x01 != 0) { "The user presence flag is not set" }

        val message = SecurityKeyWireFormat.sha256(key.applicationId.toByteArray()) + byteArrayOf(flags) +
            ByteBuffer.allocate(Int.SIZE_BYTES).putInt(counter).array() + SecurityKeyWireFormat.sha256(data)
        val publicKey = Base64.decode(checkNotNull(key.publicKey))
        val verified = when (key.keyType) {
            HardwareKeyType.SkSshEd25519 -> Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
                verifySignature(signature)
            }
            HardwareKeyType.SkEcdsaSha2Nistp256 -> {
                val mpints = DataInputStream(signature.inputStream())
                val r = readMpint(mpints)
                val s = readMpint(mpints)
                assertThat(mpints.available() == 0) { "The ECDSA signature has extra bytes" }
                val der = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(p256PublicKey(publicKey))
                    update(message)
                    verify(der)
                }
            }
            HardwareKeyType.PivSmartCard -> throw AssertionError("A PIV key has no sk signature")
        }
        assertThat(verified) { "The ${key.keyType} signature does not verify" }
        return Checked(type, flags, counter)
    }

    /** Reads one RFC 4251 `mpint` and refuses a negative value and an unnecessary zero byte at the start. */
    fun readMpint(input: DataInputStream): BigInteger {
        val bytes = readString(input)
        if (bytes.isEmpty()) return BigInteger.ZERO
        assertThat(bytes[0].toInt() and 0x80 == 0) { "The mpint is negative (the high bit of the first byte is set)" }
        assertThat(bytes[0].toInt() != 0 || (bytes.size > 1 && bytes[1].toInt() and 0x80 != 0)) {
            "The mpint has an unnecessary zero byte at the start"
        }
        return BigInteger(bytes)
    }

    private fun readString(input: DataInputStream): ByteArray = ByteArray(input.readInt()).also(input::readFully)

    private fun p256PublicKey(point: ByteArray): PublicKey {
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        val spec = ECPublicKeySpec(
            ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))),
            parameters.getParameterSpec(ECParameterSpec::class.java),
        )
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private inline fun assertThat(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }
}
