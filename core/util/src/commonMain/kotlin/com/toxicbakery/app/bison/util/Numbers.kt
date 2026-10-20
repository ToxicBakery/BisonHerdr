package com.toxicbakery.app.bison.util

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Formats to exactly [numOfDecimals] decimal places, rounding half away from zero.
 */
fun Double.format(numOfDecimals: Int): String {
    if (isNaN() || isInfinite()) return toString()
    if (numOfDecimals <= 0) return roundHalfAwayFromZero(this).toString()

    val scale = pow10(numOfDecimals)
    val scaled = roundHalfAwayFromZero(this * scale)
    val sign = if (scaled < 0L) "-" else ""
    val magnitude = abs(scaled)
    val whole = magnitude / scale
    val fraction = magnitude % scale
    return "$sign$whole.${fraction.toString().padStart(numOfDecimals, '0')}"
}

/**
 * Parses a user-entered one-decimal string into tenths, or `null` when not a number.
 */
fun parseTenths(text: String): Int? {
    val value = text.trim().toDoubleOrNull() ?: return null
    val tenths = value * 10
    if (tenths.isNaN()) return null
    return tenths.roundToInt()
}

/** Renders a tenths value as the one-decimal string [parseTenths] accepts. */
fun formatTenths(tenths: Int): String = (tenths / 10.0).format(1)

private fun pow10(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= 10L }
    return result
}

private fun roundHalfAwayFromZero(value: Double): Long =
    if (value < 0.0) -((-value).roundToLong()) else value.roundToLong()
