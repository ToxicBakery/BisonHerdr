package com.toxicbakery.app.bison.common.terminal

import androidx.compose.ui.graphics.Color
import com.toxicbakery.app.bison.common.theme.BisonThemePalette
import kotlin.math.abs

/**
 * Resolves Herdr wire cell colors (`0x00_00_00_00` default, `0x01_00_00_XX` 256-color palette,
 * `0x02_RR_GG_BB` 24-bit TrueColor) against the active [BisonThemePalette].
 */
object TerminalColorResolver {

    private const val TAG_MASK: UInt = 0xFF000000u
    private const val TAG_DEFAULT: UInt = 0x00000000u
    private const val TAG_INDEXED: UInt = 0x01000000u
    private const val TAG_RGB: UInt = 0x02000000u

    private val cubeSteps = intArrayOf(0, 95, 135, 175, 215, 255)

    fun resolveForeground(
        encodedColor: UInt,
        palette: BisonThemePalette,
        isDim: Boolean = false,
    ): Color {
        val base = when (encodedColor and TAG_MASK) {
            TAG_DEFAULT -> palette.text
            TAG_RGB -> decodeTrueColor(encodedColor)
            TAG_INDEXED -> decodeIndexed256((encodedColor and 0xFFu).toInt(), palette)
            else -> palette.text
        }
        return if (isDim) base.copy(alpha = 0.62f) else base
    }

    fun resolveBackground(
        encodedColor: UInt,
        palette: BisonThemePalette,
    ): Color = when (encodedColor and TAG_MASK) {
        TAG_DEFAULT -> palette.terminalBg
        TAG_RGB -> decodeTrueColor(encodedColor)
        TAG_INDEXED -> decodeIndexed256((encodedColor and 0xFFu).toInt(), palette)
        else -> palette.terminalBg
    }

    private fun decodeTrueColor(encoded: UInt): Color {
        val r = ((encoded shr 16) and 0xFFu).toInt()
        val g = ((encoded shr 8) and 0xFFu).toInt()
        val b = (encoded and 0xFFu).toInt()
        return Color(r, g, b)
    }

    fun decodeIndexed256(index: Int, palette: BisonThemePalette): Color {
        val clamped = index.coerceIn(0, 255)
        return when {
            clamped < 16 -> decodeAnsi16(clamped, palette)
            clamped < 232 -> {
                val cubeIndex = clamped - 16
                val r = cubeSteps[(cubeIndex / 36) % 6]
                val g = cubeSteps[(cubeIndex / 6) % 6]
                val b = cubeSteps[cubeIndex % 6]
                Color(r, g, b)
            }
            else -> {
                val gray = (8 + (clamped - 232) * 10).coerceIn(0, 255)
                Color(gray, gray, gray)
            }
        }
    }

    private fun decodeAnsi16(index: Int, palette: BisonThemePalette): Color = when (index) {
        0 -> palette.terminalBg
        1 -> palette.red
        2 -> palette.green
        3 -> palette.accent
        4 -> Color(0xFF61AFEF)
        5 -> palette.mauve
        6 -> Color(0xFF56B6C2)
        7 -> palette.text
        8 -> palette.text.copy(alpha = 0.48f)
        9 -> palette.red
        10 -> palette.green
        11 -> palette.accent
        12 -> Color(0xFF89B4FA)
        13 -> palette.mauve
        14 -> Color(0xFF88C0D0)
        else -> Color.White
    }
}

/**
 * Canonical magnetic snap stops for BSP split dividers (25%, 33%, 50%, 67%, 75%).
 */
val SPLIT_SNAP_STOPS: FloatArray = floatArrayOf(0.25f, 0.3333f, 0.50f, 0.6667f, 0.75f)

/**
 * Clamps [rawRatio] to `[0.10f, 0.90f]` and snaps to the nearest canonical BSP stop when within
 * [snapTolerance].
 */
fun snapSplitRatio(
    rawRatio: Float,
    snapTolerance: Float = 0.03f,
): Float {
    val clamped = rawRatio.coerceIn(0.10f, 0.90f)
    for (stop in SPLIT_SNAP_STOPS) {
        if (abs(clamped - stop) <= snapTolerance) {
            return stop
        }
    }
    return clamped
}
