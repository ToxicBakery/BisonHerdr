package com.toxicbakery.app.bison.mosh.term

/**
 * One cell of a [ScreenRow].
 *
 * [text] holds the printed character and the zero-width characters after it. It is empty when no character went
 * into the cell, for example after an erase. A [wide] cell covers the next cell of its row. The covered cell keeps
 * its own value, but the screen does not show it. `mosh-server` 1.4.0 has the same rule (black-box test): a
 * character in the right half of a wide character does not remove the wide character.
 */
data class ScreenCell(
    val text: String,
    val wide: Boolean,
    val style: CellStyle,
) {
    /** True when no character went into the cell. */
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        /** The empty cell with the default style. */
        val EMPTY: ScreenCell = ScreenCell("", wide = false, style = CellStyle.DEFAULT)

        /** Returns an empty cell with [style]. */
        fun empty(style: CellStyle): ScreenCell =
            if (style == CellStyle.DEFAULT) EMPTY else ScreenCell("", wide = false, style = style)
    }
}

/**
 * One row of a [MoshScreen]: an immutable array of cells.
 *
 * Two rows can share one array, because no code changes the array after the row exists. [MoshTerminal] copies the
 * array of a row before it changes a cell.
 */
class ScreenRow internal constructor(
    private val cells: Array<ScreenCell>,
    /** True when the cursor wrapped from the last column of this row to the next row. The text continues there. */
    val wrapped: Boolean,
) {
    /** The count of cells. */
    val size: Int get() = cells.size

    /** Returns the cell at [column]. */
    operator fun get(column: Int): ScreenCell = cells[column]

    /** Returns true when a wide cell to the left covers the cell at [column]. */
    fun isCovered(column: Int): Boolean {
        var covered = false
        for (index in 0 until column) covered = !covered && cells[index].wide
        return covered
    }

    /** Returns the text that the row shows. An empty cell gives a space. A covered cell gives nothing. */
    fun text(): String = buildString {
        var column = 0
        while (column < cells.size) {
            val cell = cells[column]
            append(cell.text.ifEmpty { " " })
            column += if (cell.wide) 2 else 1
        }
    }

    /** Returns a copy of the cells, which the caller can change. */
    internal fun copyCells(): Array<ScreenCell> = cells.copyOf()

    /** Returns this row with [wrapped]. The new row shares the cells of this row. */
    internal fun withWrapped(wrapped: Boolean): ScreenRow =
        if (wrapped == this.wrapped) this else ScreenRow(cells, wrapped)

    /** Returns true when this row and [other] share one array of cells. */
    internal fun sharesCellsWith(other: ScreenRow): Boolean = cells === other.cells

    override fun equals(other: Any?): Boolean =
        other is ScreenRow && wrapped == other.wrapped && cells.contentEquals(other.cells)

    override fun hashCode(): Int = 31 * cells.contentHashCode() + wrapped.hashCode()

    override fun toString(): String = "ScreenRow(\"${text()}\", wrapped=$wrapped)"

    companion object {
        /** Returns a row of [columns] empty cells with [style]. */
        fun blank(columns: Int, style: CellStyle = CellStyle.DEFAULT): ScreenRow {
            val cell = ScreenCell.empty(style)
            return ScreenRow(Array(columns) { cell }, wrapped = false)
        }
    }
}
