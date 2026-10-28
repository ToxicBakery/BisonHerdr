package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CUP, EL, ED, and ECH. */
class CursorAndEraseTest {

    @Test
    fun cupMovesToTheRowAndTheColumnFromOne() {
        assertEquals(2 to 4, screen().after("${CSI}3;5H").cursor)
        assertEquals("    x     ", screen().after("${CSI}1;5Hx").rowText(0))
    }

    @Test
    fun cupWithNoParametersMovesToTheTopLeft() {
        assertEquals(0 to 0, screen().after("abc\r\nde${CSI}H").cursor)
    }

    @Test
    fun cupUsesOneForAnEmptyOrZeroParameter() {
        assertEquals(0 to 0, screen().after("${CSI}5;5H${CSI}0;0H").cursor)
        assertEquals(2 to 0, screen().after("${CSI}3H").cursor)
        assertEquals(0 to 4, screen().after("${CSI};5H").cursor)
    }

    @Test
    fun cupStopsAtTheEdgeOfTheScreen() {
        assertEquals(3 to 9, screen().after("${CSI}99;99H").cursor)
    }

    @Test
    fun cupClearsTheWrapFlag() {
        // mosh-server 1.4.0: "X" in the last column, CUP to the same cell, and "y" left the cursor in that cell.
        val screen = screen(columns = 5).after("${CSI}1;5HX${CSI}1;5Hy")

        assertEquals("    y", screen.rowText(0))
        assertTrue(screen.wrapPending)
        assertEquals(0 to 4, screen.cursor)
    }

    @Test
    fun elErasesFromTheCursorToTheEndOfTheRow() {
        val screen = screen().after("abcdefghij${CSI}1;4H${CSI}K")

        assertEquals("abc       ", screen.rowText(0))
        assertEquals(0 to 3, screen.cursor)
    }

    @Test
    fun elOneAndTwoEraseToTheCursorAndTheFullRow() {
        assertEquals("    efghij", screen().after("abcdefghij${CSI}1;4H${CSI}1K").rowText(0))
        assertEquals("          ", screen().after("abcdefghij${CSI}1;4H${CSI}2K").rowText(0))
    }

    @Test
    fun anEraseGetsOnlyTheBackgroundOfThePen() {
        // mosh-server 1.4.0 sent "CSI 0;44m" and spaces for an ECH with the pen 1;7;44 (black-box test).
        val screen = screen().after("${CSI}0;1;7;44m${CSI}K")

        for (column in 0 until 10) assertEquals(ScreenCell.empty(background(4)), screen.cell(0, column))
        assertEquals(CellStyle(background = CellColor.Indexed.of(4), bold = true, inverse = true), screen.pen)
    }

    @Test
    fun anEraseInTheLastColumnClearsTheWrapFlag() {
        // mosh-server 1.4.0: "X" in the last column, EL, and "y" put "y" in the last column of the same row.
        val screen = screen(columns = 5).after("${CSI}1;5HX${CSI}Ky")

        assertEquals("    y", screen.rowText(0))
        assertEquals(0 to 4, screen.cursor)
    }

    @Test
    fun edTwoErasesTheScreenAndKeepsTheCursor() {
        val screen = screen().after("abc\r\ndef\r\nghi${CSI}2;2H${CSI}2J")

        for (row in 0 until 4) assertEquals("          ", screen.rowText(row))
        assertEquals(1 to 1, screen.cursor)
    }

    @Test
    fun edZeroAndOneEraseAfterAndBeforeTheCursor() {
        val text = "abc\r\ndef\r\nghi${CSI}2;2H"
        val after = screen(columns = 3, rows = 3).after("$text${CSI}J")
        val before = screen(columns = 3, rows = 3).after("$text${CSI}1J")

        assertEquals(listOf("abc", "d  ", "   "), (0 until 3).map(after::rowText))
        assertEquals(listOf("   ", "  f", "ghi"), (0 until 3).map(before::rowText))
    }

    @Test
    fun edWithTheBackgroundColorFillsEachRow() {
        // mosh-server 1.4.0 drew "CSI 44m CSI 2J" as "CSI 0;44m" and EL on each row (black-box test).
        val screen = screen(columns = 3, rows = 2).after("${CSI}44m${CSI}2J")

        for (row in 0 until 2) {
            for (column in 0 until 3) assertEquals(background(4), screen.cell(row, column).style)
        }
    }

    @Test
    fun echErasesCellsFromTheCursorAndKeepsTheCursor() {
        val screen = screen().after("abcdefghij${CSI}1;3H${CSI}4X")

        assertEquals("ab    ghij", screen.rowText(0))
        assertEquals(0 to 2, screen.cursor)
    }

    @Test
    fun echErasesOneCellForAnEmptyOrZeroParameter() {
        assertEquals("ab defghij", screen().after("abcdefghij${CSI}1;3H${CSI}X").rowText(0))
        assertEquals("ab defghij", screen().after("abcdefghij${CSI}1;3H${CSI}0X").rowText(0))
    }

    @Test
    fun echStopsAtTheEndOfTheRow() {
        val screen = screen().after("abcdefghij\r\nk${CSI}1;8H${CSI}99X")

        assertEquals("abcdefg   ", screen.rowText(0))
        assertEquals("k         ", screen.rowText(1))
    }

    @Test
    fun echAndEdClearTheWrapFlag() {
        assertFalse(screen(columns = 5).after("${CSI}1;5HX${CSI}X").wrapPending)
        assertFalse(screen(columns = 5).after("${CSI}1;5HX${CSI}2J").wrapPending)
    }

    @Test
    fun anEraseOfTheRightHalfOfAWideCellKeepsTheWideCell() {
        // mosh-server 1.4.0: "ab一cd", CUP to the right half, and ECH or EL kept "一" (black-box test).
        val ech = screen().after("ab一cd${CSI}1;4H${CSI}X")
        val el = screen().after("ab一cd${CSI}1;4H${CSI}K")

        assertEquals("ab一cd    ", ech.rowText(0))
        assertEquals("ab一      ", el.rowText(0))
    }

    @Test
    fun anEraseOfTheLeftHalfOfAWideCellRemovesTheWideCell() {
        val screen = screen().after("ab一cd${CSI}1;3H${CSI}X")

        assertEquals("ab  cd    ", screen.rowText(0))
    }
}
