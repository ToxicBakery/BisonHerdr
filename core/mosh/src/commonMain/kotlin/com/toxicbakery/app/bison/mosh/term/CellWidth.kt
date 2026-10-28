package com.toxicbakery.app.bison.mosh.term

/**
 * The count of cells that one code point uses on the screen.
 *
 * `mosh-server` computes each width with the libc of the host, and the client must agree. The rules are the width
 * rules of decision 3 of the Mosh plan, with the exceptions that a black-box test of `mosh-server` 1.4.0 with
 * glibc 2.43 found:
 * - Width 0: General_Category Mn, Me, and Cf. Also U+200B, the jamo U+1160 to U+11FF and U+D7B0 to U+D7FF, and
 *   the Hangul fillers U+3164 and U+FFA0.
 * - Width 1, not 0: U+00AD, the Prepended_Concatenation_Mark code points, U+FFF9 to U+FFFB, and U+13430 to
 *   U+1343F.
 * - Width 2: East_Asian_Width W or F, and U+3248 to U+324F.
 * - Width 1: all other code points. Also the regional indicators U+1F1E6 to U+1F1FF, and each code point that is
 *   not assigned in the Unicode data of the app.
 *
 * `scripts/generate_cell_width.py` makes the tables from the Unicode data files.
 */
object CellWidth {

    /** The version of the Unicode data of the tables. */
    const val UNICODE_VERSION: String = CellWidthTables.UNICODE_VERSION

    /** Returns 0, 1, or 2 for [codePoint]. A value outside the Unicode range has width 1. */
    fun of(codePoint: Int): Int = when {
        codePoint < FIRST_MARK -> 1
        CellWidthTables.ZERO.hasRangeWith(codePoint) -> 0
        CellWidthTables.WIDE.hasRangeWith(codePoint) -> 2
        else -> 1
    }

    /**
     * Returns true when one range of this table holds [codePoint]. The table holds pairs of the first and the last
     * code point of each range, from the lowest range to the highest range.
     */
    private fun IntArray.hasRangeWith(codePoint: Int): Boolean {
        var low = 0
        var high = size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < this[middle * 2] -> high = middle - 1
                codePoint > this[middle * 2 + 1] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    /** The lowest code point with a width other than 1 (U+0300). */
    private const val FIRST_MARK = 0x300
}
