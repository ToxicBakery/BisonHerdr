package com.toxicbakery.app.bison.mosh.wire

/**
 * AES-128 in OCB mode (RFC 7253) with a 12-byte nonce, a 16-byte tag, and no associated data (wire fact 3).
 *
 * Each call is independent, so two threads can use one cipher at the same time.
 */
interface MoshCipher {

    /** Returns the ciphertext of [plaintext] with the 16-byte tag at the end. [nonce] has 12 bytes. */
    fun seal(nonce: ByteArray, plaintext: ByteArray): ByteArray

    /**
     * Returns the plaintext of [sealed], which is a ciphertext with the tag at the end. Returns null when the tag is
     * not correct, or when [sealed] is shorter than the tag. [nonce] has 12 bytes.
     */
    fun open(nonce: ByteArray, sealed: ByteArray): ByteArray?

    companion object {
        /** The size of the nonce in bytes. */
        const val NONCE_SIZE: Int = 12

        /** The size of the tag in bytes. */
        const val TAG_SIZE: Int = 16
    }
}

/** Makes the platform [MoshCipher] for [key]. */
expect fun MoshCipher(key: MoshKey): MoshCipher
