package com.toxicbakery.app.bison.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Global user preferences persisted in `AppSettingsStore`.
 *
 * Defaults to `Gruvbox Dark` (`themeId = "gruvbox"`).
 */
@Serializable
data class AppSettings(
    val themeId: String = DEFAULT_THEME_ID,
    val defaultFontSizeSp: Float = 13f,
    val autoFadeHudWhileTyping: Boolean = true,
    val predictiveEchoUnderlines: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val splitThumbLayoutEnabled: Boolean = true,
    val leftThumbKeys: List<String> = DEFAULT_LEFT_THUMB_KEYS,
    /** True when the app checks GitHub for a new release at most once a day. */
    val checkForUpdates: Boolean = true,
    /** The wall-clock time in epoch milliseconds of the last release check, or 0 before the first check. */
    val lastReleaseCheckEpochMs: Long = 0L,
    /** The newest release version found on GitHub (for example "26.42.2"), or null. */
    val availableReleaseVersion: String? = null,
    /** The URL of the GitHub Release page for [availableReleaseVersion], or null. */
    val availableReleaseUrl: String? = null,
    /** The last release version whose notification the user already saw. */
    val notifiedReleaseVersion: String? = null,
) {
    companion object {
        const val DEFAULT_THEME_ID: String = "gruvbox"
        val DEFAULT_LEFT_THUMB_KEYS: List<String> = listOf(
            "Esc",
            "Tab",
            "Ctrl",
            "Alt",
            "^C",
            "^B",
            "/",
            "|",
        )
    }
}

/**
 * Active transport backbone negotiated for a connected [HostMachine].
 */
@Serializable
sealed interface ActiveBackbone {
    /**
     * The average poll time of the session in milliseconds: the time of one poll command batch on the SSH session.
     * It includes the run time of the Herdr commands on the host, so it is more than the network round trip time.
     * It is [UNMEASURED_RTT_MS] until the app measures the first poll.
     */
    val rttMs: Int

    /**
     * Mosh UDP SSP (`AES-128-OCB`) backbone paired with Herdr's Endpoint/API control sideband. This version never
     * selects it, because the Mosh transport is planned (issue #22).
     */
    @Serializable
    @SerialName("mosh_udp")
    data class MoshUdp(
        val udpPort: Int,
        override val rttMs: Int,
        val localEchoEnabled: Boolean = true,
        val roamedCount: Int = 0,
    ) : ActiveBackbone

    /**
     * SSH TCP Endpoint Generation 1 stream (`herdr remote-client-bridge`). This version uses it for each machine.
     * [fallbackReason] tells why the app does not use Mosh, or is null.
     */
    @Serializable
    @SerialName("ssh_endpoint")
    data class SshEndpoint(
        override val rttMs: Int,
        val fallbackReason: String? = null,
    ) : ActiveBackbone

    companion object {
        /** The [rttMs] of a backbone before the app measures the first poll. */
        const val UNMEASURED_RTT_MS: Int = 0
    }
}

/**
 * Step-by-step connection bootstrap progress surfaced in `ConnectingMachineScreen`.
 */
@Serializable
enum class ConnectionProgress {
    Idle,
    AuthenticatingSsh,
    BootstrappingMoshOrBridge,
    VerifyingUdpOrHello,
    SyncingHerdrSnapshot,
    Connected,
    Failed,
}

/**
 * Surface lease and network roaming status for an active machine session.
 */
@Serializable
enum class RoamingStatus {
    Healthy,
    SurfaceParked,
    RoamingReconnect,
}
