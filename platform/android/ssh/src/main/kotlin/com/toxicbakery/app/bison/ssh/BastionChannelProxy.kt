package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.toxicbakery.app.bison.model.KnownHost
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A JSch [Proxy] that carries a target session over a `direct-tcpip` channel of the [bastion] session (`ProxyJump`).
 *
 * The channel is a part of the bastion session, so the app opens no local port. Do not use a local port forward
 * (`setPortForwardingL`) for a bastion. It opens a listener on `127.0.0.1`, and each app on the device can use that
 * listener to get to the target network through the bastion.
 *
 * The target session reads the data of the channel from a [ChannelPipe]. A read waits for a maximum of
 * [readTimeoutMs], the same as a read from a socket with `SO_TIMEOUT`. [connect] sets [readTimeoutMs] to the connect
 * timeout of the target session. After the login, the client sets it to the timeout of the target session. Thus JSch
 * sends keepalive messages to the target and stops a target that does not reply, the same as for a direct connection.
 *
 * [bastionAddress] is the `user@host:port` text of the bastion. The errors of [connect] include it.
 */
internal class BastionChannelProxy(
    private val bastion: Session,
    private val bastionAddress: String,
) : Proxy {
    private val pipe = ChannelPipe()

    @Volatile
    private var channel: ChannelDirectTCPIP? = null

    @Volatile
    private var output: OutputStream? = null

    /** The maximum time in milliseconds that a read waits for data. 0 sets no limit. */
    var readTimeoutMs: Int
        get() = pipe.readTimeoutMs
        set(value) {
            pipe.readTimeoutMs = value
        }

    /**
     * Opens a `direct-tcpip` channel from the bastion to [host] and [port]. JSch gives the `host:port` of the
     * target session. The bastion makes the TCP connection to the target.
     *
     * See [openFailure] for the errors. Each error names the bastion and the target.
     */
    override fun connect(socketFactory: SocketFactory?, host: String, port: Int, timeout: Int) {
        val target = KnownHost.formatHostPort(host, port)
        val opened = try {
            bastion.openChannel(DIRECT_TCPIP) as ChannelDirectTCPIP
        } catch (error: JSchException) {
            // The bastion session closed.
            throw openFailure(NO_REASON, target, error)
        }
        opened.setHost(host)
        opened.setPort(port)
        // Set the sink before the channel opens, so that no data from the target goes missing.
        opened.setOutputStream(pipe.sink)
        val channelOutput = opened.outputStream
        try {
            opened.connect(timeout)
        } catch (error: JSchException) {
            throw openFailure(opened.exitStatus, target, error)
        }
        // ChannelDirectTCPIP.connect does not throw some errors. The channel then stays closed.
        if (!opened.isConnected) throw openFailure(opened.exitStatus, target, error = null)
        readTimeoutMs = timeout
        output = channelOutput
        channel = opened
    }

    override fun getInputStream(): InputStream = pipe.source

    override fun getOutputStream(): OutputStream? = output

    /** Returns null, because the target session has no socket. */
    override fun getSocket(): Socket? = null

    /** Closes the channel and the pipe. The bastion session stays open. */
    override fun close() {
        channel?.let { runCatching { it.disconnect() } }
        channel = null
        pipe.close()
    }

    /**
     * Returns the error for a channel to [target] that did not open. [reason] is the reason code of the bastion, or
     * [NO_REASON]. A failure that a later attempt can correct is an [IOException], so that
     * [RealSshBackend.toAuthResult] gives [SshAuthResult.NetworkError]: the bastion cannot connect to the target, has
     * no resources at this time, or does not reply. A channel that the bastion refuses is a [JSchException].
     */
    internal fun openFailure(reason: Int, target: String, error: JSchException?): Exception {
        val failure = "The bastion $bastionAddress did not open a channel to $target"
        return when (reason) {
            SSH_OPEN_ADMINISTRATIVELY_PROHIBITED -> JSchException(
                "$failure: the bastion does not permit it. Make sure that the bastion permits TCP forwarding.",
                error,
            )

            SSH_OPEN_CONNECT_FAILED ->
                ConnectException("$failure: the bastion could not connect to $target.").apply { initCause(error) }

            SSH_OPEN_UNKNOWN_CHANNEL_TYPE -> JSchException("$failure: the bastion does not know direct-tcpip.", error)

            SSH_OPEN_RESOURCE_SHORTAGE ->
                IOException("$failure: the bastion has no resources for it at this time.", error)

            // No reply in the timeout, or the bastion session closed.
            else -> IOException("$failure: ${error?.message ?: "the channel closed"}", error)
        }
    }

    internal companion object {
        private const val DIRECT_TCPIP = "direct-tcpip"

        /** The JSch exit status of a channel that got no SSH_MSG_CHANNEL_OPEN_FAILURE. */
        const val NO_REASON = -1

        // The reason codes of SSH_MSG_CHANNEL_OPEN_FAILURE (RFC 4254, section 5.1). JSch puts the code in
        // Channel.exitStatus.
        const val SSH_OPEN_ADMINISTRATIVELY_PROHIBITED = 1
        const val SSH_OPEN_CONNECT_FAILED = 2
        const val SSH_OPEN_UNKNOWN_CHANNEL_TYPE = 3
        const val SSH_OPEN_RESOURCE_SHORTAGE = 4
    }
}

