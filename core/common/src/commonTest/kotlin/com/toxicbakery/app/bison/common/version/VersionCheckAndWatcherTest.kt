package com.toxicbakery.app.bison.common.version

import com.toxicbakery.app.bison.model.AppSettings
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.store.InMemoryStoreIOProvider
import com.toxicbakery.app.bison.store.appJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VersionCheckAndWatcherTest {

    @Test
    fun anUpdateToTheAvailableReleaseClearsTheStoredRelease() {
        val before = AppSettings(
            availableReleaseVersion = "26.42.2",
            availableReleaseUrl = "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2",
        )
        val after = VersionCheck.afterStart(before, "26.42.2", currentCode = 2642002)
        assertNull(after.availableReleaseVersion)
        assertNull(after.availableReleaseUrl)

        val stillNewer = VersionCheck.afterStart(before, "26.41.1", currentCode = 2641001)
        assertEquals("26.42.2", stillNewer.availableReleaseVersion)
    }

    @Test
    fun parseLatestReleaseJsonAcceptsOnlyACleanPublishedRelease() {
        val release = parseGitHubLatestReleaseJson(
            """
            {
              "tag_name": "v26.42.2",
              "html_url": "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2",
              "draft": false,
              "prerelease": false
            }
            """.trimIndent(),
        )
        assertEquals(
            RemoteRelease("26.42.2", "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2"),
            release,
        )
        // A non-GitHub URL falls back to the release tag URL.
        val fallback = parseLatestReleaseJson(
            """{"tag_name": "v26.42.2", "html_url": "https://example.com/other"}""",
        )
        assertEquals(
            RemoteRelease("26.42.2", "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2"),
            fallback,
        )
        // Drafts, prereleases, bad tags, and broken JSON give null.
        assertNull(parseLatestReleaseJson("""{"tag_name": "v26.42.2", "draft": true}"""))
        assertNull(parseLatestReleaseJson("""{"tag_name": "v26.42.2", "prerelease": true}"""))
        assertNull(parseLatestReleaseJson("""{"tag_name": "v26.42.2-rc1"}"""))
        assertNull(parseLatestReleaseJson("not json"))
    }

    @Test
    fun versionCheckRulesRespectTheDailyIntervalAndSingleNotification() {
        val base = AppSettings()
        assertTrue(VersionCheck.shouldCheckRelease(base, nowEpochMs = 100_000L))
        assertFalse(VersionCheck.shouldCheckRelease(base.copy(checkForUpdates = false), nowEpochMs = 100_000L))

        val checked = VersionCheck.afterReleaseCheck(
            settings = base,
            remote = RemoteRelease("26.42.2", "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2"),
            nowEpochMs = 100_000L,
            currentCode = 2641001,
        )
        assertEquals(100_000L, checked.lastReleaseCheckEpochMs)
        assertEquals("26.42.2", checked.availableReleaseVersion)
        assertEquals("26.42.2", VersionCheck.unseenReleaseVersion(checked, currentCode = 2641001))
        assertTrue(VersionCheck.shouldShowTopBarNotification(checked, currentCode = 2641001))
        assertEquals(
            RemoteRelease("26.42.2", "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2"),
            VersionCheck.effectiveAvailableRelease(checked, currentCode = 2641001),
        )
        assertFalse(
            VersionCheck.shouldCheckRelease(
                checked,
                nowEpochMs = 100_000L + VersionCheck.CHECK_INTERVAL_MS - 1L,
            ),
        )
        assertTrue(
            VersionCheck.shouldCheckRelease(
                checked,
                nowEpochMs = 100_000L + VersionCheck.CHECK_INTERVAL_MS,
            ),
        )
        assertTrue(VersionCheck.shouldCheckRelease(checked, nowEpochMs = 50_000L))

        val acknowledged = VersionCheck.acknowledgeRelease(checked)
        assertEquals("26.42.2", acknowledged.notifiedReleaseVersion)
        assertNull(VersionCheck.unseenReleaseVersion(acknowledged, currentCode = 2641001))
        assertFalse(VersionCheck.shouldShowTopBarNotification(acknowledged, currentCode = 2641001))
        assertEquals("26.42.2", VersionCheck.availableReleaseVersion(acknowledged, currentCode = 2641001))
        assertEquals(
            RemoteRelease("26.42.2", "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2"),
            VersionCheck.effectiveAvailableRelease(acknowledged, currentCode = 2641001),
        )
        assertNull(VersionCheck.unseenReleaseVersion(checked.copy(checkForUpdates = false), currentCode = 2641001))
        assertFalse(
            VersionCheck.shouldShowTopBarNotification(
                checked.copy(checkForUpdates = false),
                currentCode = 2641001,
            ),
        )

        // Newer release after acknowledged one notifies again.
        val newerAfterAck = acknowledged.copy(
            availableReleaseVersion = "26.42.3",
            availableReleaseUrl = "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.3",
        )
        assertEquals("26.42.3", VersionCheck.unseenReleaseVersion(newerAfterAck, currentCode = 2641001))
        assertTrue(VersionCheck.shouldShowTopBarNotification(newerAfterAck, currentCode = 2641001))
    }

    @Test
    fun theWatcherChecksAtMostOnceEachDayEvenWithMultipleReleases() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = AppSettingsStore(
            storeIOProvider = InMemoryStoreIOProvider(),
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()

        var calls = 0
        var latestRelease = RemoteRelease(
            "26.42.1",
            "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.1",
        )
        val fetcher = ReleaseFetcher {
            calls += 1
            latestRelease
        }
        val baseEpochMs = 1_700_000_000_000L
        val job = VersionWatcher(
            appSettings = store,
            current = "26.41.1",
            currentCode = 2641001,
            releaseFetcher = fetcher,
            nowEpochMs = { baseEpochMs + testScheduler.currentTime },
        ).start(this)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("26.42.1", store.settings.value.availableReleaseVersion)

        // A second release comes out two hours later on the same day: no second check happens.
        latestRelease = RemoteRelease(
            "26.42.2",
            "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.2",
        )
        advanceTimeBy(2 * 3_600_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("26.42.1", store.settings.value.availableReleaseVersion)

        // Just before 24 hours: still only one check.
        advanceTimeBy(VersionCheck.CHECK_INTERVAL_MS - 2 * 3_600_000L - 1L)
        runCurrent()
        assertEquals(1, calls)

        // At 24 hours: the next daily check finds the newer release.
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("26.42.2", store.settings.value.availableReleaseVersion)

        job.cancel()
    }

    @Test
    fun theWatcherRetriesAfterOneHourOnNetworkFailureAndRespectsTheSwitch() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = AppSettingsStore(
            storeIOProvider = InMemoryStoreIOProvider(),
            json = appJson,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        store.awaitLoaded()
        store.updateSettings { settings -> settings.copy(checkForUpdates = false) }
        runCurrent()

        var calls = 0
        var response: RemoteRelease? = null
        val fetcher = ReleaseFetcher {
            calls += 1
            response
        }
        val baseEpochMs = 1_700_000_000_000L
        val job = VersionWatcher(
            appSettings = store,
            current = "26.41.1",
            currentCode = 2641001,
            releaseFetcher = fetcher,
            nowEpochMs = { baseEpochMs + testScheduler.currentTime },
        ).start(this)
        runCurrent()
        advanceTimeBy(VersionCheck.CHECK_INTERVAL_MS)
        runCurrent()
        assertEquals(0, calls)

        // Turning the switch on starts a check; a network failure retries after 1 hour.
        store.updateSettings { settings -> settings.copy(checkForUpdates = true) }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0L, store.settings.value.lastReleaseCheckEpochMs)

        response = RemoteRelease(
            "26.42.3",
            "https://github.com/ToxicBakery/BisonHerdr/releases/tag/v26.42.3",
        )
        advanceTimeBy(VersionCheck.RETRY_INTERVAL_MS - 1L)
        runCurrent()
        assertEquals(1, calls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, calls)
        assertEquals("26.42.3", store.settings.value.availableReleaseVersion)

        job.cancel()
    }
}
