package com.toxicbakery.app.bison.mosh.term

/** The color of a cell: the default color, an index into the 256-color palette, or a 24-bit RGB value. */
sealed interface CellColor {

    /** The default color of the theme. SGR 39 and SGR 49 select it. */
    data object Default : CellColor

    /**
     * An index into the 256-color palette. SGR 30 to 37 and 40 to 47 select the indexes 0 to 7. SGR 90 to 97 and
     * 100 to 107 select the indexes 8 to 15. SGR `38;5;n` and `48;5;n` select the index n.
     */
    class Indexed private constructor(val index: Int) : CellColor {
        override fun toString(): String = "Indexed($index)"

        companion object {
            /** The count of palette entries. */
            const val PALETTE_SIZE: Int = 256

            private val all = Array(PALETTE_SIZE) { index -> Indexed(index) }

            /** Returns the color of [index]. [index] must be in 0..255. */
            fun of(index: Int): Indexed {
                require(index in 0 until PALETTE_SIZE) { "The palette index $index is not in 0..255." }
                return all[index]
            }
        }
    }

    /** A 24-bit color. SGR `38;2;r;g;b` and `48;2;r;g;b` select it. */
    data class Rgb(val red: Int, val green: Int, val blue: Int) : CellColor {
        init {
            require(red in 0..MAX && green in 0..MAX && blue in 0..MAX) {
                "The color $red, $green, $blue is not a 24-bit color."
            }
        }

        private companion object {
            const val MAX = 255
        }
    }
}

/**
 * The graphic rendition of a cell (ECMA-48 SGR). The current rendition of a screen is its pen. Each printed
 * character gets the pen.
 *
 * `mosh-server` 1.4.0 drops SGR 2, 9, and 21 from the output of its application, so its diffs do not have them
 * (black-box test). The terminal applies them with their ECMA-48 function for other input.
 */
data class CellStyle(
    val foreground: CellColor = CellColor.Default,
    val background: CellColor = CellColor.Default,
    /** SGR 1. */
    val bold: Boolean = false,
    /** SGR 2. */
    val faint: Boolean = false,
    /** SGR 3. */
    val italic: Boolean = false,
    /** SGR 4. */
    val underline: Boolean = false,
    /** SGR 21. ECMA-48 and xterm give this value the function "doubly underlined". */
    val doubleUnderline: Boolean = false,
    /** SGR 5. */
    val blink: Boolean = false,
    /** SGR 7. */
    val inverse: Boolean = false,
    /** SGR 8. */
    val invisible: Boolean = false,
    /** SGR 9. */
    val strikethrough: Boolean = false,
) {

    /**
     * The style of an erased cell: only the [background] of this pen. An erase in `mosh-server` 1.4.0 keeps the
     * background color, and drops the other attributes, also SGR 7 (black-box test).
     */
    fun erased(): CellStyle = when {
        background == CellColor.Default -> DEFAULT
        this == CellStyle(background = background) -> this
        else -> CellStyle(background = background)
    }

    companion object {
        /** The style after SGR 0. */
        val DEFAULT: CellStyle = CellStyle()
    }
}
