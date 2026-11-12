package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UTF-8 text, wide cells, zero-width characters, and autowrap. The cursor values in the comments come from
 * `mosh-server` 1.4.0 at 20 columns, with `CSI 6n` after each case (black-box test).
 */
class TextTest {

    @Test
    fun utf8TextGoesIntoOneCellForEachCodePoint() {
        val screen = screen().after("aé€😀b")

        assertEquals("é", screen.cell(0, 1).text)
        assertEquals("€", screen.cell(0, 2).text)
        assertEquals("😀", screen.cell(0, 3).text)
        assertEquals("b", screen.cell(0, 5).text)
        assertEquals(0 to 6, screen.cursor)
    }

    @Test
    fun badUtf8GivesTheReplacementCharacter() {
        val bytes = byteArrayOf(0x61, 0xC3.toByte(), 0x62, 0xFF.toByte(), 0xE2.toByte(), 0x82.toByte(), 0x0D, 0x63)
        val screen = MoshTerminal().apply(screen(), bytes)

        // "a", U+FFFD for the short sequence, "b", U+FFFD for 0xFF, and U+FFFD for the sequence that CR stops.
        assertEquals("c�b��     ", screen.rowText(0))
    }

    @Test
    fun overlongAndSurrogateSequencesGiveTheReplacementCharacter() {
        val overlong = byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xAF.toByte())
        val surrogate = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())

        assertEquals("�", MoshTerminal().apply(screen(), overlong).cell(0, 0).text)
        assertEquals("�", MoshTerminal().apply(screen(), surrogate).cell(0, 0).text)
    }

    @Test
    fun aWideCharacterUsesTwoCellsAndCoversTheSecondCell() {
        // "a" and U+4E00: 1;4.
        val screen = screen().after("a一b")

        assertTrue(screen.cell(0, 1).wide)
        assertTrue(screen.row(0).isCovered(2))
        assertEquals("b", screen.cell(0, 3).text)
        assertEquals("a一b      ", screen.rowText(0))
        assertEquals(0 to 4, screen.cursor)
    }

    @Test
    fun theCellAfterAWideCharacterGetsOnlyTheBackgroundOfThePen() {
        // mosh-server 1.4.0 drew that cell as "CSI 0;41m" and a space after the pen 1;4;7;41.
        val screen = screen().after("${CSI}1;4;7;41m一${CSI}0m${CSI}1;1Hz")

        assertEquals("z", screen.cell(0, 0).text)
        assertEquals(ScreenCell.empty(background(1)), screen.cell(0, 1))
    }

    @Test
    fun aCharacterInTheRightHalfOfAWideCharacterStaysCovered() {
        // mosh-server 1.4.0 still drew U+4E00 after "CUP 1;2" and "x".
        val screen = screen().after("一${CSI}1;2Hx")

        assertTrue(screen.cell(0, 0).wide)
        assertEquals("x", screen.cell(0, 1).text)
        assertEquals("一        ", screen.rowText(0))
    }

    @Test
    fun aNarrowCharacterOnTheLeftHalfReplacesTheWideCharacter() {
        val screen = screen().after("ab一cd${CSI}1;3Hx")

        assertEquals("abx cd    ", screen.rowText(0))
    }

    @Test
    fun aWideCharacterInTheSecondToLastColumnSetsTheWrapFlag() {
        // U+4E00 at column 19: 1;20, and "y" after it: 2;2.
        val screen = screen(columns = 5).after("${CSI}1;4H一")

        assertTrue(screen.wrapPending)
        assertEquals(0 to 4, screen.cursor)
        assertEquals(1 to 1, screen.after("y").cursor)
        // mosh-server 1.4.0 sent U+4E00 and BS for the cursor in the last column, and two BS for the column before.
        assertEquals(0 to 4, screen.after("\b").cursor)
        assertEquals(0 to 3, screen.after("\b\b").cursor)
    }

    @Test
    fun aWideCharacterThatDoesNotFitErasesTheLastCellAndGoesToTheNextRow() {
        // U+4E00 at column 20: 2;3. mosh-server 1.4.0 erased the old "q" in column 20.
        val screen = screen(columns = 5).after("${CSI}1;5Hq${CSI}41m${CSI}1;5H一")

        assertEquals(ScreenCell.empty(background(1)), screen.cell(0, 4))
        assertTrue(screen.cell(1, 0).wide)
        assertEquals(1 to 2, screen.cursor)
        assertTrue(screen.row(0).wrapped)
    }

    @Test
    fun aWideCharacterAfterTheWrapFlagGoesToTheNextRow() {
        // mosh-server 1.4.0 sent "CUP 1;20", "a", and U+4E00 to put U+4E00 at the start of row 2.
        val screen = screen(columns = 5).after("${CSI}1;5Ha一")

        assertEquals("    a", screen.rowText(0))
        assertEquals("一   ", screen.rowText(1))
        assertEquals(1 to 2, screen.cursor)
    }

    @Test
    fun aZeroWidthCharacterJoinsTheCellBeforeTheCursor() {
        // "a" and U+0301: 1;2.
        val screen = screen().after("áb")

        assertEquals("á", screen.cell(0, 0).text)
        assertEquals("b", screen.cell(0, 1).text)
        assertEquals(0 to 2, screen.cursor)
    }

    @Test
    fun aZeroWidthCharacterAfterAWideCharacterJoinsTheWideCell() {
        // U+4E00 and U+0301: 1;3.
        val screen = screen().after("一́")

        assertEquals("一́", screen.cell(0, 0).text)
        assertEquals(0 to 2, screen.cursor)
    }

    @Test
    fun aZeroWidthCharacterInTheWrapStateJoinsTheLastCell() {
        // "X" in column 20 and U+0301: 1;20. Then "y": 2;2.
        val screen = screen(columns = 5).after("${CSI}1;5HX́")

        assertEquals("X́", screen.cell(0, 4).text)
        assertTrue(screen.wrapPending)
        assertEquals(1 to 1, screen.after("y").cursor)
        // U+4E00 at column 19 and U+0301: 1;20.
        assertEquals("一́", screen(columns = 5).after("${CSI}1;4H一́").cell(0, 3).text)
    }

    @Test
    fun aZeroWidthCharacterInTheFirstColumnJoinsTheCellAtTheCursor() {
        // "ab", a move to column 1, and U+0301: 1;1, and mosh-server 1.4.0 drew "a" with U+0301. A space works the
        // same way.
        val letter = screen().after("ab\ŕ")
        val space = screen().after(" \ŕ")

        assertEquals("á", letter.cell(0, 0).text)
        assertEquals(0 to 0, letter.cursor)
        assertEquals(" ́", space.cell(0, 0).text)
        assertEquals(0 to 0, space.cursor)
    }

    @Test
    fun aZeroWidthCharacterWithNoCharacterBeforeItGetsANoBreakSpace() {
        // U+0301 on an empty row: 1;2, and mosh-server 1.4.0 drew U+00A0 with U+0301 and the style of the cell.
        val first = screen().after("${CSI}41m${CSI}2K${CSI}0m\ŕ")
        val second = screen().after("${CSI}1;2H́")

        assertEquals(ScreenCell(" ́", wide = false, style = background(1)), first.cell(0, 0))
        assertEquals(0 to 1, first.cursor)
        assertEquals(" ́", second.cell(0, 1).text)
        assertEquals(0 to 2, second.cursor)
    }

    @Test
    fun aZeroWidthCharacterOnACoveredCellGetsANoBreakSpace() {
        // U+4E00, CUP 1;2, and U+0301: 1;3, and mosh-server 1.4.0 still drew U+4E00 alone.
        val covered = screen().after("一${CSI}1;2H́")
        // U+4E00, "x", CUP 1;3, and U+0301: 1;3.
        val after = screen().after("一x${CSI}1;3H́")

        assertEquals("一", covered.cell(0, 0).text)
        assertEquals(0 to 2, covered.cursor)
        assertEquals("一́", after.cell(0, 0).text)
        assertEquals(0 to 2, after.cursor)
    }

    @Test
    fun zeroWidthCharactersStopAtTheTextLimitOfACell() {
        val screen = screen().after("a" + "́".repeat(100))

        assertTrue(screen.cell(0, 0).text.length <= 32)
        assertEquals(0 to 1, screen.cursor)
    }

    @Test
    fun aCharacterInTheLastColumnSetsTheWrapFlagAndTheNextCharacterWraps() {
        // 20 characters: 1;20. 21 characters: 2;2. 25 characters: 2;6.
        val full = screen(columns = 5).after("abcde")
        val wrapped = full.after("fg")

        assertTrue(full.wrapPending)
        assertEquals(0 to 4, full.cursor)
        assertFalse(full.row(0).wrapped)
        assertEquals("fg   ", wrapped.rowText(1))
        assertEquals(1 to 2, wrapped.cursor)
        assertTrue(wrapped.row(0).wrapped)
    }

    @Test
    fun theWrapMarkOfARowClearsWhenAnEraseReachesItsEnd() {
        val wrapped = screen(columns = 5).after("abcdefg")

        assertTrue(wrapped.row(0).wrapped)
        assertFalse(wrapped.after("${CSI}1;3H${CSI}K").row(0).wrapped)
        assertFalse(wrapped.after("${CSI}1;5H${CSI}X").row(0).wrapped)
        assertFalse(wrapped.after("${CSI}2J").row(0).wrapped)
        assertTrue(wrapped.after("${CSI}1;1H${CSI}X").row(0).wrapped)
    }

    @Test
    fun theScreenTextJoinsWrappedRows() {
        // mosh-server 1.4.0 at 40 columns wrapped "already has an attached client" to the next row.
        val screen = screen(columns = 20, rows = 4).after("error: pane already has an attached client\r\n$ ")

        assertTrue(screen.row(0).wrapped)
        assertTrue(screen.row(1).wrapped)
        assertEquals("error: pane already has an attached client\n$", screen.text())
    }

    @Test
    fun c1ControlsInUtf8AreIgnored() {
        val terminal = MoshTerminal()
        val screen = screen().after("a\u0085b", terminal)

        assertEquals("ab        ", screen.rowText(0))
        assertEquals(1L, terminal.ignoredCount)
    }
}
