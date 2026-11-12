package com.toxicbakery.app.bison.mosh.term

/**
 * The changeable copy of a [MoshScreen] for one [MoshTerminal.apply] call (copy-on-write).
 *
 * The editor copies the list of rows at the start. It copies the cells of a row only before the first change to
 * that row. [build] makes the new screen, which shares each unchanged row with the old screen. Use an editor for one
 * [build] only.
 */
internal class ScreenEditor(screen: MoshScreen) {
    var columns: Int = screen.columns
        private set
    var rows: Int = screen.rows
        private set

    private var lines: Array<ScreenRow> = screen.copyRows()

    /** The cells that this editor owns and can change, for each row. Null until the first change to the row. */
    private var owned: Array<Array<ScreenCell>?> = arrayOfNulls(rows)
    private var wrapped: BooleanArray = BooleanArray(rows) { row -> lines[row].wrapped }
    private val blankRows = HashMap<CellStyle, ScreenRow>()

    var cursorRow: Int = screen.cursorRow
    var cursorColumn: Int = screen.cursorColumn
    var wrapPending: Boolean = screen.wrapPending
    var pen: CellStyle = screen.pen
    var scrollTop: Int = screen.scrollTop
    var scrollBottom: Int = screen.scrollBottom
    var modes: ScreenModes = screen.modes
    var title: String = screen.title
    var iconName: String = screen.iconName
    var bellCount: Int = screen.bellCount
    var clipboard: String? = screen.clipboard

    /** Returns the cell at [row] and [column]. */
    fun cell(row: Int, column: Int): ScreenCell = owned[row]?.get(column) ?: lines[row][column]

    /** Sets the cell at [row] and [column] to [cell]. */
    fun setCell(row: Int, column: Int, cell: ScreenCell) {
        val cells = owned[row] ?: lines[row].copyCells().also { copy -> owned[row] = copy }
        cells[column] = cell
    }

    /** Returns true when a wide cell to the left covers the cell at [row] and [column]. */
    fun isCovered(row: Int, column: Int): Boolean {
        var covered = false
        for (index in 0 until column) covered = !covered && cell(row, index).wide
        return covered
    }

    /** Sets the wrap mark of [row]. */
    fun setWrapped(row: Int, value: Boolean) {
        wrapped[row] = value
    }

    /** Erases the cells from [start] to [end] (not included) of [row] with [style]. */
    fun erase(row: Int, start: Int, end: Int, style: CellStyle) {
        if (start >= end) return
        if (start == 0 && end == columns) {
            lines[row] = blankRow(style)
            owned[row] = null
        } else {
            val cell = ScreenCell.empty(style)
            for (column in start until end) setCell(row, column, cell)
        }
        if (end == columns) wrapped[row] = false
    }

    /** Moves the rows from [top] to [bottom] up by one row. The bottom row becomes a blank row with [style]. */
    fun scrollUp(top: Int, bottom: Int, style: CellStyle) {
        for (row in top until bottom) {
            lines[row] = lines[row + 1]
            owned[row] = owned[row + 1]
            wrapped[row] = wrapped[row + 1]
        }
        lines[bottom] = blankRow(style)
        owned[bottom] = null
        wrapped[bottom] = false
    }

    /**
     * Changes the size to [newColumns] and [newRows]. The rows keep their place from the top, and the cells keep
     * their place from the left. The cursor moves into the new size. The scroll region becomes the full screen.
     * `mosh-server` sends a full paint after each size change, so these rules have a small effect.
     */
    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        val oldLines = lines
        val oldOwned = owned
        val oldWrapped = wrapped
        blankRows.clear()
        lines = Array(newRows) { row ->
            when {
                row >= rows -> blankRow(CellStyle.DEFAULT, newColumns)
                newColumns == columns -> oldOwned[row]?.let { cells -> ScreenRow(cells, oldWrapped[row]) }
                    ?: oldLines[row]
                else -> resizeRow(oldOwned[row]?.let { cells -> ScreenRow(cells, false) } ?: oldLines[row], newColumns)
            }
        }
        owned = arrayOfNulls(newRows)
        // A wrap mark is not correct after a change of the column count.
        wrapped = BooleanArray(newRows) { row -> row < rows && newColumns == columns && oldWrapped[row] }
        columns = newColumns
        rows = newRows
        cursorRow = cursorRow.coerceAtMost(newRows - 1)
        cursorColumn = cursorColumn.coerceAtMost(newColumns - 1)
        wrapPending = false
        scrollTop = 0
        scrollBottom = newRows - 1
    }

    /** Returns the new screen. */
    fun build(): MoshScreen {
        val built = Array(rows) { row ->
            val cells = owned[row]
            if (cells != null) ScreenRow(cells, wrapped[row]) else lines[row].withWrapped(wrapped[row])
        }
        // The new rows own the arrays now. The editor must not change them again.
        owned = arrayOfNulls(rows)
        return MoshScreen(
            columns = columns,
            rows = rows,
            rowArray = built,
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            wrapPending = wrapPending,
            pen = pen,
            scrollTop = scrollTop,
            scrollBottom = scrollBottom,
            modes = modes,
            title = title,
            iconName = iconName,
            bellCount = bellCount,
            clipboard = clipboard,
        )
    }

    private fun blankRow(style: CellStyle, width: Int = columns): ScreenRow =
        blankRows.getOrPut(style) { ScreenRow.blank(width, style) }

    private fun resizeRow(row: ScreenRow, newColumns: Int): ScreenRow {
        val cells = Array(newColumns) { column -> if (column < row.size) row[column] else ScreenCell.EMPTY }
        return ScreenRow(cells, wrapped = false)
    }
}
