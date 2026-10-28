package com.toxicbakery.app.bison.mosh.term

import com.toxicbakery.app.bison.mosh.wire.HostEvent
import kotlin.io.encoding.Base64

/**
 * Applies the host diffs of `mosh-server` to a [MoshScreen] (decision 3).
 *
 * The server runs the application in its own emulator. It sends bytes that change the old client screen into the
 * new screen (wire fact 9). These bytes use a small, fixed set of controls. The terminal applies the ECMA-48 and
 * xterm function of each control of that set:
 * - C0: BEL, BS, LF, and CR.
 * - CUP (`CSI row;column H`), EL (`CSI K`), ED (`CSI J`), ECH (`CSI n X`), and DECSTBM (`CSI top;bottom r`).
 * - SGR, DECSET and DECRST of the modes in [ScreenModes], and OSC 0, 1, 2, and 52.
 * - UTF-8 text with the widths of [CellWidth], and autowrap at the right margin.
 *
 * The terminal ignores each other control and counts it in [ignoredCount]. It never throws on input from the
 * network.
 *
 * The rules for the cursor come from black-box tests of `mosh-server` 1.4.0. A probe in the server asked for the
 * cursor position (`CSI 6n`), and a full paint after a resize gave the true screen of the server:
 * - A character in the last column sets [MoshScreen.wrapPending]. The next printed character first moves to the
 *   start of the next row.
 * - BS in the wrap state only clears [MoshScreen.wrapPending], and the cursor stays in the last column. The server
 *   draws the last cell of the bottom row with this rule: "x BS" puts the cursor on "x". The emulator of the server
 *   moves the cursor of its application one column more, but the diffs use this rule.
 * - Each control clears [MoshScreen.wrapPending], except SGR, DECSET, DECRST, HT, DEL, and an OSC string. Thus
 *   BEL, LF, EL, ED, ECH, and DECSTBM also clear it. An ignored control clears it too.
 * - A wide character that does not fit in the last column erases that cell and goes to the next row.
 * - A zero-width character joins the cell before the cursor, or the cell at the cursor in the first column or
 *   when [MoshScreen.wrapPending] is true. A covered cell gives its wide cell. When that cell is empty, or the
 *   cursor is on a covered cell, U+00A0 with the character goes into the cell at the cursor. The cursor then
 *   moves one column.
 * - An erase, the cell after a wide character, and a new row from a scroll get only the background of the pen.
 *
 * Each [apply] starts in the ground state of the parser, because the screen of a state must not depend on an
 * earlier diff. Use one terminal for each session. It is not safe for use by two threads at the same time.
 *
 * @param acceptClipboard The user option for clipboard writes (OSC 52). When false, the terminal ignores them.
 */
class MoshTerminal(private val acceptClipboard: Boolean = false) {

    /** The count of controls and malformed sequences that the terminal ignored. The debug log shows it. */
    var ignoredCount: Long = 0L
        private set

    /** Returns the screen after [events]. An echo ack does not change the screen. */
    fun apply(screen: MoshScreen, events: List<HostEvent>): MoshScreen {
        val changes = events.any { event ->
            event is HostEvent.Resize || (event is HostEvent.HostBytes && event.bytes.isNotEmpty())
        }
        if (!changes) return screen
        val interpreter = Interpreter(ScreenEditor(screen))
        for (event in events) {
            when (event) {
                is HostEvent.HostBytes -> interpreter.feed(event.bytes)
                is HostEvent.Resize -> interpreter.resize(event.columns, event.rows)
                is HostEvent.EchoAck -> Unit
            }
        }
        return interpreter.finish()
    }

    /** Returns the screen after [hostBytes]. */
    fun apply(screen: MoshScreen, hostBytes: ByteArray): MoshScreen =
        apply(screen, listOf(HostEvent.HostBytes(hostBytes)))

    private enum class State {
        Ground,
        Escape,
        EscapeIntermediate,
        Csi,
        CsiIgnore,
        Osc,
        OscEscape,
        ControlString,
        ControlStringEscape,
    }

