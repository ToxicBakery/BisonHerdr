package com.toxicbakery.app.bison.ssh

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Keeps the last [limit] bytes of the standard error of a persistent worker.
 *
 * A worker writes to standard error for each command that fails, for example for each `herdr remote-api-bridge` call
 * when the session has no server. Thus a buffer with no limit grows while the host fails. This stream keeps at most two
 * times [limit] bytes. The SSH library writes from its own thread, so each function is synchronized.
 */
internal class StderrTail(private val limit: Int) : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    override fun write(b: Int) {
        buffer.write(b)
        trim()
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
        trim()
    }

    /** Returns the last [limit] bytes as UTF-8 text. */
    @Synchronized
    fun text(): String {
        val bytes = buffer.toByteArray()
        val start = (bytes.size - limit).coerceAtLeast(0)
        return bytes.copyOfRange(start, bytes.size).toString(Charsets.UTF_8)
    }

    /** Removes the old bytes when the buffer has more than two times [limit] bytes. */
    private fun trim() {
        if (buffer.size() <= 2 * limit) return
        val bytes = buffer.toByteArray()
        buffer.reset()
        buffer.write(bytes, bytes.size - limit, limit)
    }
}