/**
 * A bounded byte buffer between two threads. The bastion session writes the data of a channel to [sink], and the
 * target session reads the data from [source].
 *
 * A read waits for data for a maximum of [readTimeoutMs], and then throws a [SocketTimeoutException]. JSch then
 * sends a keepalive message, the same as for a socket timeout. A write waits while the buffer is full, so a slow
 * reader stops the writer. A close of [sink] gives the end of the stream to [source] after the buffered data. A close
 * of [source] or [close] stops all reads and writes with an [IOException].
 */
internal class ChannelPipe(capacity: Int = DEFAULT_CAPACITY) {
    private val buffer = ByteArray(capacity)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var readIndex = 0
    private var size = 0
    private var sinkClosed = false
    private var sourceClosed = false

    init {
        require(capacity > 0) { "The capacity must be more than 0" }
    }

    /** The maximum time in milliseconds that a read waits for data. 0 sets no limit. */
    @Volatile
    var readTimeoutMs: Int = 0

    /** The stream that the target session reads. */
    val source: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = readInto(b, off, len)

        override fun available(): Int = lock.withLock { size }

        override fun close() = closeSource()
    }

    /** The stream that the bastion session writes. */
    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) = writeFrom(b, off, len)

        override fun close() = closeSink()
    }

    /** Closes the two ends. A read or a write that waits stops with an [IOException]. */
    fun close() = closeSource()

    private fun readInto(target: ByteArray, offset: Int, length: Int): Int {
        checkRange(target, offset, length)
        if (length == 0) return 0
        lock.withLock {
            val timeoutMs = readTimeoutMs
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            while (true) {
                if (sourceClosed) throw IOException("The pipe is closed")
                if (size > 0) break
                if (sinkClosed) return -1
                if (timeoutMs <= 0) {
                    awaitChange()
                } else {
                    if (remainingNanos <= 0L) throw SocketTimeoutException("No data for $timeoutMs ms")
                    remainingNanos = awaitChange(remainingNanos)
                }
            }
            val count = minOf(length, size, buffer.size - readIndex)
            System.arraycopy(buffer, readIndex, target, offset, count)
            readIndex = (readIndex + count) % buffer.size
            size -= count
            changed.signalAll()
            return count
        }
    }

    private fun writeFrom(data: ByteArray, offset: Int, length: Int) {
        checkRange(data, offset, length)
        var next = offset
        var remaining = length
        lock.withLock {
            while (remaining > 0) {
                if (sourceClosed || sinkClosed) throw IOException("The pipe is closed")
                if (size == buffer.size) {
                    awaitChange()
                    continue
                }
                val writeIndex = (readIndex + size) % buffer.size
                val count = minOf(remaining, buffer.size - size, buffer.size - writeIndex)
                System.arraycopy(data, next, buffer, writeIndex, count)
                size += count
                next += count
                remaining -= count
                changed.signalAll()
            }
        }
    }

    private fun closeSink() = lock.withLock {
        sinkClosed = true
        changed.signalAll()
    }

    private fun closeSource() = lock.withLock {
        sourceClosed = true
        sinkClosed = true
        size = 0
        changed.signalAll()
    }

    /**
     * Waits for a signal of [changed]. Only a close of a session interrupts its thread, so an interrupt stops the
     * read or the write with an [IOException]. The thread keeps its interrupt status.
     */
    private fun awaitChange(nanos: Long? = null): Long =
        try {
            if (nanos == null) {
                changed.await()
                0L
            } else {
                changed.awaitNanos(nanos)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("The pipe was interrupted", error)
        }

    private fun checkRange(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > bytes.size - offset) {
            throw IndexOutOfBoundsException("offset $offset, length $length, size ${bytes.size}")
        }
    }

    private companion object {
        /** The same size as the local window of a JSch `direct-tcpip` channel. */
        const val DEFAULT_CAPACITY = 0x20000
        const val BYTE_MASK = 0xFF
    }
}
