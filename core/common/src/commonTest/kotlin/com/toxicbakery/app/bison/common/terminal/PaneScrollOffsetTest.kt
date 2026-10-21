package com.toxicbakery.app.bison.common.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class PaneScrollOffsetTest {
    @Test
    fun stepsAddUpDuringOneDrag() {
        // A drag of 5 single-line steps up, then 7 steps down, from the bottom.
        var offset = 0
        repeat(5) { offset = nextPaneScrollOffset(offset, lineDelta = 1, maxOffset = 263) }
        assertEquals(5, offset)
        repeat(7) { offset = nextPaneScrollOffset(offset, lineDelta = -1, maxOffset = 263) }
        assertEquals(0, offset, "The pane must come back to the bottom")
    }

    @Test
    fun offsetStaysInTheHistory() {
        assertEquals(263, nextPaneScrollOffset(260, lineDelta = 10, maxOffset = 263))
        assertEquals(0, nextPaneScrollOffset(2, lineDelta = -10, maxOffset = 263))
        assertEquals(40, nextPaneScrollOffset(30, lineDelta = 10, maxOffset = 0), "No top limit when the history size is not known")
        assertEquals(Int.MAX_VALUE, nextPaneScrollOffset(Int.MAX_VALUE - 1, lineDelta = 5, maxOffset = 0))
    }
}
