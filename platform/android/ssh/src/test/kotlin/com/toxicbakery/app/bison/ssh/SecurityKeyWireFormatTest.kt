package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.model.HardwareKeyType
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence

/**
 * Tests the OpenSSH `PROTOCOL.u2f` wire format of [SecurityKeyWireFormat]: the DER to `mpint` conversion of P-256
 * signatures, the signature blob, the authenticator data, and the COSE public keys.
 */
class SecurityKeyWireFormatTest {

    @Test
    fun aDerIntegerWithTheHighBitSetKeepsOneLeadingZeroByte() {
        val r = byteArrayOf(0x00) + ByteArray(32) { if (it == 0) 0x80.toByte() else it.toByte() }
        val s = ByteArray(32) { (it + 1).toByte() }

        val mpints = SecurityKeyWireFormat.derToSshEcdsaSignature(der(r, s))

        assertContentEquals(mpint(r) + mpint(s), mpints)
        assertEquals(listOf(r.toList(), s.toList()), readMpints(mpints).map { it.toList() })
    }

    @Test
    fun extraLeadingZeroBytesAreRemoved() {
        val value = ByteArray(32) { (0x7f - it).toByte() }
        // A non-minimal DER integer: two extra zero bytes before a value with the high bit clear.
        val r = byteArrayOf(0x00, 0x00) + value
        // Extra zero bytes before a value with the high bit set: one zero byte stays.
        val s = byteArrayOf(0x00, 0x00, 0x00) + byteArrayOf(0xff.toByte()) + ByteArray(31) { 0x11 }

        val (sshR, sshS) = readMpints(SecurityKeyWireFormat.derToSshEcdsaSignature(der(r, s)))

        assertContentEquals(value, sshR)
        assertContentEquals(byteArrayOf(0x00, 0xff.toByte()) + ByteArray(31) { 0x11 }, sshS)
    }

    @Test
    fun shortIntegersAndALongFormSequenceLengthConvert() {
        // A signature can have a short r or s, when the first bits of the value are zero.
        val r = byteArrayOf(0x01)
        val s = ByteArray(30) { 0x22 }

        val derWithLongLength = byteArrayOf(0x30, 0x81.toByte(), (2 + r.size + 2 + s.size).toByte()) +
            byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s

        val (sshR, sshS) = readMpints(SecurityKeyWireFormat.derToSshEcdsaSignature(derWithLongLength))
        assertContentEquals(r, sshR)
        assertContentEquals(s, sshS)
    }

    @Test
    fun aZeroIntegerIsAnEmptyMpint() {
        val mpints = SecurityKeyWireFormat.derToSshEcdsaSignature(der(byteArrayOf(0), byteArrayOf(5)))
        val (sshR, sshS) = readMpints(mpints)

        assertEquals(0, sshR.size)
        assertContentEquals(byteArrayOf(5), sshS)
    }

    @Test
    fun realP256SignaturesConvertToTheSameIntegers() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        // About one half of the signatures have r or s with the high bit set.
        repeat(SIGNATURE_COUNT) { index ->
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update("message $index".toByteArray())
                sign()
            }
            val sequence = ASN1Sequence.getInstance(der)
            val expected = (0..1).map { ASN1Integer.getInstance(sequence.getObjectAt(it)).positiveValue }

            val actual = readMpints(SecurityKeyWireFormat.derToSshEcdsaSignature(der)).map { BigInteger(it) }

