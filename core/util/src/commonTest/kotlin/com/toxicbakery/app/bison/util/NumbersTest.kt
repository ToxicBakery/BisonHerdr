package com.toxicbakery.app.bison.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumbersTest {

    @Test
    fun parseTenths_roundsRatherThanTruncating() {
        assertEquals(29, parseTenths("2.9"))
        assertEquals(3, parseTenths("0.3"))
        assertEquals(7, parseTenths("0.7"))
        assertEquals(283, parseTenths("28.3"))
        assertEquals(1_170, parseTenths("117.0"))
    }

    @Test
    fun parseTenths_isExactForValuesThatWereAlreadyCorrect() {
        assertEquals(0, parseTenths("0"))
        assertEquals(1, parseTenths("0.1"))
        assertEquals(45, parseTenths("4.5"))
        assertEquals(61, parseTenths("6.1"))
        assertEquals(5_000, parseTenths("500"))
    }

    @Test
    fun parseTenths_roundTripsThroughTheDisplayFormat() {
        for (tenths in 0..2_000) {
            assertEquals(tenths, parseTenths(formatTenths(tenths)))
        }
    }

    @Test
    fun parseTenths_rejectsNonNumericInput() {
        assertNull(parseTenths(""))
        assertNull(parseTenths("   "))
        assertNull(parseTenths("abc"))
        assertNull(parseTenths("1.2.3"))
        assertNull(parseTenths("NaN"))
    }

    @Test
    fun parseTenths_toleratesSurroundingWhitespace() {
        assertEquals(29, parseTenths(" 2.9 "))
    }

    @Test
    fun format_roundsHalfAwayFromZero() {
        assertEquals("3.3", 3.25.format(1))
        assertEquals("4.0", 3.98.format(1))
        assertEquals("0.1", 0.05.format(1))
    }

    @Test
    fun format_handlesNegativesWithoutLosingMagnitudeOrSign() {
        assertEquals("-3.3", (-3.25).format(1))
        assertEquals("-0.1", (-0.05).format(1))
        assertEquals("-2.5", (-2.5).format(1))
    }

    @Test
    fun format_keepsExactlyTheRequestedDecimals() {
        assertEquals("13.0", 13.0.format(1))
        assertEquals("3.14", 3.14159.format(2))
        assertEquals("3.10", 3.1.format(2))
        assertEquals("3", 3.4.format(0))
    }

    @Test
    fun format_passesNonFiniteValuesThrough() {
        assertEquals(Double.NaN.toString(), Double.NaN.format(1))
        assertEquals(Double.POSITIVE_INFINITY.toString(), Double.POSITIVE_INFINITY.format(1))
    }

    @Test
    fun formatTenths_rendersOneDecimal() {
        assertEquals("0.0", formatTenths(0))
        assertEquals("4.5", formatTenths(45))
        assertEquals("500.0", formatTenths(5_000))
    }
}
