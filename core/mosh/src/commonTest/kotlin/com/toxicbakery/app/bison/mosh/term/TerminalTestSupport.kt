package com.toxicbakery.app.bison.mosh.term

/** The ESC byte as text. */
const val E: String = "\u001b"

/** The CSI introducer `ESC [`. */
const val CSI: String = "\u001b["

/** Returns the screen after the UTF-8 bytes of [text]. */
fun MoshScreen.after(text: String, terminal: MoshTerminal = MoshTerminal()): MoshScreen =
    terminal.apply(this, text.encodeToByteArray())

/** Returns the text that row [index] shows. */
fun MoshScreen.rowText(index: Int): String = row(index).text()

/** Returns the cursor as (row, column). */
val MoshScreen.cursor: Pair<Int, Int> get() = cursorRow to cursorColumn

/** Returns the cell at [row] and [column]. */
fun MoshScreen.cell(row: Int, column: Int): ScreenCell = row(row)[column]

/** Returns a blank screen of [columns] and [rows]. */
fun screen(columns: Int = 10, rows: Int = 4): MoshScreen = MoshScreen.blank(columns, rows)

/** Returns the style with only a background of the palette index [index]. */
fun background(index: Int): CellStyle = CellStyle(background = CellColor.Indexed.of(index))
