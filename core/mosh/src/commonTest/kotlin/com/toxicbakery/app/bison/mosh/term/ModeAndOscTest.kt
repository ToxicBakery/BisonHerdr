package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DECSET and DECRST of the census modes, and OSC 0, 1, 2, and 52. */
class ModeAndOscTest {

    @Test
    fun theDefaultModesHaveAVisibleCursorAndNoMouseMode() {
        assertEquals(ScreenModes(), MoshScreen.INITIAL.modes)
        assertTrue(MoshScreen.INITIAL.modes.cursorVisible)
        assertEquals(MouseTracking.Off, MoshScreen.INITIAL.modes.mouseTracking)
    }

    @Test
    fun mode25ShowsAndHidesTheCursor() {
        assertFalse(screen().after("${CSI}?25l").modes.cursorVisible)
        assertTrue(screen().after("${CSI}?25l${CSI}?25h").modes.cursorVisible)
    }

    @Test
    fun mode5SetsReverseVideo() {
        assertTrue(screen().after("${CSI}?5h").modes.reverseVideo)
        assertFalse(screen().after("${CSI}?5h${CSI}?5l").modes.reverseVideo)
    }

    @Test
    fun eachMouseTrackingModeReplacesTheTrackingMode() {
        for (tracking in MouseTracking.entries - MouseTracking.Off) {
            assertEquals(tracking, screen().after("${CSI}?1003h${CSI}?${tracking.mode}h").modes.mouseTracking)
        }
    }

    @Test
    fun aResetOfAnyTrackingModeTurnsTrackingOff() {
        // mosh-server 1.4.0 set the tracking mode to off for "CSI ?1003h CSI ?1000l" (black-box test).
        assertEquals(MouseTracking.Off, screen().after("${CSI}?1003h${CSI}?1000l").modes.mouseTracking)
        // The diff for a change from 1003 to 1002.
        val changed = screen().after("${CSI}?1003h${CSI}?1003l${CSI}?1002h")
        assertEquals(MouseTracking.ButtonMotion, changed.modes.mouseTracking)
    }

    @Test
    fun eachMouseEncodingReplacesTheEncodingAndAResetTurnsItOff() {
        assertEquals(MouseEncoding.Sgr, screen().after("${CSI}?1006h").modes.mouseEncoding)
        assertEquals(MouseEncoding.Utf8, screen().after("${CSI}?1006l${CSI}?1005h").modes.mouseEncoding)
        assertEquals(MouseEncoding.Urxvt, screen().after("${CSI}?1006h${CSI}?1015h").modes.mouseEncoding)
        assertEquals(MouseEncoding.Default, screen().after("${CSI}?1006h${CSI}?1015l").modes.mouseEncoding)
        // A reset of a tracking mode does not change the encoding.
        assertEquals(MouseEncoding.Sgr, screen().after("${CSI}?1006h${CSI}?1000l").modes.mouseEncoding)
    }

    @Test
    fun focusEventsAndBracketedPasteHaveTheirFlags() {
        val screen = screen().after("${CSI}?2004h${CSI}?1004h")

        assertTrue(screen.modes.bracketedPaste)
        assertTrue(screen.modes.focusEvents)
        assertFalse(screen.after("${CSI}?2004l").modes.bracketedPaste)
    }

    @Test
    fun theResetsOfAFullPaintGiveTheDefaultModes() {
        // The modes part of each full paint of mosh-server 1.4.0.
        val resets = "${CSI}?2004l${CSI}?1003l${CSI}?1002l${CSI}?1001l${CSI}?1000l${CSI}?1004l" +
            "${CSI}?1015l${CSI}?1006l${CSI}?1005l"
        val screen = screen().after("${CSI}?2004h${CSI}?1002h${CSI}?1006h${CSI}?1004h$resets")

        assertEquals(ScreenModes(), screen.modes)
    }

    @Test
    fun oneSequenceCanSetSeveralModes() {
        val modes = screen().after("${CSI}?1002;1006;2004h").modes

        val expected = ScreenModes(
            mouseTracking = MouseTracking.ButtonMotion,
            mouseEncoding = MouseEncoding.Sgr,
            bracketedPaste = true,
        )
        assertEquals(expected, modes)
    }

    @Test
    fun anUnknownModeIsCountedAndChangesNothing() {
        val terminal = MoshTerminal()
        val screen = screen().after("${CSI}?1049h${CSI}?7l", terminal)

        assertEquals(ScreenModes(), screen.modes)
        assertEquals(2L, terminal.ignoredCount)
    }

    @Test
    fun modesKeepTheWrapFlag() {
        // mosh-server 1.4.0: "X" in the last column, a DECSET, and "y" put "y" on the next row (black-box test).
        val screen = screen(columns = 5).after("${CSI}1;5HX${CSI}?2004h${CSI}?25ly")

        assertEquals("y    ", screen.rowText(1))
    }

    @Test
    fun osc0SetsTheTitleAndTheIconName() {
        val screen = screen().after("$E]0;both\u0007")

        assertEquals("both", screen.title)
        assertEquals("both", screen.iconName)
    }

    @Test
    fun osc1AndOsc2SetTheIconNameAndTheTitle() {
        // mosh-server 1.4.0 sends two strings when the two values are different.
        val screen = screen().after("$E]1;icon\u0007$E]2;win\u0007")

        assertEquals("win", screen.title)
        assertEquals("icon", screen.iconName)
    }

    @Test
    fun anOscCanEndWithStAndCanHoldUtf8() {
        assertEquals("café 一", screen().after("$E]2;café 一$E\\").title)
    }

    @Test
    fun anOscKeepsTheWrapFlagAndPrintsNothing() {
        val screen = screen(columns = 5).after("${CSI}1;5HX$E]0;t\u0007y")

        assertEquals("    X", screen.rowText(0))
        assertEquals("y    ", screen.rowText(1))
    }

    @Test
    fun osc52IsIgnoredWhenTheUserOptionIsOff() {
        val terminal = MoshTerminal()
        val screen = screen().after("$E]52;c;aGVsbG8=\u0007", terminal)

        assertNull(screen.clipboard)
        assertEquals(1L, terminal.ignoredCount)
    }

    @Test
    fun osc52WritesTheDecodedTextWhenTheUserOptionIsOn() {
        // mosh-server 1.4.0 sends OSC 52 with the selection "c" and BEL.
        val terminal = MoshTerminal(acceptClipboard = true)

        assertEquals("hello", screen().after("$E]52;c;aGVsbG8=\u0007", terminal).clipboard)
        assertEquals("一", screen().after("$E]52;c;5LiA\u0007", terminal).clipboard)
        assertEquals("", screen().after("$E]52;c;\u0007", terminal).clipboard)
    }

    @Test
    fun aBadOrQueryOsc52ChangesNothing() {
        val terminal = MoshTerminal(acceptClipboard = true)
        for (sequence in listOf("$E]52;c;?\u0007", "$E]52;c;***\u0007", "$E]52;c\u0007")) {
            assertNull(screen().after(sequence, terminal).clipboard, sequence)
        }
        assertEquals(3L, terminal.ignoredCount)
    }

    @Test
    fun anUnknownOscIsCountedAndChangesNothing() {
        val terminal = MoshTerminal()
        val screen = screen().after("$E]8;;http://example.com\u0007a$E]x;y\u0007$E]7\u0007", terminal)

        assertEquals("a", screen.rowText(0).trim())
        assertEquals("", screen.title)
        assertEquals(3L, terminal.ignoredCount)
    }
}
