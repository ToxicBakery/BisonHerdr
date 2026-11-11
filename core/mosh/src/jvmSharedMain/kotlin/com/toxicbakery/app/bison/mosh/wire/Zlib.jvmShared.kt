package com.toxicbakery.app.bison.mosh.wire

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun Zlib(): Zlib = JavaZlib

/** The [Zlib] of `java.util.zip`. Each call has its own [Deflater] or [Inflater], so the object has no state. */
private object JavaZlib : Zlib {
    private const val BUFFER_SIZE = 16 * 1024

    override fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream(data.size / 2 + BUFFER_SIZE / 16)
            val buffer = ByteArray(BUFFER_SIZE)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    override fun decompress(data: ByteArray, maxSize: Int): ByteArray? {
        require(maxSize >= 0) { "The size limit $maxSize is negative." }
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream(minOf(maxSize, maxOf(BUFFER_SIZE, data.size * 2)))
            val buffer = ByteArray(BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return null
                }
                // No output and no end: the stream is cut short, or it needs a dictionary.
                if (count == 0 && !inflater.finished()) return null
                if (count > maxSize - output.size()) return null
                output.write(buffer, 0, count)
            }
            // Bytes after the end of the stream are not part of an instruction.
            return if (inflater.remaining == 0) output.toByteArray() else null
        } finally {
            inflater.end()
        }
    }
}
