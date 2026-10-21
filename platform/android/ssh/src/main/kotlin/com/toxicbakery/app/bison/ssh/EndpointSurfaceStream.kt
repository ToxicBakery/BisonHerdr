package com.toxicbakery.app.bison.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.toxicbakery.app.bison.protocol.CellData
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An immutable copy of the current endpoint surface. */
internal class EndpointSurfaceSnapshot(
    val bootId: String,
    val revision: Long,
    val width: Int,
    val height: Int,
    val cells: List<CellData>,
    val cursor: WireCursor?,
    val panes: List<WirePane>,
    val splits: List<WireSplit>,
)

/**
 * The cell grid of one Herdr client-shell surface. A [WireSurface] replaces the grid, and a
 * [WirePatch] changes the cells, the pane metadata, and the cursor of the current revision.
 */
internal class EndpointSurfaceState {
    private var bootId = ""
    private var revision = NO_REVISION
    private var width = 0
    private var height = 0
    private var cells: Array<CellData> = emptyArray()
    private var cursor: WireCursor? = null
    private var panes: List<WirePane> = emptyList()
    private var splits: List<WireSplit> = emptyList()

    val hasSurface: Boolean get() = revision != NO_REVISION

    fun apply(surface: WireSurface) {
        bootId = surface.bootId
        revision = surface.surfaceRevision
        width = surface.width
        height = surface.height
        cells = surface.cells.copyOf()
        cursor = surface.cursor
        panes = surface.panes
        splits = surface.splits
    }

    /** Applies [patch] and returns true. Returns false when [patch] is not for the current revision. */
    fun apply(patch: WirePatch): Boolean {
        if (!hasSurface || patch.bootId != bootId || patch.baseSurfaceRevision != revision) return false
        for (row in patch.rows) {
            if (row.y !in 0 until height) continue
            val rowStart = row.y * width
            row.cells.forEachIndexed { index, cell ->
                val col = row.x + index
                if (col in 0 until width) cells[rowStart + col] = cell
            }
        }
        if (patch.panes.isNotEmpty()) {
            val updated = patch.panes.associateBy { it.paneId }
            panes = panes.map { pane -> updated[pane.paneId] ?: pane }
        }
        cursor = patch.cursor
        revision = patch.surfaceRevision
        return true
    }

    fun snapshot(): EndpointSurfaceSnapshot =
        EndpointSurfaceSnapshot(
            bootId = bootId,
            revision = revision,
            width = width,
            height = height,
            cells = cells.copyOf().asList(),
            cursor = cursor,
            panes = panes,
            splits = splits,
        )

    private companion object {
        const val NO_REVISION = -1L
    }
}

/**
 * A persistent `herdr remote-client-bridge` channel that receives the rendered pane surface of
 * the active tab, with the real terminal cursor, from an unmodified Herdr server.
 *
 * Herdr renders the surface at the size from [start] and [resize]. Like other Herdr clients,
 * this sets the pane sizes on the server.
 */
