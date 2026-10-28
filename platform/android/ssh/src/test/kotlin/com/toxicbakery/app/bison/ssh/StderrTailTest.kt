package com.toxicbakery.app.bison.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests that [StderrTail] keeps only the last bytes of the standard error of a worker. */
class StderrTailTest {

    @Test
    fun anEmptyStreamHasNoText() {
        assertEquals("", StderrTail(limit = 16).text())
    }

    @Test
    fun aShortStreamKeepsAllItsText() {
        val tail = StderrTail(limit = 64)
        tail.write("error: first\nerror: second\n".encodeToByteArray())

        assertEquals("error: first\nerror: second\n", tail.text())
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
