package com.toxicbakery.app.bison.common.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Complete color palette for a BisonHerdr theme, matching Herdr's 18 `Palette` variants in
 * `src/app/state.rs` plus `OLED Pitch Black` (`oled-black`), with `Gruvbox Dark` (`gruvbox`) as
 * the default out-of-the-box theme.
 */
@Immutable
data class BisonThemePalette(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    val isDefault: Boolean = false,
    val panelBgHex: String,
    val terminalBgHex: String,
    val accentHex: String,
    val textHex: String,
    val greenHex: String,
    val redHex: String,
    val mauveHex: String,
    val panelBg: Color = parseHexColor(panelBgHex),
    val terminalBg: Color = parseHexColor(terminalBgHex),
    val accent: Color = parseHexColor(accentHex),
    val text: Color = parseHexColor(textHex),
    val green: Color = parseHexColor(greenHex),
    val red: Color = parseHexColor(redHex),
    val mauve: Color = parseHexColor(mauveHex),
) {
    val bgDeep: Color get() = terminalBg
    val bgSurface: Color get() = panelBg
    val bgElevated: Color get() = panelBg.copy(alpha = 0.92f)
    val fgPrimary: Color get() = text
    val fgMuted: Color get() = text.copy(alpha = 0.68f)
    val accentPrimary: Color get() = accent
    val accentSecondary: Color get() = mauve
    val accentSuccess: Color get() = green
    val accentWarning: Color get() = accent
    val accentError: Color get() = red
    val accentInfo: Color get() = accent
    val borderSubtle: Color get() = text.copy(alpha = 0.22f)
}

/**
 * Parses a `#RRGGBB` or `RRGGBB` hex string into an opaque Compose [Color].
 */
internal fun parseHexColor(hex: String): Color {
    val clean = hex.trim().removePrefix("#")
    require(clean.length == 6) { "Expected 6-character RGB hex string, got: $hex" }
    val rgb = clean.toLong(16)
    return Color(0xFF000000L or rgb)
}

object BisonThemes {
    const val DEFAULT_THEME_ID: String = "gruvbox"

    val GruvboxDark = BisonThemePalette(
        id = "gruvbox",
        displayName = "Gruvbox Dark",
        isDark = true,
        isDefault = true,
        panelBgHex = "#282828",
        terminalBgHex = "#1d2021",
        accentHex = "#d79921",
        textHex = "#ebdbb2",
        greenHex = "#b8bb26",
        redHex = "#fb4934",
        mauveHex = "#d3869b",
    )

    val CatppuccinMocha = BisonThemePalette(
        id = "catppuccin",
        displayName = "Catppuccin Mocha",
        isDark = true,
        panelBgHex = "#181825",
        terminalBgHex = "#11111b",
        accentHex = "#89b4fa",
        textHex = "#cdd6f4",
        greenHex = "#a6e3a1",
        redHex = "#f38ba8",
        mauveHex = "#cba6f7",
    )

    val TokyoNight = BisonThemePalette(
        id = "tokyo-night",
        displayName = "Tokyo Night",
        isDark = true,
        panelBgHex = "#1a1b26",
        terminalBgHex = "#13141c",
        accentHex = "#7aa2f7",
        textHex = "#c0caf5",
        greenHex = "#9ece6a",
        redHex = "#f7768e",
        mauveHex = "#bb9af7",
    )

    val Vesper = BisonThemePalette(
        id = "vesper",
        displayName = "Vesper (Minimal)",
        isDark = true,
        panelBgHex = "#1a1a1a",
        terminalBgHex = "#101010",
        accentHex = "#ffc799",
        textHex = "#ffffff",
        greenHex = "#99ffe4",
        redHex = "#ff8080",
        mauveHex = "#ffd1a8",
    )

