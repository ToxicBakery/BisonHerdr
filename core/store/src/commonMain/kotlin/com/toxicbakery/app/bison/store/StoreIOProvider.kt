package com.toxicbakery.app.bison.store

import kotlin.time.Clock
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Abstraction for reading and writing named JSON store files (`machines.json`, `settings.json`)
 * with atomic temporary-file write + move semantics and corrupt file quarantine.
 */
interface StoreIOProvider {
    /**
     * Reads the full UTF-8 content of [fileName], or returns `null` if [fileName] does not exist.
     */
    fun readText(fileName: String): String?

    /**
     * Atomically writes [content] to a temporary file (`$fileName.tmp`) and moves/renames it over
     * [fileName] so a process termination during write never leaves a half-written JSON file.
     */
    fun writeAtomic(fileName: String, content: String)

    /**
     * Moves an unreadable or corrupt [fileName] aside (e.g. `$fileName.corrupt.<timestamp>`) so
     * user data is preserved for inspection and never silently overwritten.
     */
    fun moveCorruptAside(fileName: String)

    /**
     * Removes each copy that [moveCorruptAside] made for [fileName] (`$fileName.corrupt.<timestamp>`).
     * Best-effort and never throws.
     */
    fun deleteCorruptCopies(fileName: String)

    /** Returns `true` if [fileName] exists in the store. */
    fun exists(fileName: String): Boolean = readText(fileName) != null

    /** Removes [fileName] if present. Best-effort and never throws. */
    fun delete(fileName: String) {}

    /** Atomically moves [from] to [to], replacing [to] if it already exists. */
    fun move(from: String, to: String) {
        val existing = readText(from) ?: throw IllegalStateException("Missing source entry: $from")
        writeAtomic(to, existing)
        delete(from)
    }

    fun sinkFor(storeName: String): RawSink = object : RawSink {
        private val buffer = Buffer()

        override fun write(source: Buffer, byteCount: Long) {
            source.readTo(buffer, byteCount)
        }

        override fun flush() = Unit

        override fun close() {
            writeAtomic(storeName, buffer.readString())
        }
    }

    fun sourceFor(storeName: String): RawSource = Buffer().apply {
        writeString(readText(storeName).orEmpty())
    }

    fun pathFor(storeName: String): String = storeName

    companion object {
        /** The text between the file name and the timestamp in the name of a corrupt copy. */
        const val CORRUPT_COPY_INFIX: String = ".corrupt."
    }
}

/**
 * In-memory [StoreIOProvider] for unit tests, Compose Previews, and JVM fallback.
 */
class InMemoryStoreIOProvider(
    initialFiles: Map<String, String> = emptyMap(),
) : StoreIOProvider {
    private val entries: MutableMap<String, String> = initialFiles.toMutableMap()

    /** Direct view of stored file contents keyed by file name. */
    val files: Map<String, String>
        get() = entries.toMap()

    fun textAt(fileName: String): String? = entries[fileName]

    override fun exists(fileName: String): Boolean = entries.containsKey(fileName)

    override fun readText(fileName: String): String? = entries[fileName]

    override fun writeAtomic(fileName: String, content: String) {
        val tempName = "$fileName.tmp"
        entries[tempName] = content
        move(tempName, fileName)
    }

    override fun moveCorruptAside(fileName: String) {
        val current = entries.remove(fileName) ?: return
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val corruptName = "$fileName${StoreIOProvider.CORRUPT_COPY_INFIX}$timestamp"
        entries[corruptName] = current
    }

    override fun delete(fileName: String) {
        entries.remove(fileName)
    }

    override fun deleteCorruptCopies(fileName: String) {
        entries.keys.removeAll { name -> name.startsWith("$fileName${StoreIOProvider.CORRUPT_COPY_INFIX}") }
    }

    override fun move(from: String, to: String) {
        val value = entries.remove(from)
            ?: throw IllegalStateException("Cannot move non-existent entry $from to $to")
        entries[to] = value
    }
}

typealias FakeStoreIOProvider = InMemoryStoreIOProvider

/**
 * The Kodein tags of the [StoreIOProvider] bindings that are not the default binding.
 */
object StoreIOProviderTags {
    /**
     * A directory that backups do not copy, for example `Context.getNoBackupFilesDir` on Android.
     * The [MachineStore] keeps its device marker there.
     */
    const val NO_BACKUP: String = "storeIOProvider.noBackup"

    /** The directory of the encrypted secrets. Backups do not copy it. */
    const val SECRETS: String = "storeIOProvider.secrets"
}
