package com.toxicbakery.app.bison.mosh.wire

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The zlib stream of an instruction and its size limit (wire fact 5). */
class ZlibTest {

    private val zlib = Zlib()

    @Test
    fun aStreamOfADifferentZlibCodeExpands() {
        // Python zlib.compress(b"hello").
        val stream = "789CCB48CDC9C90700062C0215".hexToByteArray()
        assertContentEquals("hello".encodeToByteArray(), zlib.decompress(stream, 5))
    }

    @Test
    fun dataExpandsToTheSameBytes() {
        val random = Random(SEED)
        for (data in listOf(ByteArray(0), "hello".encodeToByteArray(), random.nextBytes(100_000), ByteArray(70_000))) {
            assertContentEquals(data, zlib.decompress(zlib.compress(data), data.size))
        }
    }

    @Test
    fun aStreamThatExpandsToMoreThanTheLimitDoesNotExpand() {
        val stream = zlib.compress(ByteArray(LIMIT_TEST_SIZE))
        assertContentEquals(ByteArray(LIMIT_TEST_SIZE), zlib.decompress(stream, LIMIT_TEST_SIZE))
        assertNull(zlib.decompress(stream, LIMIT_TEST_SIZE - 1))
    }

    @Test
    fun aCutStreamBadBytesOrBytesAfterTheEndDoNotExpand() {
        val stream = zlib.compress("hello hello hello".encodeToByteArray())
        for (size in 0 until stream.size) assertNull(zlib.decompress(stream.copyOf(size), 100), "size $size")
        assertNull(zlib.decompress(stream + byteArrayOf(0), 100))
        assertNull(zlib.decompress("00112233445566778899".hexToByteArray(), 100))
    }

    @Test
    fun aNegativeLimitIsAnError() {
        assertFailsWith<IllegalArgumentException> { zlib.decompress(zlib.compress(ByteArray(1)), -1) }
    }

    private companion object {
        const val SEED = 7
        const val LIMIT_TEST_SIZE = 1024 * 1024
    }
}
