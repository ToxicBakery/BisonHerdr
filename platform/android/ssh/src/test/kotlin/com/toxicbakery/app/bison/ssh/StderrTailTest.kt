package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests that [StderrTail] keeps only the last bytes of the standard error of a worker. */
class StderrTailTest {

    @Test
    fun anEmptyStreamHasNoLastLine() {
        val tail = StderrTail(limit = 16)

        assertEquals("", tail.text())
        assertNull(tail.lastLine())
    }

    @Test
    fun theLastLineIsTheNewestLineWithText() {
        val tail = StderrTail(limit = 64)
        tail.write("error: first\nerror: second\n\n".encodeToByteArray())

        assertEquals("error: second", tail.lastLine())
    }

    @Test
    fun theStreamKeepsOnlyTheLastBytes() {
        val tail = StderrTail(limit = 8)
        repeat(1_000) { tail.write("line $it\n".encodeToByteArray()) }
        tail.write('!'.code)

        assertEquals("line 999\n!".takeLast(8), tail.text())
        assertTrue(tail.text().encodeToByteArray().size <= 8)
    }
}
