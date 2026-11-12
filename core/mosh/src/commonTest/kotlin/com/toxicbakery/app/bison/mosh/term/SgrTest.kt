package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** SGR. `mosh-server` starts each SGR with 0, for example `CSI 0;1;38;5;9m`. */
class SgrTest {

    private fun pen(parameters: String): CellStyle = screen().after("${CSI}${parameters}m").pen

    private fun indexed(index: Int): CellColor = CellColor.Indexed.of(index)

    @Test
    fun eachAttributeOfTheCensusSetsItsFlag() {
        assertEquals(CellStyle(bold = true), pen("0;1"))
        assertEquals(CellStyle(faint = true), pen("0;2"))
        assertEquals(CellStyle(italic = true), pen("0;3"))
        assertEquals(CellStyle(underline = true), pen("0;4"))
        assertEquals(CellStyle(blink = true), pen("0;5"))
        assertEquals(CellStyle(inverse = true), pen("0;7"))
        assertEquals(CellStyle(invisible = true), pen("0;8"))
        assertEquals(CellStyle(strikethrough = true), pen("0;9"))
        assertEquals(CellStyle(doubleUnderline = true), pen("0;21"))
    }

    @Test
    fun zeroAndAnEmptyParameterResetThePen() {
        assertSame(CellStyle.DEFAULT, pen("1;3;41;0"))
        assertSame(CellStyle.DEFAULT, pen("1;3;41;"))
        assertSame(CellStyle.DEFAULT, screen().after("${CSI}1m${CSI}m").pen)
    }

    @Test
    fun theEightColorsSetPaletteIndexesZeroToSeven() {
        for (index in 0..7) {
            assertEquals(CellStyle(foreground = indexed(index)), pen("0;${30 + index}"))
            assertEquals(CellStyle(background = indexed(index)), pen("0;${40 + index}"))
        }
    }

    @Test
    fun the256ColorFormsSetThePaletteIndex() {
        assertEquals(CellStyle(foreground = indexed(9)), pen("0;38;5;9"))
        assertEquals(CellStyle(background = indexed(200)), pen("0;48;5;200"))
        assertEquals(CellStyle(foreground = indexed(255), bold = true), pen("0;38;5;255;1"))
    }

    @Test
    fun theTruecolorFormsSetTheRgbValue() {
        assertEquals(CellStyle(foreground = CellColor.Rgb(1, 2, 3)), pen("0;38;2;1;2;3"))
        assertEquals(
            CellStyle(foreground = CellColor.Rgb(1, 2, 3), background = indexed(200)),
            pen("0;38;2;1;2;3;48;5;200"),
        )
        assertEquals(CellStyle(background = CellColor.Rgb(255, 0, 128), italic = true), pen("0;48;2;255;0;128;3"))
    }

    @Test
    fun theColonFormsSetTheSameColors() {
        assertEquals(CellStyle(foreground = indexed(9)), pen("0;38:5:9"))
        assertEquals(CellStyle(foreground = CellColor.Rgb(1, 2, 3)), pen("0;38:2:1:2:3"))
        assertEquals(CellStyle(background = CellColor.Rgb(1, 2, 3)), pen("0;48:2::1:2:3"))
    }

    @Test
    fun aBadExtendedColorChangesNoColor() {
        assertEquals(CellStyle.DEFAULT, pen("0;38;5;256"))
        assertEquals(CellStyle.DEFAULT, pen("0;38;2;1;2"))
        assertEquals(CellStyle.DEFAULT, pen("0;38;7;1"))
        assertEquals(CellStyle.DEFAULT, pen("0;48;2;1;2;999"))
        assertEquals(CellStyle(bold = true), pen("0;1;38"))
    }

    @Test
    fun theOtherXtermValuesHaveTheirXtermFunction() {
        val all = "0;1;2;3;4;21;5;7;8;9;31;41"
        assertEquals(CellStyle(foreground = indexed(1), background = indexed(1)), pen("$all;22;23;24;25;27;28;29"))
        assertEquals(CellStyle(background = indexed(1)), pen("0;31;41;39"))
        assertEquals(CellStyle(foreground = indexed(1)), pen("0;31;41;49"))
        assertEquals(CellStyle(foreground = indexed(8), background = indexed(15)), pen("0;90;107"))
        assertEquals(CellStyle(foreground = indexed(15), background = indexed(8)), pen("0;97;100"))
    }

    @Test
    fun anUnknownValueChangesNothing() {
        assertEquals(CellStyle(bold = true), pen("0;1;6;53;73"))
    }

    @Test
    fun eachPrintedCharacterGetsThePen() {
        // mosh-server 1.4.0 sent this diff for "aBCDE", a bold red "F", "GH", an italic "I" that blinks, and "J".
        val screen = screen().after("aBCDE${CSI}0;1;38;5;9mF${CSI}0mGH${CSI}0;3;5mI${CSI}0mJ")

        assertEquals("aBCDEFGHIJ", screen.rowText(0))
        assertEquals(CellStyle.DEFAULT, screen.cell(0, 4).style)
        assertEquals(CellStyle(foreground = indexed(9), bold = true), screen.cell(0, 5).style)
        assertEquals(CellStyle(italic = true, blink = true), screen.cell(0, 8).style)
        assertEquals(CellStyle.DEFAULT, screen.cell(0, 9).style)
    }

    @Test
    fun sgrKeepsTheWrapFlag() {
        // mosh-server 1.4.0: "X" in the last column, SGR, and "y" put "y" on the next row (black-box test).
        val screen = screen(columns = 5).after("${CSI}1;5HX${CSI}0;1my")

        assertEquals("y    ", screen.rowText(1))
        assertTrue(screen.row(0).wrapped)
    }
}
