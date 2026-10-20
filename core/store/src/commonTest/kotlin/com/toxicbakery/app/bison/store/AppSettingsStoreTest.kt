package com.toxicbakery.app.bison.store

import com.toxicbakery.app.bison.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsStoreTest {

    @Test
    fun defaultsToGruvboxThemeOutOfTheBox() = runTest {
        val io = InMemoryStoreIOProvider()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = AppSettingsStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        assertTrue(store.isLoaded.value)
        assertEquals("gruvbox", store.settings.value.themeId)
        assertEquals(13f, store.settings.value.defaultFontSizeSp)
        assertTrue(store.settings.value.predictiveEchoUnderlines)
    }

    @Test
    fun setThemeAndDefaultFontSizePersistAtomically() = runTest {
        val io = InMemoryStoreIOProvider()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = AppSettingsStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        store.setTheme("oled-black")
        store.setDefaultFontSize(16f)

        assertEquals("oled-black", store.settings.value.themeId)
        assertEquals(16f, store.settings.value.defaultFontSizeSp)

        val rawJson = assertNotNull(io.readText(AppSettingsStore.SETTINGS_FILE_NAME))
        val decoded = appJson.decodeFromString<AppSettings>(rawJson)
        assertEquals("oled-black", decoded.themeId)
        assertEquals(16f, decoded.defaultFontSizeSp)

        store.updateSettings {
            it.copy(
                availableReleaseVersion = "26.42.2",
                availableReleaseUrl = "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2",
            )
        }
        store.acknowledgeReleaseUpdate()
        store.setCheckForUpdates(false)

        val updatedDecoded = appJson.decodeFromString<AppSettings>(
            assertNotNull(io.readText(AppSettingsStore.SETTINGS_FILE_NAME)),
        )
        assertEquals("26.42.2", updatedDecoded.notifiedReleaseVersion)
        assertEquals(false, updatedDecoded.checkForUpdates)
    }

    @Test
    fun corruptSettingsJsonFallsBackToGruvboxAndMovesCorruptFileAside() = runTest {
        val corrupt = "{\"themeId\": "
        val io = InMemoryStoreIOProvider(mapOf(AppSettingsStore.SETTINGS_FILE_NAME to corrupt))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = AppSettingsStore(
            storeIOProvider = io,
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        assertTrue(store.isLoaded.value)
        assertEquals("gruvbox", store.settings.value.themeId)
        val corruptKey = io.files.keys.single { it.startsWith("${AppSettingsStore.SETTINGS_FILE_NAME}.corrupt.") }
        assertEquals(corrupt, io.readText(corruptKey))
    }

    @Test
    fun appJsonIgnoresUnknownKeysAndCoercesDefaults() {
        val forwardJson = """
            {
              "themeId": "catppuccin",
              "futureServerSetting": true
            }
        """.trimIndent()
        val decoded = appJson.decodeFromString<AppSettings>(forwardJson)
        assertEquals("catppuccin", decoded.themeId)
        assertEquals(13f, decoded.defaultFontSizeSp)
    }
}
