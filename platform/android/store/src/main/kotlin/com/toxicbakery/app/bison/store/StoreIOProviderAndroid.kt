package com.toxicbakery.app.bison.store

import android.content.Context
import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Android [StoreIOProvider] backed by [Context.getFilesDir] using atomic temporary-file writes,
 * file-descriptor `sync()`, and `Files.move(..., StandardCopyOption.REPLACE_EXISTING)`.
 */
class StoreIOProviderAndroid(
    private val context: Context,
) : StoreIOProvider {

    private fun resolveFile(fileName: String): File =
        File(context.filesDir, fileName)

    override fun exists(fileName: String): Boolean =
        resolveFile(fileName).isFile

    override fun readText(fileName: String): String? {
        val file = resolveFile(fileName)
        if (!file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }

    override fun writeAtomic(fileName: String, content: String) {
        val tempFile = resolveFile("$fileName.tmp")
        val targetFile = resolveFile(fileName)
        tempFile.parentFile?.mkdirs()
        FileOutputStream(tempFile).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            runCatching { stream.fd.sync() }
        }
        moveFile(tempFile, targetFile)
    }

    override fun moveCorruptAside(fileName: String) {
        val corruptFile = resolveFile(fileName)
        if (!corruptFile.exists()) return
        val backupFile = resolveFile("$fileName.corrupt.${System.currentTimeMillis()}")
        log.w { "Backing up corrupt store file $fileName to ${backupFile.name}" }
        moveFile(corruptFile, backupFile)
    }

    override fun delete(fileName: String) {
        try {
            Files.deleteIfExists(resolveFile(fileName).toPath())
        } catch (error: Exception) {
            log.w(error) { "Unable to delete $fileName" }
        }
    }

    override fun move(from: String, to: String) {
        moveFile(resolveFile(from), resolveFile(to))
    }

    override fun pathFor(storeName: String): String =
        resolveFile(storeName).absolutePath

    private fun moveFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        val log = Arbor.tag("StoreIOProviderAndroid")
    }
}
