package com.toxicbakery.app.bison.mosh.term

import com.toxicbakery.app.bison.mosh.wire.HostEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diffs that `mosh-server` 1.4.0 sent to a black-box test client. The program in the server printed a known text,
 * so the test knows the screen of the server. The client must get the same screen from the diffs.
 */
class CensusStreamTest {

    /** The first reply for a client of [columns]x[rows], and the echo of the gate key. */
    private fun connected(columns: Int, rows: Int): MoshScreen {
        val paint = "${CSI}r${CSI}0m${CSI}H${CSI}2J${CSI}?25l" + "${CSI}K\n".repeat(rows - 1) + "${CSI}K" +
            "${CSI}1;1H${CSI}?25h${CSI}0m${CSI}?2004l${CSI}?1003l${CSI}?1002l${CSI}?1001l${CSI}?1000l${CSI}?1004l" +
            "${CSI}?1015l${CSI}?1006l${CSI}?1005l"
        val screen = MoshTerminal().apply(
            MoshScreen.INITIAL,
            listOf(HostEvent.Resize(columns, rows), HostEvent.HostBytes(paint.encodeToByteArray())),
        )
        return screen.after("\n")
    }

    private fun MoshScreen.afterAll(diffs: List<String>): MoshScreen =
        diffs.fold(this) { screen, diff -> screen.after(diff) }

    private fun lineStyle(index: Int): CellStyle = background(index % 8)

    @Test
    fun linesThatScrollWithARegionAndLf() {
        // The program printed "line N" with the background 4(N % 8) and LF for N = 1 to 20, at 30x6.
        val diffs = listOf(
            "${CSI}?25l\n${CSI}0;41mline 1\r\n${CSI}?25h${CSI}0m",
            "${CSI}0;42mline 2${CSI}?25l\r\n${CSI}0;43mline 3\r\n${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n${CSI}r${CSI}4;1H${CSI}0;44mline 4\r\n${CSI}0;45mline 5\r\n" +
                "${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n\n${CSI}r${CSI}3;1H${CSI}0;46mline 6\r\n${CSI}0;47mline 7\r\n" +
                "${CSI}0;40mline 8\r\n${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n${CSI}r${CSI}4;1H${CSI}0;41mline 9\r\n${CSI}0;42mline 10\r\n" +
                "${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n\n${CSI}r${CSI}3;1H${CSI}0;43mline 11\r\n" +
                "${CSI}0;44mline 12\r\n${CSI}0;45mline 13\r\n${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n${CSI}r${CSI}4;1H${CSI}0;46mline 14\r\n${CSI}0;47mline 15\r\n" +
                "${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n${CSI}r${CSI}4;1H${CSI}0;40mline 16\r\n${CSI}0;41mline 17\r\n" +
                "${CSI}?25h${CSI}0m",
            "${CSI}0m${CSI}1;5r${CSI}?25l${CSI}5;1H\n\n\n${CSI}r${CSI}3;1H${CSI}0;42mline 18\r\n" +
                "${CSI}0;43mline 19\r\n${CSI}0;44mline 20\r\n${CSI}?25h${CSI}0m",
        )
        val screen = connected(30, 6).afterAll(diffs)

        for (row in 0 until 5) {
            val number = 16 + row
            val text = "line $number"
            assertEquals(text.padEnd(30), screen.rowText(row))
            for (column in text.indices) assertEquals(lineStyle(number), screen.cell(row, column).style)
            assertEquals(CellStyle.DEFAULT, screen.cell(row, text.length).style)
        }
        assertEquals(" ".repeat(30), screen.rowText(5))
        assertEquals(5 to 0, screen.cursor)
        assertEquals(0 to 5, screen.scrollTop to screen.scrollBottom)
        assertTrue(screen.modes.cursorVisible)

        // Then the program set the region 2;4, printed "region", LF, LF, and "x" on row 4, and reset the region.
        val last = screen.after(
            "${CSI}?25l${CSI}2;1Hregion${CSI}0;43m9\r\n${CSI}0m${CSI}K\nx${CSI}K${CSI}1;1H${CSI}?25h",
        )
        assertEquals(listOf("line 16", "region9", "", "x", "line 20", ""), (0 until 6).map { last.rowText(it).trim() })
        assertEquals(lineStyle(19), last.cell(1, 6).style)
        assertEquals(0 to 0, last.cursor)
    }

    @Test
    fun textThatWrapsAfterACupToTheLastColumn() {
        // The program printed 25 letters at 20 columns.
        val screen = connected(20, 3).after("${CSI}?25l${CSI}1;1Habcdefghijklmnopqrs${CSI}1;20Htuvwxy${CSI}?25h")

        assertEquals("abcdefghijklmnopqrst", screen.rowText(0))
        assertEquals("uvwxy", screen.rowText(1).trim())
        assertTrue(screen.row(0).wrapped)
        assertEquals(1 to 5, screen.cursor)
        assertEquals("abcdefghijklmnopqrstuvwxy\n", screen.text())
    }

