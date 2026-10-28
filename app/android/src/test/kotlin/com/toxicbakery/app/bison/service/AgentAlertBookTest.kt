package com.toxicbakery.app.bison.service

import com.toxicbakery.app.bison.protocol.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests [AgentAlertBook]: the old alerts and the sequence of an action. */
class AgentAlertBookTest {

    private val book = AgentAlertBook().apply {
        posted(1, AgentAlertBook.Alert("box", "w1:p1", sequence = 5))
        posted(2, AgentAlertBook.Alert("box", "w1:p2", sequence = 6))
        posted(3, AgentAlertBook.Alert("rig", "w1:p1", sequence = 7))
    }

    @Test
    fun anAlertIsOldWhenItsPaneDoesNotNeedTheUser() {
        val needing = mapOf("box" to mapOf("w1:p1" to AgentStatus.Blocked))
        assertEquals(listOf(2, 3), book.removeOld(needing).sorted())
        assertTrue(book.has(1))
        assertFalse(book.has(2))

        // A disconnect removes all panes of the machine.
        assertEquals(listOf(1), book.removeOld(emptyMap()))
        assertFalse(book.has(1))
    }

    @Test
    fun onlyTheActionOfTheCurrentAlertIsCurrent() {
        assertTrue(book.isCurrent(1, 5))
        // A newer alert of the same pane replaced the alert of the action.
        book.posted(1, AgentAlertBook.Alert("box", "w1:p1", sequence = 8))
        assertFalse(book.isCurrent(1, 5))
        assertTrue(book.isCurrent(1, 8))
        assertFalse(book.isCurrent(1, null))
        assertFalse(book.isCurrent(9, 5))

        book.remove(1)
        assertFalse(book.isCurrent(1, 8))
    }
}