    val RosePine = BisonThemePalette(
        id = "rose-pine",
        displayName = "Rosé Pine",
        isDark = true,
        panelBgHex = "#191724",
        terminalBgHex = "#12101b",
        accentHex = "#c4a7e7",
        textHex = "#e0def4",
        greenHex = "#31748f",
        redHex = "#eb6f92",
        mauveHex = "#c4a7e7",
    )

    val KanagawaWave = BisonThemePalette(
        id = "kanagawa",
        displayName = "Kanagawa Wave",
        isDark = true,
        panelBgHex = "#1f1f28",
        terminalBgHex = "#16161d",
        accentHex = "#7e9cd8",
        textHex = "#dcd7ba",
        greenHex = "#76946a",
        redHex = "#c34043",
        mauveHex = "#957fb8",
    )

    val NordFrost = BisonThemePalette(
        id = "nord",
        displayName = "Nord Frost",
        isDark = true,
        panelBgHex = "#2e3440",
        terminalBgHex = "#242933",
        accentHex = "#88c0d0",
        textHex = "#eceff4",
        greenHex = "#a3be8c",
        redHex = "#bf616a",
        mauveHex = "#b48ead",
    )

    val Dracula = BisonThemePalette(
        id = "dracula",
        displayName = "Dracula",
        isDark = true,
        panelBgHex = "#282a36",
        terminalBgHex = "#1e1f29",
        accentHex = "#bd93f9",
        textHex = "#f8f8f2",
        greenHex = "#50fa7b",
        redHex = "#ff5555",
        mauveHex = "#ff79c6",
    )

    val OneDark = BisonThemePalette(
        id = "one-dark",
        displayName = "One Dark",
        isDark = true,
        panelBgHex = "#282c34",
        terminalBgHex = "#1e222a",
        accentHex = "#61afef",
        textHex = "#abb2bf",
        greenHex = "#98c379",
        redHex = "#e06c75",
        mauveHex = "#c678dd",
    )

    val SolarizedDark = BisonThemePalette(
        id = "solarized",
        displayName = "Solarized Dark",
        isDark = true,
        panelBgHex = "#002b36",
        terminalBgHex = "#001e26",
        accentHex = "#268bd2",
        textHex = "#93a1a1",
        greenHex = "#859900",
        redHex = "#dc322f",
        mauveHex = "#d33682",
    )

    val OledPitchBlack = BisonThemePalette(
        id = "oled-black",
        displayName = "OLED Pitch Black",
        isDark = true,
        panelBgHex = "#050508",
        terminalBgHex = "#000000",
        accentHex = "#60a5fa",
        textHex = "#f1f5f9",
        greenHex = "#34d399",
        redHex = "#fb7185",
        mauveHex = "#c084fc",
    )

    val ClassicTerminal = BisonThemePalette(
        id = "terminal",
        displayName = "Classic 16-Color",
        isDark = true,
        panelBgHex = "#121212",
        terminalBgHex = "#000000",
        accentHex = "#5c9cff",
        textHex = "#e5e5e5",
        greenHex = "#00cd00",
        redHex = "#ff5555",
        mauveHex = "#cd00cd",
    )

    val GruvboxLight = BisonThemePalette(
        id = "gruvbox-light",
        displayName = "Gruvbox Light",
        isDark = false,
        panelBgHex = "#fbf1c7",
        terminalBgHex = "#f9f5d7",
        accentHex = "#076678",
        textHex = "#3c3836",
        greenHex = "#79740e",
        redHex = "#9d0006",
        mauveHex = "#8f3f71",
    )

    val CatppuccinLatte = BisonThemePalette(
        id = "catppuccin-latte",
        displayName = "Catppuccin Latte",
        isDark = false,
        panelBgHex = "#eff1f5",
        terminalBgHex = "#ffffff",
        accentHex = "#1e66f5",
        textHex = "#4c4f69",
        greenHex = "#40a02b",
        redHex = "#d20f39",
        mauveHex = "#8839ef",
    )

