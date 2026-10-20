package com.toxicbakery.app.bison.common.version

import com.toxicbakery.app.bison.model.AppSettings
import com.toxicbakery.app.bison.store.AppSettingsStore
import com.toxicbakery.app.bison.version.AppVersion
import com.toxicbakery.app.bison.version.ReleaseVersion
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure timing and deduplication rules for the daily GitHub release check.
 */
object VersionCheck {

    /** The minimum time in milliseconds between two release checks (24 hours). */
    const val CHECK_INTERVAL_MS: Long = 86_400_000L

    /** The retry delay in milliseconds after a failed network check (1 hour). */
    const val RETRY_INTERVAL_MS: Long = 3_600_000L

    /**
     * Gives [settings] after a start of the app with the version [current] and [currentCode].
     *
     * Clears [AppSettings.availableReleaseVersion] and [AppSettings.availableReleaseUrl] when the
     * stored release is not newer than [currentCode].
     */
    fun afterStart(
        settings: AppSettings,
        current: String = AppVersion.NAME,
        currentCode: Int = ReleaseVersion.codeOf(current) ?: AppVersion.CODE,
    ): AppSettings {
        val available = settings.availableReleaseVersion
        return if (available != null && !ReleaseVersion.isNewer(available, currentCode)) {
            settings.copy(availableReleaseVersion = null, availableReleaseUrl = null)
        } else {
            settings
        }
    }

    /**
     * Gives `true` when [settings] permits a release check at [nowEpochMs].
     */
    fun shouldCheckRelease(settings: AppSettings, nowEpochMs: Long): Boolean {
        if (!settings.checkForUpdates) return false
        val lastCheck = settings.lastReleaseCheckEpochMs
        if (lastCheck <= 0L) return true
        if (nowEpochMs < lastCheck) return true
        return nowEpochMs - lastCheck >= CHECK_INTERVAL_MS
    }

    /**
     * Gives the delay in milliseconds until the next release check after [nowEpochMs].
     */
    fun nextCheckDelayMs(settings: AppSettings, nowEpochMs: Long): Long {
        if (shouldCheckRelease(settings, nowEpochMs)) return 0L
        val elapsed = nowEpochMs - settings.lastReleaseCheckEpochMs
        return (CHECK_INTERVAL_MS - elapsed).coerceAtLeast(1L)
    }

    /**
     * Gives [settings] after a release check at [nowEpochMs] with result [remote].
     */
    fun afterReleaseCheck(
        settings: AppSettings,
        remote: RemoteRelease?,
        nowEpochMs: Long,
        currentCode: Int = AppVersion.CODE,
    ): AppSettings {
        if (remote == null) {
            return settings.copy(lastReleaseCheckEpochMs = nowEpochMs)
        }
        return if (ReleaseVersion.isNewer(remote.version, currentCode)) {
            settings.copy(
                lastReleaseCheckEpochMs = nowEpochMs,
                availableReleaseVersion = remote.version,
                availableReleaseUrl = remote.htmlUrl,
            )
        } else {
            settings.copy(
                lastReleaseCheckEpochMs = nowEpochMs,
                availableReleaseVersion = null,
                availableReleaseUrl = null,
            )
        }
    }

    /**
     * Gives [AppSettings.availableReleaseVersion] when it is newer than [currentCode], or `null`.
     */
    fun availableReleaseVersion(
        settings: AppSettings,
        currentCode: Int = AppVersion.CODE,
    ): String? = settings.availableReleaseVersion?.takeIf { version ->
        ReleaseVersion.isNewer(version, currentCode)
    }

    /**
     * Gives the available release version when the user did not yet see its notification, or `null`.
     */
    fun unseenReleaseVersion(
        settings: AppSettings,
        currentCode: Int = AppVersion.CODE,
    ): String? {
        if (!settings.checkForUpdates) return null
        return availableReleaseVersion(settings, currentCode)?.takeIf { version ->
            version != settings.notifiedReleaseVersion
        }
    }

    /**
     * Returns `true` when the top-bar update chip should be displayed for [settings] and [currentCode].
     */
    fun shouldShowTopBarNotification(
        settings: AppSettings,
        currentCode: Int = AppVersion.CODE,
    ): Boolean = unseenReleaseVersion(settings, currentCode) != null

    /**
     * Returns the effective [RemoteRelease] when [AppSettings.availableReleaseVersion] is newer than
     * [currentCode], falling back to the GitHub tag URL if [AppSettings.availableReleaseUrl] is blank.
     */
    fun effectiveAvailableRelease(
        settings: AppSettings,
        currentCode: Int = AppVersion.CODE,
    ): RemoteRelease? {
        val version = availableReleaseVersion(settings, currentCode) ?: return null
        val url = settings.availableReleaseUrl?.takeIf { it.isNotBlank() } ?: releaseTagUrl(version)
        return RemoteRelease(version = version, htmlUrl = url)
    }

    /**
     * Marks [AppSettings.availableReleaseVersion] as seen in [AppSettings.notifiedReleaseVersion].
     */
    fun acknowledgeRelease(settings: AppSettings): AppSettings {
        val available = settings.availableReleaseVersion ?: return settings
        return settings.copy(notifiedReleaseVersion = available)
    }
}

/**
 * Applies [VersionCheck] at the start of the app and runs the daily GitHub release check.
 *
 * After the load of `settings.json`, it clears any outdated stored release and checks GitHub at
 * most once in 24 hours when [releaseFetcher] is not [ReleaseFetcher.None].
 */
class VersionWatcher(
    private val appSettings: AppSettingsStore,
    private val current: String = AppVersion.NAME,
    private val currentCode: Int = ReleaseVersion.codeOf(current) ?: AppVersion.CODE,
    private val releaseFetcher: ReleaseFetcher = ReleaseFetcher.None,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /** Starts the check loop in [scope]. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        appSettings.awaitLoaded()
        val initial = appSettings.settings.value
        if (VersionCheck.afterStart(initial, current, currentCode) != initial) {
            appSettings.updateSettings { settings ->
                VersionCheck.afterStart(settings, current, currentCode)
            }
        }
        if (releaseFetcher === ReleaseFetcher.None) return@launch
        while (currentCoroutineContext().isActive) {
            appSettings.settings.first { settings -> settings.checkForUpdates }
            val delayMs = VersionCheck.nextCheckDelayMs(appSettings.settings.value, nowEpochMs())
            if (delayMs > 0L) {
                delay(delayMs)
                continue
            }
            val remote = releaseFetcher.fetchLatest()
            if (remote != null) {
                val now = nowEpochMs()
                appSettings.updateSettings { settings ->
                    VersionCheck.afterReleaseCheck(settings, remote, now, currentCode)
                }
                delay(VersionCheck.CHECK_INTERVAL_MS)
            } else {
                delay(VersionCheck.RETRY_INTERVAL_MS)
            }
        }
    }
}
