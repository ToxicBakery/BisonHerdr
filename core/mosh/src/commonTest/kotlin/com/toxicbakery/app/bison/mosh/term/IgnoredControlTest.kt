package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Controls that are not in the census have no effect, and the terminal counts them. The wrap flag follows the
 * emulator of `mosh-server` 1.4.0: "X" in the last column, the control, and "y" (black-box test).
 */
class IgnoredControlTest {

    private fun ignored(sequence: String): Pair<MoshScreen, Long> {
        val terminal = MoshTerminal()
        val screen = screen(columns = 5).after("ab$sequence", terminal)
        return screen to terminal.ignoredCount
    }

    @Test
    fun controlsOutsideTheCensusChangeNoCellAndAreCounted() {
        val sequences = listOf(
            "${CSI}2A", "${CSI}3C", "${CSI}2@", "${CSI}P", "${CSI}L", "${CSI}M", "${CSI}S", "${CSI}T", "${CSI}5n",
            "${CSI}4h", "${CSI}>c", "${CSI}1 q", "${E}7", "${E}8", "${E}M", "${E}=", "$E(0", "${E}c", "\u0001", "\t",
            "\u000b", "\u000c", "\u000e", "\u007f", "${E}Pq#0;1;2;3\u001b\\", "${E}_apc$E\\", "$E]8;;x\u0007",
        )
        for (sequence in sequences) {
            val (screen, count) = ignored(sequence)
            assertEquals("ab   ", screen.rowText(0), sequence)
            assertEquals(0 to 2, screen.cursor, sequence)
            assertEquals(1L, count, sequence)
        }
    }

    @Test
    fun anIgnoredControlClearsTheWrapFlagLikeTheServer() {
        // mosh-server 1.4.0 put "y" in the last column after these controls.
        for (sequence in listOf("\u0001", "\u000e", "${CSI}5n", "${CSI}0C", "${CSI}D", "${E}7", "${E}=")) {
            val screen = screen(columns = 5).after("${CSI}1;5HX${sequence}y")
            assertEquals("    y", screen.rowText(0), sequence)
            assertTrue(screen.wrapPending, sequence)
        }
    }

    @Test
    fun htDelAndOscKeepTheWrapFlagLikeTheServer() {
        // mosh-server 1.4.0 put "y" on the next row after these controls.
        for (sequence in listOf("\t", "\u007f", "$E]52;c;aGk=\u0007")) {
            val screen = screen(columns = 5).after("${CSI}1;5HX${sequence}y")
            assertEquals("y    ", screen.rowText(1), sequence)
        }
    }

    @Test
    fun c0ControlsInsideACsiHaveTheirFunction() {
        val screen = screen().after("${CSI}2\r;3H")

        assertEquals(1 to 2, screen.cursor)
    }

    @Test
    fun canAndSubStopASequence() {
        val terminal = MoshTerminal()
        val screen = screen().after("${CSI}2\u0018x$E]0;t\u001ay", terminal)

        assertEquals("xy", screen.rowText(0).trim())
        assertEquals("", screen.title)
        assertEquals(2L, terminal.ignoredCount)
    }

    @Test
    fun escInsideASequenceStartsANewSequence() {
        val screen = screen().after("${CSI}12${CSI}2;2Hx$E]0;t${CSI}1;1Hy")

        assertEquals("y", screen.rowText(0).trim())
        assertEquals("x", screen.rowText(1).trim())
        assertEquals("", screen.title)
    }

    @Test
    fun hugeParametersStopAtTheLimit() {
        val screen = screen().after("${CSI}99999999999999999999;99999999999999999999H")

        assertEquals(3 to 9, screen.cursor)
        assertEquals(0 to 0, screen.after("${CSI}" + "1;".repeat(1000) + "H").cursor)
    }

    @Test
    fun aLongOscIsIgnored() {
        val terminal = MoshTerminal()
        val screen = screen().after("$E]2;" + "t".repeat(1024 * 1024 + 1) + "\u0007x", terminal)

        assertEquals("", screen.title)
        assertEquals("x", screen.rowText(0).trim())
        assertEquals(1L, terminal.ignoredCount)
    }

    @Test
    fun aPrivateMarkerAfterAParameterIsIgnored() {
        val terminal = MoshTerminal()
        val screen = screen().after("${CSI}2?5h", terminal)

        assertFalse(screen.modes.reverseVideo)
        assertEquals(1L, terminal.ignoredCount)
    }
}
