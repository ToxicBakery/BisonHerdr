package com.toxicbakery.app.bison.store

import com.toxicbakery.logging.Arbor
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JVM file-system backed [StoreIOProvider] using atomic temp-file writes and `Files.move`.
 */
class StoreIOProviderJvm(
    private val baseDir: File = defaultJvmAppDataDir(),
) : StoreIOProvider {

    private val preparedDir: File by lazy {
        if (!baseDir.isDirectory && !baseDir.mkdirs()) {
            log.w { "Unable to create directory ${baseDir.absolutePath}" }
        }
        baseDir
    }

    private fun resolve(fileName: String): File {
        val candidate = File(fileName)
        return if (candidate.isAbsolute) candidate else File(preparedDir, fileName)
    }

    override fun exists(fileName: String): Boolean = resolve(fileName).isFile

    override fun readText(fileName: String): String? {
        val file = resolve(fileName)
        if (!file.isFile) return null
        return file.readText(Charsets.UTF_8)
    }

    override fun writeAtomic(fileName: String, content: String) {
        val tempFile = resolve("$fileName.tmp")
        val targetFile = resolve(fileName)
        tempFile.parentFile?.mkdirs()
        FileOutputStream(tempFile).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            runCatching { stream.fd.sync() }
        }
        moveFile(tempFile, targetFile)
    }

    override fun moveCorruptAside(fileName: String) {
        val source = resolve(fileName)
        if (!source.exists()) return
        val backup = resolve("$fileName.corrupt.${System.currentTimeMillis()}")
        moveFile(source, backup)
    }

    override fun delete(fileName: String) {
        runCatching { Files.deleteIfExists(resolve(fileName).toPath()) }
            .onFailure { error -> log.w(error) { "Failed to delete $fileName" } }
    }

    override fun move(from: String, to: String) {
        moveFile(resolve(from), resolve(to))
    }

    override fun pathFor(storeName: String): String = resolve(storeName).absolutePath

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
        val log = Arbor.tag("StoreIOProviderJvm")
    }
}

internal fun defaultJvmAppDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home").orEmpty()
    return when {
        "mac" in os || "darwin" in os ->
            File(File(home, "Library/Application Support"), "BisonHerdr")
        "win" in os -> {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            File(appData?.let(::File) ?: File(home, "AppData/Roaming"), "BisonHerdr")
        }
        else -> {
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }
            File(xdg?.let(::File) ?: File(home, ".local/share"), "bison-herdr")
        }
    }
}