internal class EndpointSurfaceStream(
    private val machineId: String,
    private val sshDebugLog: SshDebugLog,
    private val scope: CoroutineScope,
    private val onSurface: (EndpointSurfaceSnapshot) -> Unit,
    private val onClosed: (stream: EndpointSurfaceStream, receivedSurface: Boolean, error: Throwable?) -> Unit,
) {
    private val writeLock = Any()
    private val state = EndpointSurfaceState()
    private val requestCounter = AtomicLong(0L)

    @Volatile
    private var closed = false

    @Volatile
    private var channel: ChannelExec? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var readJob: Job? = null

    @Volatile
    private var bootId: String? = null

    @Volatile
    private var surfaceCols = 0

    @Volatile
    private var surfaceRows = 0

    /** True after the first surface arrives, until the stream closes. */
    @Volatile
    var isLive: Boolean = false
        private set

    fun start(session: Session, command: String, cols: Int, rows: Int) {
        val execChannel = session.openChannel("exec") as ChannelExec
        execChannel.setCommand(command)
        execChannel.setErrStream(ByteArrayOutputStream())
        val input = execChannel.inputStream
        val out = execChannel.outputStream
        execChannel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
        channel = execChannel
        output = out
        surfaceCols = cols
        surfaceRows = rows
        write(EndpointWire.encodeHello(cols = cols, rows = rows))
        readJob = scope.launch(Dispatchers.IO) {
            readLoop(BufferedInputStream(input, READ_BUFFER_BYTES))
        }
    }

    private fun readLoop(input: InputStream) {
        var error: Throwable? = null
        try {
            while (!closed) {
                val payload = EndpointWire.readFrame(input) ?: break
                if (!handle(EndpointWire.decodeServerMessage(payload))) break
            }
        } catch (err: Exception) {
            error = err
        }
        val receivedSurface = state.hasSurface
        val wasClosedLocally = closed
        close()
        if (!wasClosedLocally) onClosed(this, receivedSurface, error)
    }

    /** Handles one message. Returns false when the server ends the stream. */
    private fun handle(message: EndpointServerMessage): Boolean {
        when (message) {
            is EndpointServerMessage.Surface -> {
                state.apply(message.surface)
                bootId = message.surface.bootId
                if (!isLive) {
                    isLive = true
                    sshDebugLog.record(
                        machineId = machineId,
                        tag = "SurfaceStream",
                        message = "Receiving Herdr pane surfaces at ${message.surface.width}x${message.surface.height}",
                    )
                }
                onSurface(state.snapshot())
            }

            is EndpointServerMessage.Patch -> {
                if (state.apply(message.patch)) {
                    onSurface(state.snapshot())
                } else {
                    // A resize to the same size makes the server send a new complete surface.
                    write(EndpointWire.encodeResize(cols = surfaceCols, rows = surfaceRows))
                }
            }

            is EndpointServerMessage.Control -> when (message.kind) {
                EndpointWire.HEALTH_PING_KIND ->
                    write(EndpointWire.encodeControl(EndpointWire.HEALTH_PONG_KIND, message.data))

                EndpointWire.PRESENTATION_SYNC_KIND ->
                    write(EndpointWire.encodeControl(EndpointWire.PRESENTATION_READY_KIND, message.data))

                else -> Unit
            }

            is EndpointServerMessage.ShellError -> sshDebugLog.record(
                machineId = machineId,
                tag = "SurfaceStream",
                message = "Herdr client shell error: ${message.message}",
                isError = true,
            )

            is EndpointServerMessage.Shutdown -> {
                sshDebugLog.record(
                    machineId = machineId,
                    tag = "SurfaceStream",
                    message = "Herdr server shut down (${message.reason ?: "no reason"})",
                )
                return false
            }

            is EndpointServerMessage.Other -> Unit
        }
        return true
    }

    /** Asks the server to render the surface at [cols] x [rows]. */
    fun resize(cols: Int, rows: Int) {
        if (cols == surfaceCols && rows == surfaceRows) return
        surfaceCols = cols
        surfaceRows = rows
        write(EndpointWire.encodeResize(cols = cols, rows = rows))
    }

    /** Stops (`false`) or starts (`true`) surface traffic with `client_shell.surface.set`. */
    fun setSurfaceActive(active: Boolean) {
        val currentBootId = bootId ?: return
        val request = buildJsonObject {
            put("id", "bison_surface_${requestCounter.incrementAndGet()}")
            put("method", "client_shell.surface.set")
            put("params", buildJsonObject { put("active", active) })
        }
        write(EndpointWire.encodeEndpointRequest(currentBootId, request.toString()))
    }

    private fun write(frame: ByteArray) {
        val out = output ?: return
        runCatching {
            synchronized(writeLock) {
                out.write(frame)
                out.flush()
            }
        }.onFailure { err ->
            sshDebugLog.record(
                machineId = machineId,
                tag = "SurfaceStream",
                message = "Write to remote-client-bridge failed: ${err.message}",
                isError = true,
            )
        }
    }

    fun close() {
        closed = true
        isLive = false
        runCatching { output?.close() }
        runCatching { channel?.disconnect() }
        output = null
        channel = null
        readJob?.cancel()
        readJob = null
    }

    private companion object {
        const val CHANNEL_CONNECT_TIMEOUT_MS = 8_000
        const val READ_BUFFER_BYTES = 64 * 1024
    }
}
