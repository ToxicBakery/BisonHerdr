package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DECSTBM, and LF at the bottom margin. */
class ScrollRegionTest {

    private fun numbered(rows: Int = 5): MoshScreen =
        screen(columns = 3, rows = rows).after((1..rows).joinToString("\r\n") { row -> "r$row" })

    private fun MoshScreen.texts(): List<String> = (0 until rows).map { row -> rowText(row).trim() }

    @Test
    fun decstbmSetsTheRegionAndMovesTheCursorToTheTopLeft() {
        val screen = numbered().after("${CSI}4;3H${CSI}2;4r")

        assertEquals(1, screen.scrollTop)
        assertEquals(3, screen.scrollBottom)
        assertEquals(0 to 0, screen.cursor)
    }

    @Test
    fun decstbmWithNoParametersResetsTheRegionToTheFullScreen() {
        val screen = numbered().after("${CSI}2;4r${CSI}3;2H${CSI}r")

        assertEquals(0, screen.scrollTop)
        assertEquals(4, screen.scrollBottom)
        assertEquals(0 to 0, screen.cursor)
    }

    @Test
    fun aRegionWithTheTopAtOrBelowTheBottomHasNoEffect() {
        // mosh-server 1.4.0 did not move the cursor for "CSI 5;2r" and "CSI 3;3r" (black-box test).
        for (sequence in listOf("${CSI}5;2r", "${CSI}3;3r")) {
            val screen = numbered().after("${CSI}4;3H$sequence")

            assertEquals(0, screen.scrollTop, sequence)
            assertEquals(4, screen.scrollBottom, sequence)
            assertEquals(3 to 2, screen.cursor, sequence)
        }
    }

    @Test
    fun aBottomPastTheScreenStopsAtTheLastRow() {
        val screen = numbered().after("${CSI}2;99r")

        assertEquals(1, screen.scrollTop)
        assertEquals(4, screen.scrollBottom)
    }

    @Test
    fun lfAtTheBottomMarginScrollsOnlyTheRegion() {
        val screen = numbered().after("${CSI}2;4r${CSI}4;1H\n")

        assertEquals(listOf("r1", "r3", "r4", "", "r5"), screen.texts())
        assertEquals(3 to 0, screen.cursor)
    }

    @Test
    fun lfOnTheLastRowBelowTheRegionDoesNotScroll() {
        val screen = numbered().after("${CSI}2;3r${CSI}5;1H\n\n")

        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), screen.texts())
        assertEquals(4 to 0, screen.cursor)
    }

    @Test
    fun aNewRowFromAScrollGetsOnlyTheBackgroundOfThePen() {
        // mosh-server 1.4.0 drew the new row of a scroll with the pen 42 as "CSI 0;42m CSI K" (black-box test).
        val screen = numbered(rows = 3).after("${CSI}1;4;42m${CSI}3;1H\n")

        for (column in 0 until 3) assertEquals(background(2), screen.cell(2, column).style)
    }

    @Test
    fun autowrapAtTheBottomMarginScrollsTheRegion() {
        val screen = screen(columns = 3, rows = 3).after("${CSI}3;1Habcd")

        assertEquals(listOf("", "abc", "d"), screen.texts())
        assertEquals(2 to 1, screen.cursor)
        assertTrue(screen.row(1).wrapped)
    }

    @Test
    fun autowrapOnTheLastRowBelowTheRegionStaysOnThatRow() {
        // mosh-server 1.4.0 with the region 1;3 of 6 rows: "X" in the last cell and "y" gave the cursor 6;2.
        val screen = screen(columns = 3, rows = 4).after("${CSI}1;2r${CSI}4;3HXy")

        assertEquals("y X", screen.rowText(3))
        assertEquals(3 to 1, screen.cursor)
    }

    @Test
    fun cupIsAbsoluteAlsoWithARegion() {
        assertEquals(4 to 0, numbered().after("${CSI}2;4r${CSI}5;1H").cursor)
    }

    @Test
    fun decstbmClearsTheWrapFlag() {
        assertFalse(screen(columns = 3).after("abc${CSI}5;2r").wrapPending)
        assertFalse(screen(columns = 3).after("abc${CSI}1;2r").wrapPending)
    }
}
