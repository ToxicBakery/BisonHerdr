package com.toxicbakery.app.bison.mosh.term

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The width rules of decision 3. The expected values come from a probe in `mosh-server` 1.4.0 with glibc 2.43. The
 * probe wrote each code point after "a" and asked for the cursor position (`CSI 6n`).
 */
class CellWidthTest {

    @Test
    fun theSampleCodePointsOfThePlanHaveTheWidthsOfTheProbe() {
        val expected = mapOf(
            0x00AD to 1,
            0x200B to 0,
            0x200D to 0,
            0x0301 to 0,
            0x20DD to 0,
            0xFE0F to 0,
            0x1160 to 0,
            0x11A8 to 0,
            0xD7B0 to 0,
            0x1E5EE to 0,
            0x1F1E6 to 1,
            0x1F1FA to 1,
            0x231A to 2,
            0x4E00 to 2,
            0xAC00 to 2,
            0xFF21 to 2,
            0x1F600 to 2,
            0x1F3FB to 2,
            0x00B7 to 1,
            0xE000 to 1,
        )
        for ((codePoint, width) in expected) {
            assertEquals(width, CellWidth.of(codePoint), "U+${codePoint.toString(16).uppercase()}")
        }
    }

    @Test
    fun theFormatCharactersThatGlibcDrawsHaveWidthOne() {
        // Prepended_Concatenation_Mark, the interlinear annotation characters, and the Egyptian format controls.
        for (codePoint in listOf(0x0600, 0x0605, 0x06DD, 0x070F, 0x0890, 0x08E2, 0x110BD, 0x110CD)) {
            assertEquals(1, CellWidth.of(codePoint), "U+${codePoint.toString(16).uppercase()}")
        }
        for (codePoint in listOf(0xFFF9, 0xFFFB, 0x13430, 0x1343F)) {
            assertEquals(1, CellWidth.of(codePoint), "U+${codePoint.toString(16).uppercase()}")
        }
    }

    @Test
    fun theOtherExceptionsOfTheProbeHaveTheirWidths() {
        assertEquals(0, CellWidth.of(0x3164), "HANGUL FILLER")
        assertEquals(0, CellWidth.of(0xFFA0), "HALFWIDTH HANGUL FILLER")
        assertEquals(0, CellWidth.of(0x11FF))
        assertEquals(0, CellWidth.of(0xD7FB))
        // U+D7FC to U+D7FF are not assigned. glibc 2.43 does not draw them, so the server never sends them.
        assertEquals(1, CellWidth.of(0xD7FF))
        assertEquals(2, CellWidth.of(0x3248), "CIRCLED NUMBER TEN ON BLACK SQUARE")
        assertEquals(2, CellWidth.of(0x324F))
        assertEquals(0, CellWidth.of(0x061C), "ARABIC LETTER MARK")
        assertEquals(0, CellWidth.of(0xE0001), "LANGUAGE TAG")
    }

    @Test
    fun asciiAndLatinTextHasWidthOne() {
        for (codePoint in 0x20..0x2FF) assertEquals(1, CellWidth.of(codePoint), "U+${codePoint.toString(16)}")
    }

    @Test
    fun codePointsThatAreNotAssignedHaveWidthOne() {
        assertEquals(1, CellWidth.of(0x0378))
        assertEquals(1, CellWidth.of(0x2FFFD), "East_Asian_Width W, but not assigned")
        assertEquals(1, CellWidth.of(0x10FFFF))
        assertEquals(1, CellWidth.of(0x110000), "outside the Unicode range")
    }

    @Test
    fun theTablesUseTheNewestUnicodeData() {
        assertEquals("18.0.0", CellWidth.UNICODE_VERSION)
    }
}
