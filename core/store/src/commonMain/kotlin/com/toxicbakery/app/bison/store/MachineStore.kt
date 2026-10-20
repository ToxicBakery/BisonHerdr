package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.HostMachine
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
 * Atomic, coroutine-safe persistent store for [HostMachine] profiles (`machines.json`).
 *
 * Modeled on `SnapshotPB`'s `DeviceStore`:
 * - Exposes [knownMachines] and [isLoaded] as reactive [StateFlow]s.
 * - Guards disk persistence behind [initialLoad] ([awaitLoaded]) and a coroutine [Mutex].
 * - Writes atomically via [StoreIOProvider.writeAtomic] and quarantines corrupt JSON files via
 *   [StoreIOProvider.moveCorruptAside] so user data is never silently clobbered.
 */
class MachineStore(
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

    private val _knownMachines = MutableStateFlow<List<HostMachine>>(emptyList())
    val knownMachines: StateFlow<List<HostMachine>> = _knownMachines.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        scope.launch {
            loadMachinesFromDisk()
        }
    }

    /**
     * Suspends until the initial disk read of `machines.json` has completed and merged into [knownMachines].
     */
    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    /**
     * Returns the [HostMachine] matching [machineId] from the current in-memory snapshot, or `null`.
     */
    fun getMachine(machineId: String): HostMachine? =
        _knownMachines.value.firstOrNull { it.id == machineId }

    /**
     * Inserts a new [HostMachine] or updates an existing machine with the same [HostMachine.id],
     * then persists `machines.json` atomically.
     */
    suspend fun upsertMachine(machine: HostMachine) {
        initialLoad.await()
        _knownMachines.update { current ->
            val index = current.indexOfFirst { it.id == machine.id }
            if (index >= 0) {
                current.toMutableList().apply { this[index] = machine }
            } else {
                current + machine
            }
        }
        persistMachines()
    }

    /**
     * Removes the [HostMachine] with [machineId] and persists `machines.json` atomically.
     */
    suspend fun removeMachine(machineId: String) {
        initialLoad.await()
        _knownMachines.update { current ->
            current.filterNot { it.id == machineId }
        }
        persistMachines()
    }

    /**
     * Updates the per-host terminal font size ([HostMachine.fontSizeSp]) for [machineId].
     */
    suspend fun updateFontSize(machineId: String, fontSizeSp: Float) {
        initialLoad.await()
        val clamped = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        var changed = false
        _knownMachines.update { current ->
            current.map { machine ->
                if (machine.id == machineId) {
                    changed = true
                    machine.copy(fontSizeSp = clamped)
                } else {
                    machine
                }
            }
        }
        if (changed) {
            persistMachines()
        }
    }

    /**
     * Updates or clears the per-host color theme override ([HostMachine.themeOverrideId]) for [machineId].
     */
    suspend fun updateThemeOverride(machineId: String, themeId: String?) {
        initialLoad.await()
        var changed = false
        _knownMachines.update { current ->
            current.map { machine ->
                if (machine.id == machineId) {
                    changed = true
                    machine.copy(themeOverrideId = themeId)
                } else {
                    machine
                }
            }
        }
        if (changed) {
            persistMachines()
        }
    }

    /**
     * Batch-updates the machine list via a side-effect-free [transform] and schedules an atomic write.
     */
    fun update(transform: (List<HostMachine>) -> List<HostMachine>) {
        _knownMachines.update(transform)
        scope.launch {
            initialLoad.await()
            persistMachines()
        }
    }

    private suspend fun loadMachinesFromDisk() {
        try {
            mutex.withLock {
                val rawText = storeIOProvider.readText(MACHINES_FILE_NAME)
                if (rawText != null) {
                    val loaded = try {
                        json.decodeFromString<List<HostMachine>>(rawText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.e(e) { "Corrupt $MACHINES_FILE_NAME detected; moving aside." }
                        quarantineCorruptFile()
                        null
                    }
                    if (loaded != null) {
                        _knownMachines.update { current ->
                            val currentIds = current.mapTo(mutableSetOf()) { it.id }
                            current + loaded.filter { it.id !in currentIds }
                        }
                    }
                }
            }
        } finally {
            _isLoaded.value = true
            initialLoad.complete(Unit)
        }
    }

    private suspend fun persistMachines() {
        withContext(scope.coroutineContext) {
            mutex.withLock {
                if (writesBlocked) {
                    log.e { "Skipping $MACHINES_FILE_NAME write because corrupt file could not be moved aside." }
                    return@withLock
                }
                val snapshot = _knownMachines.value
                val encoded = json.encodeToString(snapshot)
                storeIOProvider.writeAtomic(MACHINES_FILE_NAME, encoded)
            }
        }
    }

    private fun quarantineCorruptFile() {
        try {
            storeIOProvider.moveCorruptAside(MACHINES_FILE_NAME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writesBlocked = true
            log.e(e) { "Failed to move corrupt $MACHINES_FILE_NAME aside; blocking further writes." }
        }
    }

    companion object {
        const val MACHINES_FILE_NAME: String = "machines.json"
        const val MIN_FONT_SIZE_SP: Float = 8f
        const val MAX_FONT_SIZE_SP: Float = 32f

        private val log = Arbor.tag("MachineStore")
    }
}
