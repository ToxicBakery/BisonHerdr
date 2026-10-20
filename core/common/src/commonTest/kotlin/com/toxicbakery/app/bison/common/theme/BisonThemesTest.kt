package com.toxicbakery.app.bison.common.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BisonThemesTest {

    @Test
    fun all19ThemesArePresentWithUniqueIds() {
        assertEquals(19, BisonThemes.all.size)
        val uniqueIds = BisonThemes.all.map { it.id }.toSet()
        assertEquals(19, uniqueIds.size)
        assertEquals(12, BisonThemes.darkThemes.size)
        assertEquals(7, BisonThemes.lightThemes.size)
    }

    @Test
    fun gruvboxDarkIsDefaultWithExactHexValues() {
        val gruvbox = BisonThemes.GruvboxDark
        assertEquals("gruvbox", gruvbox.id)
        assertEquals("Gruvbox Dark", gruvbox.displayName)
        assertTrue(gruvbox.isDark)
        assertTrue(gruvbox.isDefault)
        assertEquals("#282828", gruvbox.panelBgHex)
        assertEquals("#1d2021", gruvbox.terminalBgHex)
        assertEquals("#d79921", gruvbox.accentHex)
        assertEquals("#ebdbb2", gruvbox.textHex)
        assertEquals("#b8bb26", gruvbox.greenHex)
        assertEquals("#fb4934", gruvbox.redHex)
        assertEquals("#d3869b", gruvbox.mauveHex)
    }

    @Test
    fun resolveThemePrefersHostOverrideAndFallsBackToGlobalOrGruvbox() {
        assertEquals(
            BisonThemes.GruvboxDark,
            resolveTheme(globalThemeId = null, hostOverrideThemeId = null),
        )
        assertEquals(
            BisonThemes.TokyoNight,
            resolveTheme(globalThemeId = "tokyo-night", hostOverrideThemeId = null),
        )
        assertEquals(
            BisonThemes.OledPitchBlack,
            resolveTheme(globalThemeId = "gruvbox", hostOverrideThemeId = "oled-black"),
        )
        assertEquals(
            BisonThemes.CatppuccinLatte,
            resolveTheme(globalThemeId = "catppuccin-latte", hostOverrideThemeId = "unknown-id"),
        )
        assertFalse(BisonThemes.CatppuccinLatte.isDark)
    }
}
