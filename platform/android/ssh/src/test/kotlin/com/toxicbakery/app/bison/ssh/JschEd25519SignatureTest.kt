package com.toxicbakery.app.bison.ssh

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests [AndroidJschSignatureEd25519] with the RFC 8032 section 7.1 test vectors. */
class JschEd25519SignatureTest {

    private fun sshSignatureBlob(rawSignature: ByteArray): ByteArray {
        val name = "ssh-ed25519".encodeToByteArray()
        return ByteBuffer.allocate(4 + name.size + 4 + rawSignature.size)
            .putInt(name.size).put(name)
            .putInt(rawSignature.size).put(rawSignature)
            .array()
    }

    private fun verifier(publicKey: ByteArray, message: ByteArray): AndroidJschSignatureEd25519 =
        AndroidJschSignatureEd25519().apply {
            init()
            setPubKey(publicKey)
            update(message)
        }

    @Test
    fun verifiesTest1WithAnEmptyMessage() {
        assertTrue(verifier(TEST1_PUBLIC, ByteArray(0)).verify(TEST1_SIGNATURE))
    }

    @Test
    fun verifiesTest2RawAndSshWrappedSignatures() {
        assertTrue(verifier(TEST2_PUBLIC, TEST2_MESSAGE).verify(TEST2_SIGNATURE))
        assertTrue(verifier(TEST2_PUBLIC, TEST2_MESSAGE).verify(sshSignatureBlob(TEST2_SIGNATURE)))
    }

    @Test
    fun rejectsAChangedMessageOrSignature() {
        assertFalse(verifier(TEST2_PUBLIC, byteArrayOf(0x73)).verify(TEST2_SIGNATURE))
        val badSignature = TEST2_SIGNATURE.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(verifier(TEST2_PUBLIC, TEST2_MESSAGE).verify(badSignature))
        assertFalse(verifier(TEST1_PUBLIC, TEST2_MESSAGE).verify(TEST2_SIGNATURE))
    }

    @Test
    fun signsTest2WithThePrivateSeed() {
        val signer = AndroidJschSignatureEd25519().apply {
            init()
            setPrvKey(TEST2_SECRET)
            update(TEST2_MESSAGE)
        }
        assertContentEquals(TEST2_SIGNATURE, signer.sign())
    }

    private companion object {
        val TEST1_PUBLIC = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val TEST1_SIGNATURE = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        val TEST2_SECRET = hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val TEST2_PUBLIC = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val TEST2_MESSAGE = byteArrayOf(0x72)
        val TEST2_SIGNATURE = hex(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        )

        fun hex(value: String): ByteArray =
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
