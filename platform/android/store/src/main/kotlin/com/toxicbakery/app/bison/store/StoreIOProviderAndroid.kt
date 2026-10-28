package com.toxicbakery.app.bison.store

import android.content.Context
import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Android [StoreIOProvider] backed by a directory of the app, using atomic temporary-file writes,
 * file-descriptor `sync()`, and `Files.move(..., StandardCopyOption.REPLACE_EXISTING)`.
 *
 * [directory] gives the directory. The provider makes it at the first use.
 */
class StoreIOProviderAndroid(
    private val directory: () -> File,
) : StoreIOProvider {

    /**
     * Uses [Context.getFilesDir]. Auto Backup reads this directory, so the backup rules of the app manifest
     * select the files that a backup copies.
     */
    constructor(context: Context) : this(directory = { context.filesDir })

    private val storageDir: File by lazy {
        directory().apply { mkdirs() }
    }

    private fun resolveFile(fileName: String): File =
        File(storageDir, fileName)

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
        val backupFile = resolveFile("$fileName${StoreIOProvider.CORRUPT_COPY_INFIX}${System.currentTimeMillis()}")
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

    override fun deleteCorruptCopies(fileName: String) {
        val prefix = "$fileName${StoreIOProvider.CORRUPT_COPY_INFIX}"
        storageDir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { file ->
            try {
                Files.deleteIfExists(file.toPath())
            } catch (error: Exception) {
                log.w(error) { "Unable to delete ${file.name}" }
            }
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
