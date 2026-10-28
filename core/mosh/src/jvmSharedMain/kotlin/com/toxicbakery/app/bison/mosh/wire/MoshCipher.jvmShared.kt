package com.toxicbakery.app.bison.mosh.wire

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.OCBBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

actual fun MoshCipher(key: MoshKey): MoshCipher = BouncyCastleMoshCipher(key)

/**
 * The [MoshCipher] of BouncyCastle. Android and the JDK have no public OCB mode in their JCA providers.
 *
 * Each call makes a new [OCBBlockCipher], so that two threads can use one instance at the same time.
 */
private class BouncyCastleMoshCipher(key: MoshKey) : MoshCipher {
    private val keyParameter = KeyParameter(key.toByteArray())

    override fun seal(nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = newCipher(forEncryption = true, nonce)
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val count = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, count)
        return output
    }

    override fun open(nonce: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < MoshCipher.TAG_SIZE) return null
        val cipher = newCipher(forEncryption = false, nonce)
        val output = ByteArray(cipher.getOutputSize(sealed.size))
        return try {
            val count = cipher.processBytes(sealed, 0, sealed.size, output, 0)
            val total = count + cipher.doFinal(output, count)
            if (total == output.size) output else output.copyOf(total)
        } catch (_: InvalidCipherTextException) {
            null
        }
    }

    private fun newCipher(forEncryption: Boolean, nonce: ByteArray): OCBBlockCipher {
        require(nonce.size == MoshCipher.NONCE_SIZE) {
            "The nonce has ${nonce.size} bytes, not ${MoshCipher.NONCE_SIZE}."
        }
        // AESEngine.newInstance() replaces the AESEngine constructor, which BouncyCastle 1.86 deprecates.
        return OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance()).apply {
            init(forEncryption, AEADParameters(keyParameter, TAG_BITS, nonce))
        }
    }

    private companion object {
        const val TAG_BITS = MoshCipher.TAG_SIZE * Byte.SIZE_BITS
    }
}
