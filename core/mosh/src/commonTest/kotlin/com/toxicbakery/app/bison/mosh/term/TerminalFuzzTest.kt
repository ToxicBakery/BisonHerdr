package com.toxicbakery.app.bison.mosh.term

import com.toxicbakery.app.bison.mosh.wire.HostEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Random input never makes the terminal throw, and each screen keeps its invariants. */
class TerminalFuzzTest {

    private val random = Random(SEED)

    @Test
    fun randomBytesNeverMakeTheTerminalThrow() {
        val terminal = MoshTerminal(acceptClipboard = true)
        var screen = screen(columns = 13, rows = 7)
        repeat(ROUNDS) {
            screen = terminal.apply(screen, random.nextBytes(random.nextInt(0, 300)))
            screen.assertValid()
        }
    }

    @Test
    fun randomControlsFromTheCensusNeverMakeTheTerminalThrow() {
        val terminal = MoshTerminal(acceptClipboard = true)
        var screen = screen(columns = 11, rows = 6)
        repeat(ROUNDS) {
            val events = List(random.nextInt(1, 4)) { randomEvent() }
            screen = terminal.apply(screen, events)
            screen.assertValid()
        }
    }

    @Test
    fun changedCensusStreamsNeverMakeTheTerminalThrow() {
        val terminal = MoshTerminal()
        val valid = (List(40) { randomPiece() }.joinToString("")).encodeToByteArray()
        repeat(ROUNDS) {
            val bytes = valid.copyOf()
            repeat(random.nextInt(1, 6)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            val cut = bytes.copyOf(random.nextInt(bytes.size + 1))
            terminal.apply(screen(columns = 9, rows = 5), cut).assertValid()
        }
    }

    private fun randomEvent(): HostEvent = when (random.nextInt(10)) {
        0 -> HostEvent.Resize(random.nextInt(0, 30), random.nextInt(0, 12))
        1 -> HostEvent.EchoAck(random.nextLong(0, 100).toULong())
        else -> HostEvent.HostBytes(List(random.nextInt(1, 30)) { randomPiece() }.joinToString("").encodeToByteArray())
    }

    private fun randomPiece(): String = when (random.nextInt(16)) {
        0 -> "\r"
        1 -> "\n"
        2 -> "\b"
        3 -> "\u0007"
        4 -> "${CSI}${random.nextInt(0, 15)};${random.nextInt(0, 15)}H"
        5 -> "${CSI}${random.nextInt(0, 3)}K"
        6 -> "${CSI}${random.nextInt(0, 3)}J"
        7 -> "${CSI}${random.nextInt(0, 15)}X"
        8 -> "${CSI}${random.nextInt(0, 10)};${random.nextInt(0, 10)}r"
        9 -> "${CSI}0;${SGR_VALUES.random(random)}m"
        10 -> "${CSI}?${MODES.random(random)}${if (random.nextBoolean()) "h" else "l"}"
        11 -> "$E]${listOf(0, 1, 2, 52).random(random)};${if (random.nextBoolean()) "c;aGk=" else "title"}\u0007"
        12 -> TEXT.random(random).toString()
        13 -> "一Ａ😀".substring(0, random.nextInt(1, 3))
        14 -> listOf("́", "​", " ́", "ᅠ", "️").random(random)
        else -> "abcdefghij".take(random.nextInt(1, 10))
    }

    private fun MoshScreen.assertValid() {
        assertTrue(columns in 1..MoshScreen.MAX_SIZE && rows in 1..MoshScreen.MAX_SIZE, "size ${columns}x$rows")
        assertEquals(rows, lines.size)
        assertTrue(cursorRow in 0 until rows && cursorColumn in 0 until columns, "cursor $cursorRow,$cursorColumn")
        assertTrue(scrollTop in 0 until scrollBottom + 1 && scrollBottom < rows, "region $scrollTop..$scrollBottom")
        assertTrue(!wrapPending || cursorColumn == columns - 1, "wrap flag in column $cursorColumn")
        for (row in lines) {
            assertEquals(columns, row.size)
            for (column in 0 until row.size) assertTrue(row[column].text.length <= 32)
        }
    }

    private companion object {
        const val SEED = 1993
        const val ROUNDS = 2000
        const val TEXT = "abc xyz 0123 .,;"
        val SGR_VALUES = listOf("1", "2", "3", "4", "5", "7", "8", "9", "21", "31", "42", "38;5;200", "48;2;1;2;3", "")
        val MODES = listOf(25, 5, 1000, 1001, 1002, 1003, 1004, 1005, 1006, 1015, 2004, 1049)
    }
}