    /** The parser and the control functions for one [apply] call. */
    private inner class Interpreter(private val editor: ScreenEditor) {
        private var state = State.Ground

        private var utf8Remaining = 0
        private var utf8Value = 0
        private var utf8Minimum = 0

        private val fields = IntArray(MAX_FIELDS)
        private val subFields = BooleanArray(MAX_FIELDS)
        private var fieldCount = 0
        private var field = NO_VALUE
        private var subField = false
        private var marker = 0
        private var hasIntermediate = false

        private var osc = ByteArray(OSC_START_SIZE)
        private var oscSize = 0
        private var oscOverflow = false

        private var asciiStyle: CellStyle? = null
        private val asciiCells = arrayOfNulls<ScreenCell>(ASCII_SIZE)

        fun feed(bytes: ByteArray) {
            for (byte in bytes) step(byte.toInt() and BYTE_MASK)
        }

        fun resize(columns: Int, rows: Int) {
            editor.resize(columns.coerceIn(1, MoshScreen.MAX_SIZE), rows.coerceIn(1, MoshScreen.MAX_SIZE))
        }

        fun finish(): MoshScreen {
            // A sequence that the diff did not complete has no effect.
            if (state != State.Ground || utf8Remaining > 0) ignoredCount++
            return editor.build()
        }

        private fun step(byte: Int) {
            when (state) {
                State.Ground -> ground(byte)
                State.Escape -> escape(byte)
                State.EscapeIntermediate -> escapeIntermediate(byte)
                State.Csi -> csi(byte)
                State.CsiIgnore -> csiIgnore(byte)
                State.Osc -> oscByte(byte)
                State.OscEscape -> oscEscape(byte)
                State.ControlString -> controlString(byte)
                State.ControlStringEscape -> controlStringEscape(byte)
            }
        }

        private fun ground(byte: Int) {
            if (utf8Remaining > 0) {
                if (byte in UTF8_CONTINUATION) {
                    utf8Value = (utf8Value shl UTF8_BITS) or (byte and UTF8_PAYLOAD)
                    if (--utf8Remaining == 0) endUtf8()
                    return
                }
                // The sequence stops early. The byte starts something new.
                utf8Remaining = 0
                print(REPLACEMENT)
            }
            when {
                byte < SPACE -> control(byte)
                byte == DEL -> ignore(clearsWrap = false)
                byte < ASCII_SIZE -> print(byte)
                byte in UTF8_TWO -> startUtf8(1, byte and 0x1F, 0x80)
                byte in UTF8_THREE -> startUtf8(2, byte and 0x0F, 0x800)
                byte in UTF8_FOUR -> startUtf8(3, byte and 0x07, 0x10000)
                else -> print(REPLACEMENT)
            }
        }

        private fun startUtf8(remaining: Int, value: Int, minimum: Int) {
            utf8Remaining = remaining
            utf8Value = value
            utf8Minimum = minimum
        }

        private fun endUtf8() {
            val codePoint = utf8Value
            when {
                codePoint < utf8Minimum || codePoint in SURROGATES || codePoint > MAX_CODE_POINT -> print(REPLACEMENT)
                codePoint in C1_CONTROLS -> ignore(clearsWrap = true)
                else -> print(codePoint)
            }
        }

        /** Executes a C0 control. ESC, CAN, and SUB also change the parser state. */
        private fun control(byte: Int) {
            when (byte) {
                BEL -> {
                    editor.bellCount++
                    editor.wrapPending = false
                }
                BS -> {
                    // The server diff draws the last cell of the bottom row, and then moves back with BS. The first
                    // BS only ends the wrap state.
                    if (editor.wrapPending) {
                        editor.wrapPending = false
                    } else {
                        editor.cursorColumn = (editor.cursorColumn - 1).coerceAtLeast(0)
                    }
                }
                LF -> {
                    editor.wrapPending = false
                    index()
                }
                CR -> {
                    editor.wrapPending = false
                    editor.cursorColumn = 0
                }
                ESC -> {
                    if (state != State.Ground && state != State.Escape) ignoredCount++
                    state = State.Escape
                    hasIntermediate = false
                }
                CAN, SUB -> {
                    ignore(clearsWrap = true)
                    state = State.Ground
                }
                HT -> ignore(clearsWrap = false)
                else -> ignore(clearsWrap = true)
            }
        }

        private fun escape(byte: Int) {
            when (byte) {
                CSI_START -> {
                    fieldCount = 0
                    field = NO_VALUE
                    subField = false
                    marker = 0
                    hasIntermediate = false
                    state = State.Csi
                }
                OSC_START -> {
                    oscSize = 0
                    oscOverflow = false
                    state = State.Osc
                }
                DCS_START, SOS_START, PM_START, APC_START -> {
                    ignore(clearsWrap = true)
                    state = State.ControlString
                }
                else -> escapeIntermediate(byte)
            }
        }

        private fun escapeIntermediate(byte: Int) {
            when {
                byte < SPACE -> control(byte)
                byte in INTERMEDIATES -> state = State.EscapeIntermediate
                byte < DEL -> {
                    // The client needs no escape sequence of this kind.
                    ignore(clearsWrap = true)
                    state = State.Ground
                }
                byte == DEL -> Unit
                else -> {
                    ignoredCount++
                    state = State.Ground
                    ground(byte)
                }
            }
        }

        private fun csi(byte: Int) {
            when {
                byte < SPACE -> control(byte)
                byte in DIGITS && !hasIntermediate -> {
                    val value = if (field == NO_VALUE) 0 else field
                    field = (value * DECIMAL + (byte - '0'.code)).coerceAtMost(MAX_VALUE)
                }
                (byte == SEMICOLON || byte == COLON) && !hasIntermediate -> {
                    endField()
                    subField = byte == COLON
                }
                byte in PRIVATE_MARKERS && fieldCount == 0 && field == NO_VALUE && marker == 0 && !hasIntermediate ->
                    marker = byte
                byte in INTERMEDIATES -> hasIntermediate = true
                byte in FINALS -> {
                    endField()
                    state = State.Ground
                    dispatchCsi(byte)
                }
                byte == DEL -> Unit
                byte > DEL -> {
                    ignoredCount++
                    state = State.Ground
                    ground(byte)
                }
                else -> state = State.CsiIgnore
            }
        }

        private fun csiIgnore(byte: Int) {
            when {
                byte < SPACE -> control(byte)
                byte in FINALS -> {
                    ignore(clearsWrap = true)
                    state = State.Ground
                }
                byte > DEL -> {
                    ignoredCount++
                    state = State.Ground
                    ground(byte)
                }
            }
        }

        private fun endField() {
            if (fieldCount < MAX_FIELDS) {
                fields[fieldCount] = field
                subFields[fieldCount] = subField
                fieldCount++
            }
            field = NO_VALUE
            subField = false
        }

        /** Returns the parameter at [index], without the sub-parameters, or [default] when it is empty. */
        private fun parameter(index: Int, default: Int): Int {
            var count = 0
            for (position in 0 until fieldCount) {
                if (subFields[position]) continue
                if (count == index) return fields[position].takeIf { value -> value != NO_VALUE } ?: default
                count++
            }
            return default
        }

        private fun dispatchCsi(final: Int) {
            if (hasIntermediate) {
                ignore(clearsWrap = true)
                return
            }
            when (marker) {
                0 -> when (final) {
                    CUP -> cursorPosition()
                    EL -> eraseInLine(parameter(0, 0))
                    ED -> eraseInDisplay(parameter(0, 0))
                    ECH -> eraseCharacters(parameter(0, 1))
                    DECSTBM -> setScrollRegion()
                    SGR -> selectGraphicRendition()
                    else -> ignore(clearsWrap = true)
                }
                QUESTION -> when (final) {
                    SET_MODE -> setModes(true)
                    RESET_MODE -> setModes(false)
                    else -> ignore(clearsWrap = true)
                }
                else -> ignore(clearsWrap = true)
            }
        }

        private fun cursorPosition() {
            editor.wrapPending = false
            editor.cursorRow = (parameter(0, 1).coerceAtLeast(1) - 1).coerceAtMost(editor.rows - 1)
            editor.cursorColumn = (parameter(1, 1).coerceAtLeast(1) - 1).coerceAtMost(editor.columns - 1)
        }

        private fun eraseInLine(mode: Int) {
            editor.wrapPending = false
            val row = editor.cursorRow
            val style = editor.pen.erased()
            when (mode) {
                0 -> editor.erase(row, editor.cursorColumn, editor.columns, style)
                1 -> editor.erase(row, 0, editor.cursorColumn + 1, style)
                2 -> editor.erase(row, 0, editor.columns, style)
                else -> ignoredCount++
            }
        }

        private fun eraseInDisplay(mode: Int) {
            editor.wrapPending = false
            val row = editor.cursorRow
            val style = editor.pen.erased()
            when (mode) {
                0 -> {
                    editor.erase(row, editor.cursorColumn, editor.columns, style)
                    for (below in row + 1 until editor.rows) editor.erase(below, 0, editor.columns, style)
                }
                1 -> {
                    for (above in 0 until row) editor.erase(above, 0, editor.columns, style)
                    editor.erase(row, 0, editor.cursorColumn + 1, style)
                }
                2 -> for (each in 0 until editor.rows) editor.erase(each, 0, editor.columns, style)
                else -> ignoredCount++
            }
        }

        private fun eraseCharacters(count: Int) {
            editor.wrapPending = false
            val start = editor.cursorColumn
            val end = (start.toLong() + count.coerceAtLeast(1)).coerceAtMost(editor.columns.toLong()).toInt()
            editor.erase(editor.cursorRow, start, end, editor.pen.erased())
        }

        /** DECSTBM. A valid region moves the cursor to the top left. A region with top >= bottom has no effect. */
        private fun setScrollRegion() {
            editor.wrapPending = false
            val top = parameter(0, 1).coerceAtLeast(1)
            val bottom = parameter(1, editor.rows).let { value -> if (value == 0) editor.rows else value }
                .coerceAtMost(editor.rows)
            if (top >= bottom) return
            editor.scrollTop = top - 1
            editor.scrollBottom = bottom - 1
            editor.cursorRow = 0
            editor.cursorColumn = 0
        }

        private fun setModes(set: Boolean) {
            for (index in 0 until fieldCount) {
                if (subFields[index]) continue
                val modes = editor.modes.with(fields[index], set)
                if (modes == null) ignoredCount++ else editor.modes = modes
            }
        }

        private fun selectGraphicRendition() {
            var index = 0
            while (index < fieldCount) {
                var end = index + 1
                while (end < fieldCount && subFields[end]) end++
                index = if (end > index + 1) {
                    sgrWithSubParameters(index, end)
                    end
                } else {
                    sgr(index)
                }
            }
        }

        /** Applies the SGR value at [index]. Returns the index of the next value. */
        private fun sgr(index: Int): Int {
            val pen = editor.pen
            val value = fields[index]
            editor.pen = when (value) {
                NO_VALUE, 0 -> CellStyle.DEFAULT
                1 -> pen.copy(bold = true)
                2 -> pen.copy(faint = true)
                3 -> pen.copy(italic = true)
                4 -> pen.copy(underline = true)
                5 -> pen.copy(blink = true)
                7 -> pen.copy(inverse = true)
                8 -> pen.copy(invisible = true)
                9 -> pen.copy(strikethrough = true)
                21 -> pen.copy(doubleUnderline = true)
                22 -> pen.copy(bold = false, faint = false)
                23 -> pen.copy(italic = false)
                24 -> pen.copy(underline = false, doubleUnderline = false)
                25 -> pen.copy(blink = false)
                27 -> pen.copy(inverse = false)
                28 -> pen.copy(invisible = false)
                29 -> pen.copy(strikethrough = false)
                in 30..37 -> pen.copy(foreground = CellColor.Indexed.of(value - 30))
                39 -> pen.copy(foreground = CellColor.Default)
                in 40..47 -> pen.copy(background = CellColor.Indexed.of(value - 40))
                49 -> pen.copy(background = CellColor.Default)
                in 90..97 -> pen.copy(foreground = CellColor.Indexed.of(value - 90 + BRIGHT))
                in 100..107 -> pen.copy(background = CellColor.Indexed.of(value - 100 + BRIGHT))
                SGR_FOREGROUND, SGR_BACKGROUND -> return extendedColor(index, value == SGR_FOREGROUND)
                else -> pen
            }
            return index + 1
        }

        /** Applies `38;5;n`, `38;2;r;g;b`, and the same forms of 48. Returns the index of the next value. */
        private fun extendedColor(index: Int, foreground: Boolean): Int {
            if (index + 1 >= fieldCount) return fieldCount
            val indexed = fields[index + 1] == COLOR_INDEXED
            val size = when (fields[index + 1]) {
                COLOR_INDEXED -> 3
                COLOR_RGB -> 5
                else -> return fieldCount
            }
            if (index + size > fieldCount) return fieldCount
            val color = if (indexed) {
                indexedColor(fields[index + 2])
            } else {
                rgbColor(fields[index + 2], fields[index + 3], fields[index + 4])
            }
            if (color != null) setColor(color, foreground)
            return index + size
        }

        /** Applies the colon forms `38:5:n`, `38:2:r:g:b`, and `38:2::r:g:b`, and the same forms of 48. */
        private fun sgrWithSubParameters(index: Int, end: Int) {
            val value = fields[index]
            if (value != SGR_FOREGROUND && value != SGR_BACKGROUND) return
            val count = end - index - 1
            val color = when {
                fields[index + 1] == COLOR_INDEXED && count >= 2 -> indexedColor(fields[index + 2])
                fields[index + 1] == COLOR_RGB && count >= 5 ->
                    rgbColor(fields[index + 3], fields[index + 4], fields[index + 5])
                fields[index + 1] == COLOR_RGB && count == 4 ->
                    rgbColor(fields[index + 2], fields[index + 3], fields[index + 4])
                else -> null
            }
            if (color != null) setColor(color, value == SGR_FOREGROUND)
        }

        private fun indexedColor(value: Int): CellColor? =
            if (value in 0 until CellColor.Indexed.PALETTE_SIZE) CellColor.Indexed.of(value) else null

        private fun rgbColor(red: Int, green: Int, blue: Int): CellColor? =
            if (red in COLOR_RANGE && green in COLOR_RANGE && blue in COLOR_RANGE) {
                CellColor.Rgb(red, green, blue)
            } else {
                null
            }

        private fun setColor(color: CellColor, foreground: Boolean) {
            editor.pen = if (foreground) editor.pen.copy(foreground = color) else editor.pen.copy(background = color)
        }

        private fun oscByte(byte: Int) {
            when (byte) {
                BEL -> {
                    state = State.Ground
                    dispatchOsc()
                }
                ESC -> state = State.OscEscape
                CAN, SUB -> {
                    ignoredCount++
                    state = State.Ground
                }
                else -> if (byte >= SPACE) appendOsc(byte)
            }
        }

        private fun oscEscape(byte: Int) {
            if (byte == ST_FINAL) {
                state = State.Ground
                dispatchOsc()
            } else {
                // A new escape sequence ends the string with no effect.
                ignoredCount++
                state = State.Escape
                escape(byte)
            }
        }

        private fun appendOsc(byte: Int) {
            if (oscSize == MAX_OSC_SIZE) {
                oscOverflow = true
                return
            }
            if (oscSize == osc.size) osc = osc.copyOf(minOf(osc.size * 2, MAX_OSC_SIZE))
            osc[oscSize++] = byte.toByte()
        }

        private fun dispatchOsc() {
            val separator = (0 until oscSize).firstOrNull { index -> osc[index].toInt() == SEMICOLON }
            val number = separator?.let { end -> decimal(0, end) }
            if (oscOverflow || separator == null || number == null) {
                ignoredCount++
                return
            }
            val value = osc.copyOfRange(separator + 1, oscSize)
            when (number) {
                OSC_TITLE_AND_ICON -> {
                    editor.title = value.decodeToString()
                    editor.iconName = editor.title
                }
                OSC_ICON -> editor.iconName = value.decodeToString()
                OSC_TITLE -> editor.title = value.decodeToString()
                OSC_CLIPBOARD -> clipboardWrite(value)
                else -> ignoredCount++
            }
        }

        /** Returns the decimal number of the OSC bytes from [start] to [end], or null when they are not digits. */
        private fun decimal(start: Int, end: Int): Int? {
            if (end <= start || end - start > MAX_OSC_NUMBER_DIGITS) return null
            var value = 0
            for (index in start until end) {
                val digit = osc[index].toInt()
                if (digit !in DIGITS) return null
                value = value * DECIMAL + (digit - '0'.code)
            }
            return value
        }

        /** OSC 52: `52;<selection>;<base64 text>`. `mosh-server` 1.4.0 sends only the selection `c`. */
        private fun clipboardWrite(value: ByteArray) {
            val separator = value.indexOf(SEMICOLON.toByte())
            val data = if (separator < 0) null else value.copyOfRange(separator + 1, value.size).decodeToString()
            // A "?" asks for the clipboard. The client never answers.
            if (!acceptClipboard || data == null || data == "?") {
                ignoredCount++
                return
            }
            val text = try {
                BASE64.decode(data).decodeToString()
            } catch (_: IllegalArgumentException) {
                ignoredCount++
                return
            }
            editor.clipboard = text
        }

        private fun controlString(byte: Int) {
            when (byte) {
                ESC -> state = State.ControlStringEscape
                CAN, SUB -> state = State.Ground
            }
        }

        private fun controlStringEscape(byte: Int) {
            if (byte == ST_FINAL) {
                state = State.Ground
            } else {
                state = State.Escape
                escape(byte)
            }
        }

        private fun ignore(clearsWrap: Boolean) {
            ignoredCount++
            if (clearsWrap) editor.wrapPending = false
        }

        /** Moves the cursor down one row. At the bottom of the scroll region, the region moves up one row. */
        private fun index() {
            when {
                editor.cursorRow == editor.scrollBottom ->
                    editor.scrollUp(editor.scrollTop, editor.scrollBottom, editor.pen.erased())
                editor.cursorRow < editor.rows - 1 -> editor.cursorRow++
            }
        }

        /** Moves the cursor to the start of the next row, and marks the row that it leaves as wrapped. */
        private fun wrap() {
            editor.setWrapped(editor.cursorRow, true)
            editor.cursorColumn = 0
            editor.wrapPending = false
            index()
        }

        private fun print(codePoint: Int) {
            val width = CellWidth.of(codePoint)
            if (width == 0) {
                addZeroWidth(codePoint)
                return
            }
            if (width == 2 && editor.columns < 2) {
                ignore(clearsWrap = false)
                return
            }
            if (editor.wrapPending) wrap()
            if (width == 2 && editor.cursorColumn == editor.columns - 1) {
                editor.setCell(editor.cursorRow, editor.cursorColumn, ScreenCell.empty(editor.pen.erased()))
                wrap()
            }
            val row = editor.cursorRow
            val column = editor.cursorColumn
            editor.setCell(row, column, cellOf(codePoint, width == 2))
            if (width == 2) editor.setCell(row, column + 1, ScreenCell.empty(editor.pen.erased()))
            advance(width)
        }

        private fun advance(width: Int) {
            val next = editor.cursorColumn + width
            if (next >= editor.columns) {
                editor.cursorColumn = editor.columns - 1
                editor.wrapPending = true
            } else {
                editor.cursorColumn = next
            }
        }

        private fun addZeroWidth(codePoint: Int) {
            val row = editor.cursorRow
            val pending = editor.wrapPending
            var target = if (pending) editor.cursorColumn else (editor.cursorColumn - 1).coerceAtLeast(0)
            if (editor.isCovered(row, target)) target--
            val cell = editor.cell(row, target)
            if (!cell.isEmpty && (pending || !editor.isCovered(row, editor.cursorColumn))) {
                val text = cell.text + textOf(codePoint)
                if (text.length <= MAX_CELL_TEXT) editor.setCell(row, target, cell.copy(text = text))
                return
            }
            if (pending) wrap()
            val cursorRow = editor.cursorRow
            val column = editor.cursorColumn
            val style = editor.cell(cursorRow, column).style
            editor.setCell(cursorRow, column, ScreenCell(NO_BREAK_SPACE + textOf(codePoint), false, style))
            advance(1)
        }

        private fun cellOf(codePoint: Int, wide: Boolean): ScreenCell {
            val pen = editor.pen
            if (codePoint >= ASCII_SIZE) return ScreenCell(textOf(codePoint), wide, pen)
            if (asciiStyle != pen) {
                asciiCells.fill(null)
                asciiStyle = pen
            }
            return asciiCells[codePoint] ?: ScreenCell(ASCII_TEXT[codePoint], false, pen).also { cell ->
                asciiCells[codePoint] = cell
            }
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val ASCII_SIZE = 0x80
        const val SPACE = 0x20
        const val DEL = 0x7F
        const val BEL = 0x07
        const val BS = 0x08
        const val HT = 0x09
        const val LF = 0x0A
        const val CR = 0x0D
        const val CAN = 0x18
        const val SUB = 0x1A
        const val ESC = 0x1B

        const val CSI_START = '['.code
        const val OSC_START = ']'.code
        const val DCS_START = 'P'.code
        const val SOS_START = 'X'.code
        const val PM_START = '^'.code
        const val APC_START = '_'.code
        const val ST_FINAL = '\\'.code

        const val SEMICOLON = ';'.code
        const val COLON = ':'.code
        const val QUESTION = '?'.code
        val DIGITS = '0'.code..'9'.code
        val PRIVATE_MARKERS = '<'.code..'?'.code
        val INTERMEDIATES = 0x20..0x2F
        val FINALS = 0x40..0x7E

        const val CUP = 'H'.code
        const val EL = 'K'.code
        const val ED = 'J'.code
        const val ECH = 'X'.code
        const val DECSTBM = 'r'.code
        const val SGR = 'm'.code
        const val SET_MODE = 'h'.code
        const val RESET_MODE = 'l'.code

        const val SGR_FOREGROUND = 38
        const val SGR_BACKGROUND = 48
        const val COLOR_INDEXED = 5
        const val COLOR_RGB = 2
        const val BRIGHT = 8
        val COLOR_RANGE = 0..255

        const val OSC_TITLE_AND_ICON = 0
        const val OSC_ICON = 1
        const val OSC_TITLE = 2
        const val OSC_CLIPBOARD = 52
        const val MAX_OSC_NUMBER_DIGITS = 4

        const val DECIMAL = 10

        /** An empty parameter. */
        const val NO_VALUE = -1

        /** The largest parameter value. A larger value becomes this value. */
        const val MAX_VALUE = 65_535

        /** The count of parameters and sub-parameters that a control sequence keeps. */
        const val MAX_FIELDS = 32

        const val OSC_START_SIZE = 64

        /**
         * The largest OSC string: 1 MiB. The terminal ignores a longer string. This value is a choice of this client.
         */
        const val MAX_OSC_SIZE = 1024 * 1024

        /**
         * The largest text of one cell, in UTF-16 units. The terminal drops a zero-width character that makes the
         * text longer. This value is a choice of this client.
         */
        const val MAX_CELL_TEXT = 32

        val UTF8_CONTINUATION = 0x80..0xBF
        val UTF8_TWO = 0xC2..0xDF
        val UTF8_THREE = 0xE0..0xEF
        val UTF8_FOUR = 0xF0..0xF4
        const val UTF8_BITS = 6
        const val UTF8_PAYLOAD = 0x3F
        val SURROGATES = 0xD800..0xDFFF
        val C1_CONTROLS = 0x80..0x9F
        const val MAX_CODE_POINT = 0x10FFFF
        const val REPLACEMENT = 0xFFFD
        const val NO_BREAK_SPACE = " "

        val ASCII_TEXT = Array(ASCII_SIZE) { code -> code.toChar().toString() }
        val BASE64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

        fun textOf(codePoint: Int): String {
            if (codePoint < SUPPLEMENTARY_START) return codePoint.toChar().toString()
            val offset = codePoint - SUPPLEMENTARY_START
            val high = (HIGH_SURROGATE_START + (offset ushr SURROGATE_BITS)).toChar()
            val low = (LOW_SURROGATE_START + (offset and SURROGATE_MASK)).toChar()
            return charArrayOf(high, low).concatToString()
        }

        const val SUPPLEMENTARY_START = 0x10000
        const val HIGH_SURROGATE_START = 0xD800
        const val LOW_SURROGATE_START = 0xDC00
        const val SURROGATE_BITS = 10
        const val SURROGATE_MASK = 0x3FF
    }
}
