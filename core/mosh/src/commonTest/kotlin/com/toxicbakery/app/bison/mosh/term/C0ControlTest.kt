package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BEL, BS, LF, and CR, the C0 controls of the census. */
class C0ControlTest {

    @Test
    fun belCountsOneBellAndChangesNoCell() {
        val before = screen().after("ab")
        val after = before.after("\u0007\u0007")

        assertEquals(2, after.bellCount)
        assertEquals("ab        ", after.rowText(0))
        assertEquals(before.cursor, after.cursor)
    }

    @Test
    fun belClearsTheWrapFlag() {
        // mosh-server 1.4.0: "X" in the last column, BEL, and "y" gave the cursor 1;20 (black-box test).
        val screen = screen(columns = 5).after("${CSI}1;5HX\u0007y")

        assertEquals("    y", screen.rowText(0))
        assertEquals(0 to 4, screen.cursor)
        assertTrue(screen.wrapPending)
    }

    @Test
    fun bsMovesOneColumnToTheLeftAndStopsAtTheFirstColumn() {
        assertEquals(0 to 2, screen().after("abc\b").cursor)
        assertEquals(0 to 0, screen().after("a\b\b\b").cursor)
    }

    @Test
    fun bsFromTheWrapStateKeepsTheCursorInTheLastColumn() {
        // mosh-server 1.4.0 sent "x BS" for the cursor on "x" in the last cell of the bottom row, and "x BS BS" for
        // the cursor one column to the left. A full paint after a resize gave the same cursor (black-box test).
        val one = screen(columns = 5, rows = 3).after("${CSI}3;5Hx\b")
        val two = screen(columns = 5, rows = 3).after("${CSI}3;5Hx\b\b")

        assertEquals(2 to 4, one.cursor)
        assertFalse(one.wrapPending)
        assertEquals(2 to 3, two.cursor)
        assertEquals("    y", one.after("y").rowText(2))
    }

    @Test
    fun lfMovesOneRowDownAndKeepsTheColumn() {
        val screen = screen().after("ab\n")

        assertEquals(1 to 2, screen.cursor)
        assertEquals(3 to 2, screen.after("\n\n\n").cursor)
    }

    @Test
    fun lfOnTheBottomRowScrollsTheScreenUp() {
        val screen = screen(rows = 3).after("1\r\n2\r\n3\n")

        assertEquals(listOf("2", "3", ""), (0 until 3).map { row -> screen.rowText(row).trim() })
        assertEquals(2 to 1, screen.cursor)
    }

    @Test
    fun lfClearsTheWrapFlag() {
        // mosh-server 1.4.0: "X" in the last column, LF, and "y" put "y" in the last column of the next row.
        val screen = screen(columns = 5).after("${CSI}1;5HX\ny")

        assertEquals("    y", screen.rowText(1))
        assertEquals(1 to 4, screen.cursor)
        assertFalse(screen.row(0).wrapped)
    }

    @Test
    fun crMovesToTheFirstColumnAndClearsTheWrapFlag() {
        val screen = screen(columns = 5).after("abcde\r")

        assertEquals(0 to 0, screen.cursor)
        assertFalse(screen.wrapPending)
        assertEquals("Xbcde", screen.after("X").rowText(0))
    }

    @Test
    fun crLfStartsTheNextRow() {
        val screen = screen().after("hello\r\nworld")

        assertEquals("hello", screen.rowText(0).trim())
        assertEquals("world", screen.rowText(1).trim())
        assertEquals(1 to 5, screen.cursor)
    }
}
