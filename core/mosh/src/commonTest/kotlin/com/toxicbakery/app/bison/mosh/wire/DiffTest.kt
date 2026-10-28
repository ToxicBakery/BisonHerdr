package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The client diff and the server diff (wire facts 7 and 8). */
class DiffTest {

    @Test
    fun aResizeIsAnEntryWithFieldThreeThatHoldsTheColumnsAndTheRows() {
        // Entry 1 { 3 { 5: 60, 6: 12 } }
        assertContentEquals("0A061A04283C300C".hexToByteArray(), UserDiff(listOf(UserEvent.Resize(60, 12))).encode())
    }

    @Test
    fun keysAreAnEntryWithFieldTwoThatHoldsTheBytesInFieldFour() {
        // Entry 1 { 2 { 4: "\n" } }
        assertContentEquals("0A0512032201 0A".replace(" ", "").hexToByteArray(), userKeys("\n").encode())
    }

    @Test
    fun aUserDiffDecodesToTheSameEventsInTheSameOrder() {
        val diff = UserDiff(
            listOf(
                UserEvent.Resize(80, 24),
                UserEvent.Keys("ls\r".encodeToByteArray()),
                UserEvent.Keys(ByteArray(0)),
                UserEvent.Resize(0, 0),
            ),
        )
        assertEquals(diff, UserDiff.decode(diff.encode()))
        assertEquals(UserDiff(emptyList()), UserDiff.decode(ByteArray(0)))
    }

    @Test
    fun anEchoAckIsAnEntryWithFieldSevenThatHoldsTheStateInFieldEight() {
        // Entry 1 { 7 { 8: 1 } }
        assertContentEquals("0A043A024001".hexToByteArray(), HostDiff(listOf(HostEvent.EchoAck(1uL))).encode())
    }

    @Test
    fun aHostDiffDecodesToTheSameEventsInTheSameOrder() {
        val diff = HostDiff(
            listOf(
                HostEvent.Resize(300, 150),
                HostEvent.HostBytes("\u001b[H\u001b[2Jhello".encodeToByteArray()),
                HostEvent.EchoAck(ULong.MAX_VALUE),
            ),
        )
        assertEquals(diff, HostDiff.decode(diff.encode()))
    }

    @Test
    fun theDecodersSkipUnknownFieldsAndUnknownEntries() {
        // An unknown top-level varint, an entry with an unknown event 9, and a resize entry with an unknown field 7.
        val bytes = "1001 0A034A0161 0A081A06283C300C3801".replace(" ", "").hexToByteArray()
        assertEquals(UserDiff(listOf(UserEvent.Resize(60, 12))), UserDiff.decode(bytes))
        assertEquals(HostDiff(listOf(HostEvent.Resize(60, 12))), HostDiff.decode(bytes))
    }

    @Test
    fun aSizeOutsideTheIntRangeOrBadBytesDoNotDecode() {
        // Entry 1 { 3 { 5: 2^31 } }
        val tooLarge = "0A081A062880808080 08".replace(" ", "").hexToByteArray()
        assertNull(UserDiff.decode(tooLarge))
        assertNull(HostDiff.decode(tooLarge))
        assertNull(UserDiff.decode("0A05".hexToByteArray()))
        assertNull(HostDiff.decode("0A021A".hexToByteArray()))
    }

    private fun userKeys(text: String) = UserDiff(listOf(UserEvent.Keys(text.encodeToByteArray())))
}
