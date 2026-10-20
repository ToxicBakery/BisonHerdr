package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.AppSettings
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
 * Atomic, coroutine-safe persistent store for global [AppSettings] (`settings.json`).
 *
 * Defaults to `Gruvbox Dark` (`AppSettings(themeId = "gruvbox")`) out of the box.
 */
class AppSettingsStore(
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

    private val _settings = MutableStateFlow(AppSettings(themeId = DEFAULT_THEME_ID))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        scope.launch {
            loadSettingsFromDisk()
        }
    }

    /**
     * Suspends until the initial disk read of `settings.json` has completed.
     */
    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    /**
     * Atomically transforms [settings] and persists the updated [AppSettings] to `settings.json`.
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        initialLoad.await()
        _settings.update(transform)
        persistSettings()
    }

    /**
     * Updates the global color theme ID (e.g., `"gruvbox"`, `"tokyo-night"`, `"oled-black"`).
     */
    suspend fun setTheme(themeId: String) {
        updateSettings { current ->
            current.copy(themeId = themeId.ifBlank { DEFAULT_THEME_ID })
        }
    }

    /**
     * Updates the global default terminal font size in `sp`.
     */
    suspend fun setDefaultFontSize(fontSizeSp: Float) {
        val clamped = fontSizeSp.coerceIn(MachineStore.MIN_FONT_SIZE_SP, MachineStore.MAX_FONT_SIZE_SP)
        updateSettings { current ->
            current.copy(defaultFontSizeSp = clamped)
        }
    }

    /**
     * Enables or disables automatic daily GitHub release checks.
     */
    suspend fun setCheckForUpdates(enabled: Boolean) {
        updateSettings { current ->
            current.copy(checkForUpdates = enabled)
        }
    }

    /**
     * Marks [AppSettings.availableReleaseVersion] as seen in [AppSettings.notifiedReleaseVersion].
     */
    suspend fun acknowledgeReleaseUpdate() {
        updateSettings { current ->
            val available = current.availableReleaseVersion ?: return@updateSettings current
            current.copy(notifiedReleaseVersion = available)
        }
    }

    private suspend fun loadSettingsFromDisk() {
        try {
            mutex.withLock {
                val rawText = storeIOProvider.readText(SETTINGS_FILE_NAME)
                if (rawText != null) {
                    val loaded = try {
                        json.decodeFromString<AppSettings>(rawText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.e(e) { "Corrupt $SETTINGS_FILE_NAME detected; moving aside." }
                        quarantineCorruptFile()
                        null
                    }
                    if (loaded != null) {
                        _settings.value = loaded
                    }
                }
            }
        } finally {
            _isLoaded.value = true
            initialLoad.complete(Unit)
        }
    }

    private suspend fun persistSettings() {
        withContext(scope.coroutineContext) {
            mutex.withLock {
                if (writesBlocked) {
                    log.e { "Skipping $SETTINGS_FILE_NAME write because corrupt file could not be moved aside." }
                    return@withLock
                }
                val snapshot = _settings.value
                val encoded = json.encodeToString(snapshot)
                storeIOProvider.writeAtomic(SETTINGS_FILE_NAME, encoded)
            }
        }
    }

    private fun quarantineCorruptFile() {
        try {
            storeIOProvider.moveCorruptAside(SETTINGS_FILE_NAME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writesBlocked = true
            log.e(e) { "Failed to move corrupt $SETTINGS_FILE_NAME aside; blocking further writes." }
        }
    }

    companion object {
        const val SETTINGS_FILE_NAME: String = "settings.json"
        const val DEFAULT_THEME_ID: String = "gruvbox"

        private val log = Arbor.tag("AppSettingsStore")
    }
}
