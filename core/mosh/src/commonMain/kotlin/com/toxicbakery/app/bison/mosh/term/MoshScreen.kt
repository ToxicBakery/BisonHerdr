package com.toxicbakery.app.bison.mosh.term

/**
 * One state of the screen of a Mosh session: an immutable value.
 *
 * [MoshTerminal.apply] returns a new screen and does not change the old screen. The new screen shares each row that
 * the diff did not change with the old screen. Thus each kept state costs one list of rows, not a full screen.
 *
 * The rows and the columns start at 0. When [wrapPending] is true, the cursor is in the last column, and the next
 * printed character goes to the start of the next row (the xterm "last column" state).
 */
class MoshScreen internal constructor(
    /** The count of columns. */
    val columns: Int,
    /** The count of rows. */
    val rows: Int,
    private val rowArray: Array<ScreenRow>,
    /** The row of the cursor. */
    val cursorRow: Int,
    /** The column of the cursor. */
    val cursorColumn: Int,
    /** True when the next printed character wraps to the next row first. */
    val wrapPending: Boolean,
    /** The style that the next printed character gets. */
    val pen: CellStyle,
    /** The first row of the scroll region (DECSTBM). */
    val scrollTop: Int,
    /** The last row of the scroll region (DECSTBM). */
    val scrollBottom: Int,
    val modes: ScreenModes,
    /** The window title (OSC 0 and OSC 2). */
    val title: String,
    /** The icon name (OSC 0 and OSC 1). */
    val iconName: String,
    /** The count of BEL controls. */
    val bellCount: Int,
    /**
     * The text of the newest clipboard write (OSC 52), or null. The app must not put it on the clipboard of the
     * device unless the user turned on that option.
     */
    val clipboard: String?,
) {
    /** The rows, from the top. Do not change the list. */
    val lines: List<ScreenRow> = rowArray.asList()

    /** Returns the row at [index]. */
    fun row(index: Int): ScreenRow = rowArray[index]

    /**
     * Returns the text of the screen. A row that wrapped joins the next row with no line break. Each line has no
     * spaces at its end. The attach check uses this text, because a long error line wraps to the next row.
     */
    fun text(): String = buildString {
        for ((index, row) in rowArray.withIndex()) {
            val text = row.text()
            if (row.wrapped && index < rowArray.lastIndex) {
                append(text)
            } else {
                append(text.trimEnd(' '))
                if (index < rowArray.lastIndex) append('\n')
            }
        }
    }

    /** Returns a copy of the rows, which the caller can change. */
    internal fun copyRows(): Array<ScreenRow> = rowArray.copyOf()

    override fun equals(other: Any?): Boolean =
        other is MoshScreen &&
            columns == other.columns &&
            rows == other.rows &&
            cursorRow == other.cursorRow &&
            cursorColumn == other.cursorColumn &&
            wrapPending == other.wrapPending &&
            pen == other.pen &&
            scrollTop == other.scrollTop &&
            scrollBottom == other.scrollBottom &&
            modes == other.modes &&
            title == other.title &&
            iconName == other.iconName &&
            bellCount == other.bellCount &&
            clipboard == other.clipboard &&
            rowArray.contentEquals(other.rowArray)

    override fun hashCode(): Int {
        var result = columns
        result = 31 * result + rows
        result = 31 * result + cursorRow
        result = 31 * result + cursorColumn
        result = 31 * result + wrapPending.hashCode()
        result = 31 * result + pen.hashCode()
        result = 31 * result + modes.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + bellCount
        return 31 * result + rowArray.contentHashCode()
    }

    override fun toString(): String =
        "MoshScreen(${columns}x$rows, cursor=$cursorRow,$cursorColumn, wrapPending=$wrapPending, modes=$modes, " +
            "title=\"$title\")"

    companion object {
        /**
         * The size of the screen of state 0. A new `mosh-server` has a screen of 80 columns and 24 rows. When the
         * first client size is also 80x24, the first reply has no size entry and no host bytes (black-box test).
         */
        const val INITIAL_COLUMNS: Int = 80

        /** See [INITIAL_COLUMNS]. */
        const val INITIAL_ROWS: Int = 24

        /**
         * The largest count of columns and of rows. A larger size from the server becomes this size. This value is
         * a choice of this client, not a Mosh value.
         */
        const val MAX_SIZE: Int = 1024

        /** The screen of state 0. */
        val INITIAL: MoshScreen = blank(INITIAL_COLUMNS, INITIAL_ROWS)

        /** Returns an empty screen of [columns] and [rows], with the cursor at the top left and the default modes. */
        fun blank(columns: Int, rows: Int): MoshScreen {
            val width = columns.coerceIn(1, MAX_SIZE)
            val height = rows.coerceIn(1, MAX_SIZE)
            val row = ScreenRow.blank(width)
            return MoshScreen(
                columns = width,
                rows = height,
                rowArray = Array(height) { row },
                cursorRow = 0,
                cursorColumn = 0,
                wrapPending = false,
                pen = CellStyle.DEFAULT,
                scrollTop = 0,
                scrollBottom = height - 1,
                modes = ScreenModes(),
                title = "",
                iconName = "",
                bellCount = 0,
                clipboard = null,
            )
        }
    }
}
