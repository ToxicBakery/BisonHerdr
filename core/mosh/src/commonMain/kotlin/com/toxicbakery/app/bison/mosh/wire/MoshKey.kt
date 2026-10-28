package com.toxicbakery.app.bison.mosh.wire

import kotlin.io.encoding.Base64

/**
 * The 16-byte AES-128 key of one Mosh session.
 *
 * `mosh-server new` prints the key in its `MOSH CONNECT <port> <key>` line. The key text is 22 base64 characters
 * with no `=` at the end (wire fact 1).
 */
class MoshKey private constructor(private val bytes: ByteArray) {

    /** Returns a copy of the 16 key bytes. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    /** Does not show the key, so that a log line cannot hold it. */
    override fun toString(): String = "MoshKey(16 bytes)"

    companion object {
        /** The size of the key in bytes. */
        const val SIZE: Int = 16

        /** The length of the key text in the `MOSH CONNECT` line. */
        const val TEXT_LENGTH: Int = 22

        private val base64 = Base64.withPadding(Base64.PaddingOption.ABSENT)

        /** Returns the key of [text], or null when [text] is not 22 base64 characters that encode 16 bytes. */
        fun parse(text: String): MoshKey? {
            if (text.length != TEXT_LENGTH) return null
            val bytes = try {
                base64.decode(text)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return if (bytes.size == SIZE) MoshKey(bytes) else null
        }

        /** Returns the key that has the 16 [bytes]. */
        fun of(bytes: ByteArray): MoshKey {
            require(bytes.size == SIZE) { "A Mosh key has $SIZE bytes, not ${bytes.size}." }
            return MoshKey(bytes.copyOf())
        }
    }
}