            assertEquals(expected, actual, "signature $index")
        }
    }

    @Test
    fun incorrectDerSignaturesAreRefused() {
        val r = ByteArray(32) { 0x11 }
        val s = ByteArray(32) { 0x22 }
        val valid = der(r, s)
        val cases = mapOf(
            "empty" to ByteArray(0),
            "not a sequence" to byteArrayOf(0x31) + valid.copyOfRange(1, valid.size),
            "incorrect sequence length" to longerSequence(valid),
            "extra bytes" to longerSequence(valid) + byteArrayOf(0x00),
            "truncated" to valid.copyOfRange(0, valid.size - 3),
            "negative integer" to der(byteArrayOf(0x80.toByte()) + ByteArray(31), s),
            "empty integer" to der(ByteArray(0), s),
        )
        for ((name, bytes) in cases) {
            val error = assertFailsWith<HardwareKeyException>(name) {
                SecurityKeyWireFormat.derToSshEcdsaSignature(bytes)
            }
            assertEquals(HardwareKeyException.Reason.Failed, error.reason, name)
        }
    }

    @Test
    fun ed25519BlobHasTheRawSignatureTheFlagsAndTheCounter() {
        val signature = ByteArray(SecurityKeyWireFormat.ED25519_SIGNATURE_LENGTH) { it.toByte() }

        val blob = SecurityKeyWireFormat.buildOpenSshSkSignatureBlob(
            keyType = HardwareKeyType.SkSshEd25519,
            signature = signature,
            flags = 0x05,
            counter = 0x01020304,
        )

        val expected = sshString("sk-ssh-ed25519@openssh.com".toByteArray()) + sshString(signature) +
            byteArrayOf(0x05, 0x01, 0x02, 0x03, 0x04)
        assertContentEquals(expected, blob)
    }

    @Test
    fun p256BlobHasTheMpintSignatureTheFlagsAndTheCounter() {
        val r = byteArrayOf(0x00, 0x90.toByte()) + ByteArray(31) { 0x01 }
        val s = ByteArray(32) { 0x02 }

        val blob = SecurityKeyWireFormat.buildOpenSshSkSignatureBlob(
            keyType = HardwareKeyType.SkEcdsaSha2Nistp256,
            signature = der(r, s),
            flags = 0x01,
            counter = -1,
        )

        val expected = sshString("sk-ecdsa-sha2-nistp256@openssh.com".toByteArray()) +
            sshString(mpint(r) + mpint(s)) + byteArrayOf(0x01) + byteArrayOf(-1, -1, -1, -1)
        assertContentEquals(expected, blob)
    }

    @Test
    fun aBlobNeedsAFido2TypeAndAnEd25519SignatureOf64Bytes() {
        val shortSignature = assertFailsWith<HardwareKeyException> {
            SecurityKeyWireFormat.buildOpenSshSkSignatureBlob(HardwareKeyType.SkSshEd25519, ByteArray(63), 1, 1)
        }
        assertEquals(HardwareKeyException.Reason.Failed, shortSignature.reason)

        val piv = assertFailsWith<HardwareKeyException> {
            SecurityKeyWireFormat.buildOpenSshSkSignatureBlob(HardwareKeyType.PivSmartCard, ByteArray(64), 1, 1)
        }
        assertEquals(HardwareKeyException.Reason.NotSupported, piv.reason)
    }

    @Test
    fun theAuthenticatorDataGivesTheFlagsAndTheCounter() {
        val signature = ByteArray(SecurityKeyWireFormat.ED25519_SIGNATURE_LENGTH) { 0x33 }
        val authenticatorData = authenticatorData("ssh:", flags = 0x05, counter = 0x00a0b0c0)

        val blob = SecurityKeyWireFormat.signatureBlob(HardwareKeyType.SkSshEd25519, "ssh:", authenticatorData, signature)

        val expected = SecurityKeyWireFormat.buildOpenSshSkSignatureBlob(
            keyType = HardwareKeyType.SkSshEd25519,
            signature = signature,
            flags = 0x05,
            counter = 0x00a0b0c0,
        )
        assertContentEquals(expected, blob)
    }

    @Test
    fun theAuthenticatorDataMustBeForTheApplicationAndLongEnough() {
        val signature = ByteArray(SecurityKeyWireFormat.ED25519_SIGNATURE_LENGTH)
        val otherApplication = assertFailsWith<HardwareKeyException> {
            SecurityKeyWireFormat.signatureBlob(
                HardwareKeyType.SkSshEd25519,
                "ssh:",
                authenticatorData("ssh:other", flags = 1, counter = 1),
                signature,
            )
        }
        assertEquals(HardwareKeyException.Reason.Failed, otherApplication.reason)

        val short = assertFailsWith<HardwareKeyException> {
            SecurityKeyWireFormat.signatureBlob(HardwareKeyType.SkSshEd25519, "ssh:", ByteArray(36), signature)
        }
        assertEquals(HardwareKeyException.Reason.Failed, short.reason)
    }

    @Test
    fun extensionDataAndAMissingTouchAreRefused() {
        val signature = ByteArray(SecurityKeyWireFormat.ED25519_SIGNATURE_LENGTH)
        val cases = mapOf(
            // The ED flag and one byte of CBOR extension data: an empty map (0xa0).
            "extension data" to authenticatorData("ssh:", flags = ED_AND_UP, counter = 1) + byteArrayOf(0xa0.toByte()),
            "the ED flag with no data" to authenticatorData("ssh:", flags = ED_AND_UP, counter = 1),
            "no user presence" to authenticatorData("ssh:", flags = 0x04, counter = 1),
        )
        for ((name, data) in cases) {
            val error = assertFailsWith<HardwareKeyException>(name) {
                SecurityKeyWireFormat.signatureBlob(HardwareKeyType.SkSshEd25519, "ssh:", data, signature)
            }
            assertEquals(HardwareKeyException.Reason.Failed, error.reason, name)
        }
    }

    @Test
    fun coseKeysGiveTheRawPublicKeys() {
        val x = ByteArray(32) { 0x0a }
        val y = ByteArray(32) { 0x0b }

        assertContentEquals(
            x,
            SecurityKeyWireFormat.publicKeyFromCose(
                HardwareKeyType.SkSshEd25519,
                mapOf(1 to 1, 3 to -8, -1 to 6, -2 to x),
            ),
        )
        assertContentEquals(
            byteArrayOf(0x04) + x + y,
            SecurityKeyWireFormat.publicKeyFromCose(
                HardwareKeyType.SkEcdsaSha2Nistp256,
                mapOf(1 to 2, 3 to -7, -1 to 1, -2 to x, -3 to y),
            ),
        )
        assertEquals(-8, SecurityKeyWireFormat.coseAlgorithmOf(HardwareKeyType.SkSshEd25519))
        assertEquals(-7, SecurityKeyWireFormat.coseAlgorithmOf(HardwareKeyType.SkEcdsaSha2Nistp256))
    }

    @Test
    fun coseKeysThatDoNotAgreeWithTheKeyTypeAreRefused() {
        val x = ByteArray(32)
        val cases = listOf(
            HardwareKeyType.SkSshEd25519 to mapOf(1 to 2, 3 to -8, -1 to 6, -2 to x),
            HardwareKeyType.SkSshEd25519 to mapOf(1 to 1, 3 to -7, -1 to 6, -2 to x),
            HardwareKeyType.SkSshEd25519 to mapOf(1 to 1, 3 to -8, -1 to 6, -2 to ByteArray(31)),
            HardwareKeyType.SkEcdsaSha2Nistp256 to mapOf(1 to 2, 3 to -7, -1 to 2, -2 to x, -3 to x),
            HardwareKeyType.SkEcdsaSha2Nistp256 to mapOf(1 to 2, 3 to -7, -1 to 1, -2 to x),
        )
        for ((keyType, cose) in cases) {
            val error = assertFailsWith<HardwareKeyException>("$keyType $cose") {
                SecurityKeyWireFormat.publicKeyFromCose(keyType, cose)
            }
            assertEquals(HardwareKeyException.Reason.NotSupported, error.reason)
        }
        val piv = assertFailsWith<HardwareKeyException> {
            SecurityKeyWireFormat.coseAlgorithmOf(HardwareKeyType.PivSmartCard)
        }
        assertEquals(HardwareKeyException.Reason.NotSupported, piv.reason)
    }

    /** Returns [der] with a sequence length that is one byte too long. */
    private fun longerSequence(der: ByteArray): ByteArray =
        byteArrayOf(0x30, (der[1] + 1).toByte()) + der.copyOfRange(2, der.size)

    private fun der(r: ByteArray, s: ByteArray): ByteArray {
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun authenticatorData(application: String, flags: Byte, counter: Int): ByteArray =
        ByteBuffer.allocate(SecurityKeyWireFormat.AUTHENTICATOR_DATA_LENGTH)
            .put(SecurityKeyWireFormat.sha256(application.toByteArray()))
            .put(flags)
            .putInt(counter)
            .array()

    private fun mpint(value: ByteArray): ByteArray = sshString(value)

    private fun sshString(value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(value.size)
            dos.write(value)
        }
        return out.toByteArray()
    }

    /** Reads the two `mpint` values of an SSH ECDSA signature. */
    private fun readMpints(bytes: ByteArray): List<ByteArray> {
        val input = DataInputStream(bytes.inputStream())
        val values = List(2) { ByteArray(input.readInt()).also(input::readFully) }
        assertEquals(0, input.available(), "The signature has extra bytes")
        return values
    }

    private companion object {
        const val SIGNATURE_COUNT = 32
        const val ED_AND_UP: Byte = 0x81.toByte()
    }
}
