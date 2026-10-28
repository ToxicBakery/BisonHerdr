package com.toxicbakery.app.bison.mosh.term

import com.toxicbakery.app.bison.mosh.wire.HostEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The screen state is immutable, and a new state shares each row that its diff did not change. */
class CopyOnWriteTest {

    private val base = screen(columns = 8, rows = 6).after((1..6).joinToString("\r\n") { row -> "row $row" })

    @Test
    fun applyDoesNotChangeTheOldScreen() {
        val rows = base.lines.toList()
        val text = base.text()
        val cursor = base.cursor

        base.after("${CSI}2;1Hchanged${CSI}2J${CSI}?25l$E]0;title\u0007")

        assertEquals(text, base.text())
        assertEquals(rows, base.lines)
        assertEquals(cursor, base.cursor)
        assertEquals("", base.title)
        assertTrue(base.modes.cursorVisible)
    }

    @Test
    fun aNewScreenSharesEachRowThatTheDiffDidNotChange() {
        val next = base.after("${CSI}3;1HROW")

        assertEquals("ROW 3   ", next.rowText(2))
        for (row in 0 until 6) {
            if (row == 2) assertNotSame(base.row(row), next.row(row)) else assertSame(base.row(row), next.row(row))
        }
    }

    @Test
    fun aRowThatChangesTwoTimesInOneDiffIsCopiedOneTime() {
        val next = base.after("${CSI}3;1HR${CSI}3;3HW")

        assertEquals("RoW 3   ", next.rowText(2))
        assertEquals("row 3   ", base.rowText(2))
    }

    @Test
    fun aScrollMovesTheRowsWithNoCopyOfTheirCells() {
        val next = base.after("${CSI}2;5r${CSI}5;1H\n${CSI}r")

        assertSame(base.row(0), next.row(0))
        assertSame(base.row(2), next.row(1))
        assertSame(base.row(3), next.row(2))
        assertSame(base.row(4), next.row(3))
        assertSame(base.row(5), next.row(5))
        assertEquals("        ", next.rowText(4))
    }

    @Test
    fun aChangeOfTheWrapMarkSharesTheCells() {
        // The first diff fills the row. The next diff wraps, and only the wrap mark of the row changes.
        val full = screen(columns = 4, rows = 2).after("abcd")
        val next = full.after("e")

        assertFalse(full.row(0).wrapped)
        assertTrue(next.row(0).wrapped)
        assertTrue(next.row(0).sharesCellsWith(full.row(0)))
    }

    @Test
    fun aFullEraseSharesOneBlankRow() {
        val next = base.after("${CSI}2J")

        for (row in 1 until 6) assertTrue(next.row(0).sharesCellsWith(next.row(row)))
    }

    @Test
    fun aDiffFromAnOldStateGivesAScreenThatDoesNotDependOnTheNewerStates() {
        val terminal = MoshTerminal()
        val first = base.after("${CSI}1;1HA", terminal)
        val second = first.after("${CSI}2;1HB", terminal)
        // The server can diff from a state that the client did not acknowledge (wire fact 17).
        val fromOld = first.after("${CSI}3;1HC", terminal)

        assertEquals("Bow 2   ", second.rowText(1))
        assertEquals("row 2   ", fromOld.rowText(1))
        assertEquals("Cow 3   ", fromOld.rowText(2))
        assertEquals("Aow 1   ", fromOld.rowText(0))
    }

    @Test
    fun eachDiffStartsInTheGroundStateOfTheParser() {
        val terminal = MoshTerminal()
        // The first diff ends in the middle of a CSI and of a UTF-8 sequence. The next diff must not continue them.
        val partial = terminal.apply(base, "${CSI}2".encodeToByteArray())
        val next = partial.after("x", terminal)

        assertEquals(base.cursor, partial.cursor)
        assertEquals('x', next.cell(5, 5).text.single())
        val utf8 = terminal.apply(base, byteArrayOf(0xE4.toByte(), 0xB8.toByte()))
        assertEquals(base, utf8)
        assertEquals(2L, terminal.ignoredCount)
    }

    @Test
    fun anEchoAckOrAnEmptyDiffReturnsTheSameScreen() {
        val terminal = MoshTerminal()

        assertSame(base, terminal.apply(base, listOf(HostEvent.EchoAck(7uL))))
        assertSame(base, terminal.apply(base, listOf(HostEvent.HostBytes(ByteArray(0)))))
        assertSame(base, terminal.apply(base, emptyList()))
    }

    @Test
    fun aResizeKeepsTheTopLeftCellsAndResetsTheRegion() {
        val resized = MoshTerminal().apply(
            base.after("${CSI}2;4r${CSI}6;8H"),
            listOf(HostEvent.Resize(4, 3)),
        )

        assertEquals(4, resized.columns)
        assertEquals(3, resized.rows)
        assertEquals(listOf("row ", "row ", "row "), (0 until 3).map(resized::rowText))
        assertEquals(2 to 3, resized.cursor)
        assertEquals(0, resized.scrollTop)
        assertEquals(2, resized.scrollBottom)
    }

    @Test
    fun aResizeToALargerSizeAddsEmptyCells() {
        val resized = MoshTerminal().apply(base, listOf(HostEvent.Resize(10, 7)))

        assertEquals("row 1     ", resized.rowText(0))
        assertEquals("          ", resized.rowText(6))
        assertEquals(base.cursor, resized.cursor)
    }

    @Test
    fun aResizeAndThePaintAfterItApplyInOrder() {
        // The first reply of mosh-server 1.4.0 for a client of 40x6: a size entry, and then the full paint.
        val paint = "${CSI}r${CSI}0m${CSI}H${CSI}2J${CSI}?25l${CSI}K\n${CSI}K\n${CSI}K\n${CSI}K\n${CSI}K\n${CSI}K" +
            "${CSI}1;1H${CSI}?25h${CSI}0m${CSI}?2004l${CSI}?1003l${CSI}?1002l${CSI}?1001l${CSI}?1000l${CSI}?1004l" +
            "${CSI}?1015l${CSI}?1006l${CSI}?1005l"
        val screen = MoshTerminal().apply(
            MoshScreen.INITIAL,
            listOf(HostEvent.Resize(40, 6), HostEvent.HostBytes(paint.encodeToByteArray())),
        )

        assertEquals(MoshScreen.blank(40, 6), screen)
    }

    @Test
    fun aSizeOutsideTheLimitsBecomesTheNearestLimit() {
        val terminal = MoshTerminal()

        val small = terminal.apply(base, listOf(HostEvent.Resize(0, 0)))
        val large = terminal.apply(base, listOf(HostEvent.Resize(5000, 2)))

        assertEquals(1 to 1, small.columns to small.rows)
        assertEquals(MoshScreen.MAX_SIZE, large.columns)
    }

    @Test
    fun theInitialScreenIsTheScreenOfANewServer() {
        assertEquals(80, MoshScreen.INITIAL.columns)
        assertEquals(24, MoshScreen.INITIAL.rows)
        assertEquals(0 to 0, MoshScreen.INITIAL.cursor)
        assertEquals(MoshScreen.blank(80, 24), MoshScreen.INITIAL)
    }
}
