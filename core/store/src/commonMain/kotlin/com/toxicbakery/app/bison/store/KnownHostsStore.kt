package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.KnownHost
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Atomic, coroutine-safe persistent store for the SSH host keys that the user accepted (`known_hosts.json`).
 *
 * - Keeps one [KnownHost] for each `host:port`. See [KnownHost.isFor].
 * - Exposes [knownHosts] and [isLoaded] as reactive [StateFlow]s.
 * - Guards disk persistence behind [initialLoad] ([awaitLoaded]) and a coroutine [Mutex].
 * - Writes atomically via [StoreIOProvider.writeAtomic] and quarantines a corrupt JSON file via
 *   [StoreIOProvider.moveCorruptAside]. After a quarantine the store is empty, so each host asks again.
 */
class KnownHostsStore(
    private val storeIOProvider: StoreIOProvider,
    private val json: Json = appJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + storeIoDispatcher),
) {
    constructor(
        storeIOProvider: StoreIOProvider,
        json: Json = appJson,
        dispatcher: CoroutineDispatcher,
    ) : this(
        storeIOProvider = storeIOProvider,
        json = json,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    private val mutex = Mutex()
    private var writesBlocked = false
    private val initialLoad = CompletableDeferred<Unit>()

    private val _knownHosts = MutableStateFlow<List<KnownHost>>(emptyList())

    /** The accepted host keys, in the order that the user accepted them. */
    val knownHosts: StateFlow<List<KnownHost>> = _knownHosts.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        scope.launch {
            loadKnownHostsFromDisk()
        }
    }

    /**
     * Suspends until the initial disk read of `known_hosts.json` has completed.
     *
     * Call this function before a host key check. Before the load, [find] returns null for each host.
     */
    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    /**
     * Returns the [KnownHost] for [host] and [port] from the current in-memory snapshot, or `null`.
     * This function does not block, so a JSch thread can call it.
     */
    fun find(host: String, port: Int): KnownHost? =
        _knownHosts.value.firstOrNull { it.isFor(host, port) }

    /**
     * Saves [entry] and persists `known_hosts.json` atomically. The entry replaces the entry
     * with the same `host:port`.
     */
    suspend fun put(entry: KnownHost) {
        initialLoad.await()
        _knownHosts.update { current ->
            current.filterNot { it.isFor(entry.host, entry.port) } + entry
        }
        persistKnownHosts()
    }

    /**
     * Removes the entry for [host] and [port] and persists `known_hosts.json` atomically.
     * Returns `true` when the store had an entry for [host] and [port].
     */
    suspend fun remove(host: String, port: Int): Boolean {
        initialLoad.await()
        var removed = false
        _knownHosts.update { current ->
            val remaining = current.filterNot { it.isFor(host, port) }
            removed = remaining.size != current.size
            remaining
        }
        if (removed) {
            persistKnownHosts()
        }
        return removed
    }

    private suspend fun loadKnownHostsFromDisk() {
        try {
            mutex.withLock {
                val rawText = storeIOProvider.readText(KNOWN_HOSTS_FILE_NAME)
                if (rawText != null) {
                    val loaded = try {
                        json.decodeFromString<List<KnownHost>>(rawText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.e(e) { "Corrupt $KNOWN_HOSTS_FILE_NAME detected; moving aside." }
                        quarantineCorruptFile()
                        null
                    }
                    if (loaded != null) {
                        _knownHosts.value = loaded
                    }
                }
            }
        } finally {
            _isLoaded.value = true
            initialLoad.complete(Unit)
        }
    }

    private suspend fun persistKnownHosts() {
        withContext(scope.coroutineContext) {
            mutex.withLock {
                if (writesBlocked) {
                    log.e { "Skipping $KNOWN_HOSTS_FILE_NAME write because corrupt file could not be moved aside." }
                    return@withLock
                }
                val snapshot = _knownHosts.value
                val encoded = json.encodeToString(snapshot)
                storeIOProvider.writeAtomic(KNOWN_HOSTS_FILE_NAME, encoded)
            }
        }
    }

    private fun quarantineCorruptFile() {
        try {
            storeIOProvider.moveCorruptAside(KNOWN_HOSTS_FILE_NAME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writesBlocked = true
            log.e(e) { "Failed to move corrupt $KNOWN_HOSTS_FILE_NAME aside; blocking further writes." }
        }
    }

    companion object {
        const val KNOWN_HOSTS_FILE_NAME: String = "known_hosts.json"

        private val log = Arbor.tag("KnownHostsStore")
    }
}
