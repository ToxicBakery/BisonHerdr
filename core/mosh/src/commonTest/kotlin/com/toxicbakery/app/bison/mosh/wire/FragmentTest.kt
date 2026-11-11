package com.toxicbakery.app.bison.mosh.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fragment layout and the split of a stream (wire fact 5). */
class FragmentTest {

    @Test
    fun aFragmentEncodesTheIdTheFinalFlagAndTheNumberBigEndian() {
        val fragment = Fragment(0x0102030405060708uL, number = 3, isFinal = true, content = byteArrayOf(9))
        assertContentEquals("0102030405060708800309".hexToByteArray(), fragment.encode())
        val notFinal = Fragment(ULong.MAX_VALUE, number = Fragment.MAX_NUMBER, isFinal = false, content = ByteArray(0))
        assertContentEquals("FFFFFFFFFFFFFFFF7FFF".hexToByteArray(), notFinal.encode())
    }

    @Test
    fun aFragmentDecodesToTheSameValues() {
        val fragment = Fragment(ULong.MAX_VALUE, number = 1234, isFinal = true, content = "abc".encodeToByteArray())
        assertEquals(fragment, Fragment.decode(fragment.encode()))
        val padded = byteArrayOf(1, 2) + fragment.encode() + byteArrayOf(3)
        assertEquals(fragment, Fragment.decode(padded, offset = 2, length = fragment.encodedSize))
    }

    @Test
    fun aFragmentWithNoFullHeaderDoesNotDecode() {
        for (size in 0 until Fragment.HEADER_SIZE) assertNull(Fragment.decode(ByteArray(size)))
        val empty = assertNotNull(Fragment.decode(ByteArray(Fragment.HEADER_SIZE)))
        assertEquals(0, empty.content.size)
    }

    @Test
    fun aRangeOutsideTheBytesDoesNotDecode() {
        val bytes = Fragment(1uL, 0, isFinal = true, content = byteArrayOf(1, 2)).encode()
        assertNull(Fragment.decode(bytes, offset = -1))
        assertNull(Fragment.decode(bytes, offset = -1, length = bytes.size))
        assertNull(Fragment.decode(bytes, offset = 1, length = bytes.size))
        assertNull(Fragment.decode(bytes, offset = bytes.size + 1))
        assertNull(Fragment.decode(bytes, offset = 0, length = bytes.size + 1))
    }

    @Test
    fun aNumberOutsideFifteenBitsIsAnError() {
        assertFailsWith<IllegalArgumentException> { Fragment(1uL, -1, isFinal = false, content = ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Fragment(1uL, 0x8000, isFinal = false, content = ByteArray(0)) }
    }

    @Test
    fun anEmptyStreamSplitsIntoOneEmptyFinalFragment() {
        val fragments = Fragment.split(4uL, ByteArray(0))
        assertEquals(listOf(Fragment(4uL, 0, isFinal = true, content = ByteArray(0))), fragments)
    }

    @Test
    fun aStreamSplitsIntoNumberedPartsWithTheFinalFlagOnTheLastPart() {
        for (size in listOf(1, 9, 10, 11, 30, 31)) {
            val stream = ByteArray(size) { index -> index.toByte() }
            val fragments = Fragment.split(8uL, stream, maxContentSize = 10)
            assertEquals((size + 9) / 10, fragments.size, "size $size")
            assertEquals(fragments.indices.toList(), fragments.map { fragment -> fragment.number })
            assertEquals(List(fragments.size) { index -> index == fragments.lastIndex }, fragments.map { it.isFinal })
            assertTrue(fragments.all { fragment -> fragment.instructionId == 8uL && fragment.content.size <= 10 })
            assertContentEquals(stream, fragments.fold(ByteArray(0)) { joined, fragment -> joined + fragment.content })
        }
    }

    @Test
    fun aStreamThatNeedsMoreThanTheFragmentLimitIsAnError() {
        Fragment.split(1uL, ByteArray(FragmentAssembler.MAX_FRAGMENTS), maxContentSize = 1)
        assertFailsWith<IllegalArgumentException> {
            Fragment.split(1uL, ByteArray(FragmentAssembler.MAX_FRAGMENTS + 1), maxContentSize = 1)
        }
    }
}
