package com.toxicbakery.app.bison.ssh

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests the [ChannelPipe] between the bastion session and the target session of [BastionChannelProxy]. */
class ChannelPipeTest {

    @Test
    fun readGivesTheBytesInOrderAcrossTheEndOfTheBuffer() {
        val pipe = ChannelPipe(capacity = 4)
        val received = ByteArray(9)
        var next = 0

        listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), byteArrayOf(7, 8, 9)).forEach { chunk ->
            pipe.sink.write(chunk)
            while (pipe.source.available() > 0) {
                next += pipe.source.read(received, next, received.size - next)
            }
        }

        assertContentEquals(ByteArray(9) { (it + 1).toByte() }, received)
    }

    @Test
    fun aReadWithNoDataThrowsASocketTimeoutAfterTheReadTimeout() {
        val pipe = ChannelPipe().apply { readTimeoutMs = 50 }
        val start = System.nanoTime()

        assertFailsWith<SocketTimeoutException> { pipe.source.read() }

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 50)
        pipe.sink.write(7)
        assertEquals(7, pipe.source.read(), "A timeout must not close the pipe")
    }

    @Test
    fun aClosedSinkGivesTheBufferedBytesAndThenTheEndOfTheStream() {
        val pipe = ChannelPipe()
        pipe.sink.write(byteArrayOf(1, 2))
        pipe.sink.close()

        assertEquals(1, pipe.source.read())
        assertEquals(2, pipe.source.read())
        assertEquals(-1, pipe.source.read())
        assertFailsWith<IOException> { pipe.sink.write(3) }
    }

    @Test
    fun aFullBufferStopsTheWriterUntilTheReaderGetsTheBytes() {
        val pipe = ChannelPipe(capacity = 2)
        val written = CountDownLatch(1)
        val writer = thread {
            pipe.sink.write(byteArrayOf(1, 2, 3, 4))
            written.countDown()
        }

        assertFalse(written.await(100, TimeUnit.MILLISECONDS), "The writer did not wait for space")
        val received = ByteArray(4)
        var next = 0
        while (next < received.size) next += pipe.source.read(received, next, received.size - next)

        assertTrue(written.await(5, TimeUnit.SECONDS))
        writer.join()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), received)
    }

    @Test
    fun closeStopsAWriterThatWaits() {
        val pipe = ChannelPipe(capacity = 1)
        val writerError = arrayOfNulls<Throwable>(1)
        val writer = thread {
            writerError[0] = runCatching { pipe.sink.write(byteArrayOf(1, 2)) }.exceptionOrNull()
        }

        Thread.sleep(50)
        pipe.close()
        writer.join(5_000)

        assertIs<IOException>(writerError[0])
        assertFailsWith<IOException> { pipe.source.read() }
    }

    @Test
    fun anInterruptStopsAReadThatWaits() {
        val pipe = ChannelPipe()
        val readerError = arrayOfNulls<Throwable>(1)
        val reader = thread {
            readerError[0] = runCatching { pipe.source.read() }.exceptionOrNull()
        }

        Thread.sleep(50)
        reader.interrupt()
        reader.join(5_000)

        assertIs<IOException>(readerError[0])
    }
}
