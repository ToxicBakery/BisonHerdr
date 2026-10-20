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
    val rttMs: Int

    /**
     * Primary Mosh UDP SSP (`AES-128-OCB`) backbone paired with Herdr's Endpoint/API control sideband.
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
     * Fallback SSH TCP Endpoint Generation 1 stream (`herdr remote-client-bridge`).
     */
    @Serializable
    @SerialName("ssh_endpoint")
    data class SshEndpoint(
        override val rttMs: Int,
        val fallbackReason: String? = null,
    ) : ActiveBackbone
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
