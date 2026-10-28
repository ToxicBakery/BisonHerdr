package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.HostMachine
import com.toxicbakery.logging.Arbor
import kotlin.random.Random
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
 * - Exposes [knownMachines] and [isLoaded] as reactive [StateFlow]s.
 * - Guards disk persistence behind [initialLoad] ([awaitLoaded]) and a coroutine [Mutex].
 * - Writes atomically via [StoreIOProvider.writeAtomic] and quarantines corrupt JSON files via
 *   [StoreIOProvider.moveCorruptAside] so user data is never silently clobbered.
 * - Gives the loaded machines to [loadMigration] before it publishes them. When the migration changes a machine,
 *   the store writes `machines.json` again. The app uses it to move plain-text passwords into the secret store.
 * - Keeps a device marker ([DEVICE_MARKER_FILE_NAME]) in [noBackupStoreIOProvider]. A backup does not copy the
 *   marker. When the marker is absent at load, the store does the first-start steps: it removes the corrupt copies
 *   of `machines.json`, because a copy from an earlier version can hold a plain-text password. When
 *   `machines.json` also exists, it sets [restoredFromBackup].
 */
class MachineStore(
    private val storeIOProvider: StoreIOProvider,
    private val json: Json = appJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + storeIoDispatcher),
    private val noBackupStoreIOProvider: StoreIOProvider? = null,
    private val loadMigration: (suspend (List<HostMachine>) -> List<HostMachine>)? = null,
) {
    constructor(
        storeIOProvider: StoreIOProvider,
        json: Json = appJson,
        dispatcher: CoroutineDispatcher,
        noBackupStoreIOProvider: StoreIOProvider? = null,
        loadMigration: (suspend (List<HostMachine>) -> List<HostMachine>)? = null,
    ) : this(
        storeIOProvider = storeIOProvider,
        json = json,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        noBackupStoreIOProvider = noBackupStoreIOProvider,
        loadMigration = loadMigration,
    )

    private val mutex = Mutex()
    private var writesBlocked = false
    private val initialLoad = CompletableDeferred<Unit>()

    private val _knownMachines = MutableStateFlow<List<HostMachine>>(emptyList())
    val knownMachines: StateFlow<List<HostMachine>> = _knownMachines.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _restoredFromBackup = MutableStateFlow(false)

    /**
     * True when this load found `machines.json` but no device marker. This occurs after a restore from a backup
     * or a transfer from a different device, and also on the first start after an update from an earlier version.
     * A backup does not copy passwords or device keys, so some machines can need a password or a key again.
     */
    val restoredFromBackup: StateFlow<Boolean> = _restoredFromBackup.asStateFlow()

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
                        val migrated = migrate(loaded)
                        _knownMachines.update { current ->
                            val currentIds = current.mapTo(mutableSetOf()) { it.id }
                            current + migrated.filter { it.id !in currentIds }
                        }
                        if (migrated != loaded) {
                            writeMigratedMachinesLocked()
                        }
                    }
                }
                runFirstStartSteps(machinesFileExists = rawText != null)
            }
        } finally {
            _isLoaded.value = true
            initialLoad.complete(Unit)
        }
    }

    private suspend fun persistMachines() {
        withContext(scope.coroutineContext) {
            mutex.withLock {
                writeMachinesLocked()
            }
        }
    }

    /** Writes the current machines to `machines.json`. Call it while [mutex] is held. */
    private fun writeMachinesLocked() {
        if (writesBlocked) {
            log.e { "Skipping $MACHINES_FILE_NAME write because corrupt file could not be moved aside." }
            return
        }
        val snapshot = _knownMachines.value
        val encoded = json.encodeToString(snapshot)
        storeIOProvider.writeAtomic(MACHINES_FILE_NAME, encoded)
    }

    /** Writes the machines that [loadMigration] changed. A failed write does not stop the load. */
    private fun writeMigratedMachinesLocked() {
        try {
            writeMachinesLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Could not write the migrated $MACHINES_FILE_NAME. The next load tries again." }
        }
    }

    /** Returns [loaded] after [loadMigration]. When the migration fails, it returns [loaded] with no change. */
    private suspend fun migrate(loaded: List<HostMachine>): List<HostMachine> {
        val migration = loadMigration ?: return loaded
        return try {
            migration(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "The load migration of $MACHINES_FILE_NAME failed. The next load tries again." }
            loaded
        }
    }

    /**
     * Does the first-start steps when [noBackupStoreIOProvider] has no device marker, and then writes the marker.
     * Call it while [mutex] is held.
     */
    private fun runFirstStartSteps(machinesFileExists: Boolean) {
        val markerStore = noBackupStoreIOProvider ?: return
        if (markerStore.exists(DEVICE_MARKER_FILE_NAME)) return
        // An earlier version can have put a plain-text password into a corrupt copy.
        storeIOProvider.deleteCorruptCopies(MACHINES_FILE_NAME)
        _restoredFromBackup.value = machinesFileExists
        try {
            markerStore.writeAtomic(DEVICE_MARKER_FILE_NAME, newDeviceId())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Could not write $DEVICE_MARKER_FILE_NAME. The next load does the first-start steps again." }
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

        /** The device marker. It is in a directory that backups do not copy. See [restoredFromBackup]. */
        const val DEVICE_MARKER_FILE_NAME: String = "device.id"
        private const val DEVICE_ID_BYTES = 16
        const val MIN_FONT_SIZE_SP: Float = 8f
        const val MAX_FONT_SIZE_SP: Float = 32f

        private val log = Arbor.tag("MachineStore")

        /** Returns a new random identifier for the device marker. */
        private fun newDeviceId(): String =
            Random.nextBytes(DEVICE_ID_BYTES).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}