    val TokyoNightDay = BisonThemePalette(
        id = "tokyo-night-day",
        displayName = "Tokyo Night Day",
        isDark = false,
        panelBgHex = "#e1e2e7",
        terminalBgHex = "#f0f1f5",
        accentHex = "#2e7de9",
        textHex = "#3760bf",
        greenHex = "#587539",
        redHex = "#f52a65",
        mauveHex = "#7847bd",
    )

    val OneLight = BisonThemePalette(
        id = "one-light",
        displayName = "One Light",
        isDark = false,
        panelBgHex = "#fafafa",
        terminalBgHex = "#ffffff",
        accentHex = "#4078f2",
        textHex = "#383a42",
        greenHex = "#50a14f",
        redHex = "#e45649",
        mauveHex = "#a626a4",
    )

    val SolarizedLight = BisonThemePalette(
        id = "solarized-light",
        displayName = "Solarized Light",
        isDark = false,
        panelBgHex = "#fdf6e3",
        terminalBgHex = "#fefbf1",
        accentHex = "#268bd2",
        textHex = "#657b83",
        greenHex = "#859900",
        redHex = "#dc322f",
        mauveHex = "#d33682",
    )

    val KanagawaLotus = BisonThemePalette(
        id = "kanagawa-lotus",
        displayName = "Kanagawa Lotus",
        isDark = false,
        panelBgHex = "#f2ecbc",
        terminalBgHex = "#f7f3d7",
        accentHex = "#4d699b",
        textHex = "#545464",
        greenHex = "#6f894e",
        redHex = "#c84053",
        mauveHex = "#624c83",
    )

    val RosePineDawn = BisonThemePalette(
        id = "rose-pine-dawn",
        displayName = "Rosé Pine Dawn",
        isDark = false,
        panelBgHex = "#faf4ed",
        terminalBgHex = "#fffaf3",
        accentHex = "#907aa9",
        textHex = "#464261",
        greenHex = "#286983",
        redHex = "#b4637a",
        mauveHex = "#907aa9",
    )

    /**
     * All 19 built-in palettes in canonical order.
     */
    val all: List<BisonThemePalette> = listOf(
        GruvboxDark,
        CatppuccinMocha,
        TokyoNight,
        Vesper,
        RosePine,
        KanagawaWave,
        NordFrost,
        Dracula,
        OneDark,
        SolarizedDark,
        OledPitchBlack,
        ClassicTerminal,
        GruvboxLight,
        CatppuccinLatte,
        TokyoNightDay,
        OneLight,
        SolarizedLight,
        KanagawaLotus,
        RosePineDawn,
    )

    private val byIdMap: Map<String, BisonThemePalette> = all.associateBy { it.id }

    val darkThemes: List<BisonThemePalette> = all.filter { it.isDark }
    val lightThemes: List<BisonThemePalette> = all.filter { !it.isDark }

    fun byId(id: String?): BisonThemePalette =
        if (id.isNullOrBlank()) GruvboxDark else byIdMap[id.trim().lowercase()] ?: GruvboxDark

    /**
     * Resolves the effective [BisonThemePalette] given the user's global theme ID (defaulting to
     * `gruvbox`) and an optional per-host theme override ID.
     */
    fun resolveTheme(
        globalThemeId: String?,
        hostOverrideThemeId: String? = null,
    ): BisonThemePalette {
        val candidateOverride = hostOverrideThemeId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (candidateOverride != null) {
            byIdMap[candidateOverride]?.let { return it }
        }
        return byId(globalThemeId)
    }
}

/**
 * Top-level convenience helper for resolving the effective theme from [globalThemeId] and
 * [hostOverrideThemeId].
 */
fun resolveTheme(
    globalThemeId: String?,
    hostOverrideThemeId: String? = null,
): BisonThemePalette = BisonThemes.resolveTheme(globalThemeId, hostOverrideThemeId)