    @Test
    fun textThatEndsInTheLastColumnWithNoWrap() {
        // The program printed "ab" in columns 19 and 20. The server moved the cursor with CR, LF, and CUP.
        val screen = connected(20, 3).after("${CSI}?25l${CSI}1;19Hab\r\n${CSI}1;20H${CSI}?25h")

        assertEquals("ab", screen.rowText(0).trim())
        assertFalse(screen.row(0).wrapped)
        assertEquals(0 to 19, screen.cursor)
        assertFalse(screen.wrapPending)
    }

    @Test
    fun theLastCellOfTheBottomRowAndBsForTheCursor() {
        // The program printed "x" in the last cell of the bottom row, and then zero, one, or two BS. A full paint
        // after a resize put the cursor in columns 20, 19, and 18. The diffs used one more BS than the program.
        val expected = listOf(19, 18, 17)
        for ((count, column) in expected.withIndex()) {
            val diff = "${CSI}?25l${CSI}1;1Hab${CSI}3;20Hx" + "\b".repeat(count + 1) + "${CSI}?25h"
            val screen = connected(20, 3).after(diff)

            assertEquals("x", screen.cell(2, 19).text)
            assertEquals(2 to column, screen.cursor)
            assertFalse(screen.wrapPending)
        }
        // A wide character in the last two cells works the same way.
        val wide = connected(20, 3).after("${CSI}?25l${CSI}1;1Hab${CSI}3;19H\u4e00\b${CSI}?25h")
        assertEquals(2 to 19, wide.cursor)
    }

    @Test
    fun bsAfterAnEraseMovesTheCursorBack() {
        // A part of a diff of the random census, at a smaller size: "Ａvn" on the last row, EL with the background
        // 46, BS, and the pen of the cell at the cursor.
        val screen = connected(20, 3).after(
            "${CSI}3;1H${CSI}0;8;38;5;88;46mＡvn${CSI}0;46m${CSI}K\b${CSI}0;8;38;5;88;46m",
        )

        assertEquals("Ａvn", screen.rowText(2).trim())
        assertEquals(background(6), screen.cell(2, 10).style)
        assertEquals(2 to 3, screen.cursor)
        val pen = CellStyle(
            foreground = CellColor.Indexed.of(88),
            background = CellColor.Indexed.of(6),
            invisible = true,
        )
        assertEquals(pen, screen.pen)
    }

    @Test
    fun spacesWithTheBackgroundColorForAnErase() {
        // The program erased two cells with the pen 1;7;44. The server sent two spaces with the background 44.
        val screen = connected(20, 3).after("${CSI}?25l${CSI}1;1H${CSI}0;44m  ${CSI}0m${CSI}K\r${CSI}?25h")

        assertEquals(" ", screen.cell(0, 0).text)
        assertEquals(background(4), screen.cell(0, 1).style)
        assertEquals(CellStyle.DEFAULT, screen.cell(0, 2).style)
        assertEquals(0 to 0, screen.cursor)
    }

    @Test
    fun aWideCellThatANarrowCharacterReplaced() {
        // The program printed a red U+4E00 and then "z" on its left half.
        val screen = connected(20, 3).after("${CSI}?25l${CSI}1;1Hz${CSI}0;41m ${CSI}0m${CSI}K\b${CSI}?25h")

        assertEquals("z ", screen.rowText(0).substring(0, 2))
        assertEquals(background(1), screen.cell(0, 1).style)
        assertEquals(0 to 1, screen.cursor)
    }

    @Test
    fun marksWithNoCharacterBeforeThem() {
        val noBreak = connected(20, 3).after("${CSI}?25l${CSI}1;1H${CSI}0;41m ́${CSI}K${CSI}?25h${CSI}0m")
        val space = connected(20, 3).after("${CSI}?25l${CSI}1;1H ́\r${CSI}?25h")

        assertEquals(ScreenCell(" ́", wide = false, style = background(1)), noBreak.cell(0, 0))
        assertEquals(background(1), noBreak.cell(0, 19).style)
        assertEquals(" ́", space.cell(0, 0).text)
        assertEquals(0 to 0, space.cursor)
    }

    @Test
    fun aMouseModeChangeAndTheTitles() {
        val mouse = connected(20, 3).after("${CSI}?1003h").after("${CSI}?1003l${CSI}?1002h")
        val titles = connected(20, 3).after("$E]1;icon\u0007$E]2;win\u0007\u0007")

        assertEquals(MouseTracking.ButtonMotion, mouse.modes.mouseTracking)
        assertEquals("win" to "icon", titles.title to titles.iconName)
        assertEquals(1, titles.bellCount)
    }
}
