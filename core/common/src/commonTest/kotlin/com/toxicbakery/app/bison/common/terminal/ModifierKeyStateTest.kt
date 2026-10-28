package com.toxicbakery.app.bison.common.terminal

import com.toxicbakery.app.bison.common.theme.BisonThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModifierKeyStateTest {

    @Test
    fun singleTapAndDoubleTapLifecycleForCtrlAndAlt() {
        var state = ModifierKeyState()
        assertFalse(state.hasActiveModifiers)

        // Single tap arms sticky one-shot
        state = state.onSingleTap(TerminalModifierKey.Ctrl)
        assertEquals(ModifierMode.StickyOneShot, state.ctrl)
        assertTrue(state.hasActiveModifiers)
        assertEquals("ctrl+c", state.formatKeyChord("c"))

        // Consuming one-shot resets StickyOneShot to Off
        val afterConsume = state.consumeOneShot()
        assertEquals(ModifierMode.Off, afterConsume.ctrl)

        // Rapid double-tap via timestamp locks modifier
        var timed = ModifierKeyState()
        timed = timed.onTap(TerminalModifierKey.Alt, timestampMs = 1_000L)
        assertEquals(ModifierMode.StickyOneShot, timed.alt)
        timed = timed.onTap(TerminalModifierKey.Alt, timestampMs = 1_200L)
        assertEquals(ModifierMode.Locked, timed.alt)

        // Locked survives consumeOneShot()
        val afterLockedConsume = timed.consumeOneShot()
        assertEquals(ModifierMode.Locked, afterLockedConsume.alt)

        // Tapping again releases Locked -> Off
        val released = afterLockedConsume.onTap(TerminalModifierKey.Alt, timestampMs = 2_000L)
        assertEquals(ModifierMode.Off, released.alt)
    }

    @Test
    fun theSpaceKeyGetsTheHerdrNameSpaceInAChord() {
        val ctrl = ModifierKeyState().onSingleTap(TerminalModifierKey.Ctrl)
        assertEquals("ctrl+space", ctrl.formatKeyChord(" "))
        val ctrlAlt = ctrl.onSingleTap(TerminalModifierKey.Alt)
        assertEquals("ctrl+alt+space", ctrlAlt.formatKeyChord(" "))
    }

    @Test
    fun snapSplitRatioSnapsToCanonicalStopsAndClampsBounds() {
        assertEquals(0.25f, snapSplitRatio(0.26f))
        assertEquals(0.3333f, snapSplitRatio(0.32f))
        assertEquals(0.50f, snapSplitRatio(0.49f))
        assertEquals(0.6667f, snapSplitRatio(0.68f))
        assertEquals(0.75f, snapSplitRatio(0.74f))
        assertEquals(0.10f, snapSplitRatio(0.02f))
        assertEquals(0.90f, snapSplitRatio(0.99f))
    }

    @Test
    fun terminalColorResolverDecodesDefaultIndexedAndTrueColor() {
        val palette = BisonThemes.GruvboxDark
        assertEquals(palette.text, TerminalColorResolver.resolveForeground(0x00000000u, palette))
        assertEquals(palette.terminalBg, TerminalColorResolver.resolveBackground(0x00000000u, palette))
        assertEquals(palette.red, TerminalColorResolver.resolveForeground(0x01000001u, palette))
        val trueColor = TerminalColorResolver.resolveForeground(0x02112233u, palette)
        assertEquals(0x11 / 255f, trueColor.red, 0.01f)
        assertEquals(0x22 / 255f, trueColor.green, 0.01f)
        assertEquals(0x33 / 255f, trueColor.blue, 0.01f)
    }
}
