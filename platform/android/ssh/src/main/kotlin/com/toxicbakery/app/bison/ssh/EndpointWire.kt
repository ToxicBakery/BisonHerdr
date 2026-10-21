package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.SurfaceRect
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A malformed or unsupported Herdr endpoint frame. */
internal class EndpointWireException(message: String) : IOException(message)

/**
 * Reads values in the bincode 2 `standard()` format that Herdr uses on `herdr-client.sock`
 * and `herdr remote-client-bridge`.
 *
 * Integers wider than one byte use the bincode variable-length format. A `u8` and a `bool`
 * are one byte. A string and a `Vec` start with a variable-length length.
 */
internal class BincodeReader(private val buffer: ByteArray) {
    var position: Int = 0
        private set

    val remaining: Int get() = buffer.size - position

    private fun require(count: Int) {
        if (count < 0 || position + count > buffer.size) {
            throw EndpointWireException("Truncated endpoint frame at byte $position (needs $count more)")
        }
    }

    fun u8(): Int {
        require(1)
        return buffer[position++].toInt() and 0xFF
    }

    fun bool(): Boolean = when (val value = u8()) {
        0 -> false
        1 -> true
        else -> throw EndpointWireException("Bad bool value $value at byte ${position - 1}")
    }

    private fun littleEndian(byteCount: Int): Long {
        require(byteCount)
        var value = 0L
        for (index in 0 until byteCount) {
            value = value or ((buffer[position + index].toLong() and 0xFF) shl (8 * index))
        }
        position += byteCount
        return value
    }

    /** Reads an unsigned variable-length integer. Values above [Long.MAX_VALUE] are not supported. */
    fun varULong(): Long = when (val tag = u8()) {
        in 0..250 -> tag.toLong()
        251 -> littleEndian(2)
        252 -> littleEndian(4)
        253 -> littleEndian(8).also { if (it < 0) throw EndpointWireException("u64 value is too large") }
        else -> throw EndpointWireException("Unsupported variable-length integer tag $tag")
    }

    fun varUInt(): Int {
        val value = varULong()
        if (value > Int.MAX_VALUE) throw EndpointWireException("Integer $value is too large")
        return value.toInt()
    }

    /** Reads a `u32` value. The result keeps all 32 bits. */
    fun u32(): UInt {
        val value = varULong()
        if (value > 0xFFFF_FFFFL) throw EndpointWireException("u32 value $value is out of range")
        return value.toUInt()
    }

    fun length(): Int {
        val count = varUInt()
        if (count > remaining) throw EndpointWireException("Length $count is larger than the frame")
        return count
    }

    fun string(): String {
        val count = length()
        val value = buffer.decodeToString(position, position + count, throwOnInvalidSequence = true)
        position += count
        return value
    }

    fun skipBytes() {
        val count = length()
        position += count
    }

    inline fun <T> option(read: () -> T): T? = when (val tag = u8()) {
        0 -> null
        1 -> read()
        else -> throw EndpointWireException("Bad Option tag $tag")
    }

    inline fun <T> list(read: () -> T): List<T> {
        val count = varUInt()
        if (count > remaining) throw EndpointWireException("List size $count is larger than the frame")
        return List(count) { read() }
    }
}

/** Writes values in the bincode 2 `standard()` format. */
internal class BincodeWriter {
    private val out = ByteArrayOutputStream(256)

    fun u8(value: Int): BincodeWriter = apply { out.write(value and 0xFF) }

    fun bool(value: Boolean): BincodeWriter = u8(if (value) 1 else 0)

    private fun littleEndian(value: Long, byteCount: Int) {
        for (index in 0 until byteCount) out.write(((value ushr (8 * index)) and 0xFF).toInt())
    }

    fun varULong(value: Long): BincodeWriter = apply {
        require(value >= 0) { "Negative value $value" }
        when {
            value <= 250 -> out.write(value.toInt())
            value <= 0xFFFF -> {
                out.write(251)
                littleEndian(value, 2)
            }
            value <= 0xFFFF_FFFFL -> {
                out.write(252)
                littleEndian(value, 4)
            }
            else -> {
                out.write(253)
                littleEndian(value, 8)
            }
        }
    }

    fun string(value: String): BincodeWriter = apply {
        val bytes = value.encodeToByteArray()
        varULong(bytes.size.toLong())
        out.write(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal data class WireCursor(
    val x: Int,
    val y: Int,
    val visible: Boolean,
    val shape: CursorShape,
)

internal data class WireScroll(
    val offsetFromBottom: Long,
    val maxOffsetFromBottom: Long,
    val viewportRows: Long,
)

internal data class WirePane(
    val paneId: String,
    val contentRevision: Long,
    val rect: SurfaceRect,
    val innerRect: SurfaceRect,
    val scrollbarRect: SurfaceRect?,
    val scroll: WireScroll?,
    val focused: Boolean,
    val mouseReporting: Boolean,
)

/**
 * One split divider. [dividerIsColumn] is true when the divider is a column between a left
 * and a right pane. Herdr calls this direction `Horizontal`. The app calls it `Vertical`.
 */
internal data class WireSplit(
    val dividerIsColumn: Boolean,
    val position: Int,
    val area: SurfaceRect,
    val hitRect: SurfaceRect,
    val path: List<Boolean>,
)

internal class WireSurface(
    val bootId: String,
    val surfaceRevision: Long,
    val width: Int,
    val height: Int,
    val cells: Array<CellData>,
    val cursor: WireCursor?,
    val panes: List<WirePane>,
    val splits: List<WireSplit>,
)

internal class WirePatchRow(
    val x: Int,
    val y: Int,
    val cells: List<CellData>,
)

internal class WirePatch(
    val bootId: String,
    val baseSurfaceRevision: Long,
    val surfaceRevision: Long,
    val rows: List<WirePatchRow>,
    val panes: List<WirePane>,
    val cursor: WireCursor?,
)

/** The `ServerMessage` variants that the surface stream uses. Other variants become [Other]. */
internal sealed interface EndpointServerMessage {
    data class Control(val kind: String, val data: String) : EndpointServerMessage
    class Surface(val surface: WireSurface) : EndpointServerMessage
    class Patch(val patch: WirePatch) : EndpointServerMessage
    data class Shutdown(val reason: String?) : EndpointServerMessage
    data class ShellError(val message: String) : EndpointServerMessage
    data class Other(val variant: Int) : EndpointServerMessage
}

/**
 * Encoder and decoder for the Herdr endpoint generation 1 protocol (`[u32 LE length][bincode]` frames).
 *
 * Only the messages for the pane surface stream are supported. The client asks for plain
 * `PaneSurface` and `PaneSurfacePatch` messages (no surface reuse, delta, or scroll encodings),
 * so each message has the complete data for its revision.
 */
internal object EndpointWire {
    const val MAX_FRAME_SIZE: Int = 32 * 1024 * 1024

    const val HELLO_KIND = "endpoint.hello.v1"
    const val WELCOME_KIND = "endpoint.welcome.v1"
    const val HEALTH_PING_KIND = "endpoint.health.ping.v1"
    const val HEALTH_PONG_KIND = "endpoint.health.pong.v1"
    const val PRESENTATION_SYNC_KIND = "endpoint.presentation.sync.v1"
    const val PRESENTATION_READY_KIND = "endpoint.presentation.ready.v1"

    // ClientMessage variant indexes. Endpoint generation 1 freezes this order.
    private const val CLIENT_SHELL_RESIZE = 12
    private const val CLIENT_SHELL_ENDPOINT_REQUEST = 15
    private const val CLIENT_ENDPOINT_CONTROL = 20

    // ServerMessage variant indexes. Endpoint generation 1 freezes this order.
    private const val SERVER_SHUTDOWN = 3
    private const val SERVER_PANE_SURFACE = 13
    private const val SERVER_CLIENT_SHELL_ERROR = 15
    private const val SERVER_PANE_SURFACE_PATCH = 19
    private const val SERVER_ENDPOINT_CONTROL = 20

    // Herdr modifier bits (wire.rs MODIFIER_*).
    private const val WIRE_BOLD = 0x0001
    private const val WIRE_DIM = 0x0002
    private const val WIRE_ITALIC = 0x0004
    private const val WIRE_UNDERLINED = 0x0008
    private const val WIRE_REVERSED = 0x0040
    private const val WIRE_HIDDEN = 0x0080
    private const val WIRE_CROSSED_OUT = 0x0100
    private const val WIRE_UNDERLINE_STYLE_SHIFT = 12
    private const val WIRE_UNDERLINE_STYLE_CURLY = 3

    // Herdr named colors (wire.rs COLOR_BLACK..COLOR_WHITE) use 0x01..0x10 with a zero tag byte.
    private const val WIRE_NAMED_COLOR_FIRST = 0x01u
    private const val WIRE_NAMED_COLOR_LAST = 0x10u

    private fun frame(variant: Int, body: BincodeWriter.() -> Unit): ByteArray {
        val payload = BincodeWriter().varULong(variant.toLong()).apply(body).toByteArray()
        val out = ByteArray(4 + payload.size)
        val size = payload.size
        out[0] = (size and 0xFF).toByte()
        out[1] = ((size ushr 8) and 0xFF).toByte()
        out[2] = ((size ushr 16) and 0xFF).toByte()
        out[3] = ((size ushr 24) and 0xFF).toByte()
        payload.copyInto(out, destinationOffset = 4)
        return out
    }

    fun encodeControl(kind: String, data: String): ByteArray =
        frame(CLIENT_ENDPOINT_CONTROL) {
            string(kind)
            string(data)
        }

    /** Builds the `endpoint.hello.v1` handshake for a client shell with a [cols] x [rows] surface. */
    fun encodeHello(cols: Int, rows: Int, surfaceActive: Boolean = true): ByteArray {
        val hello = buildJsonObject {
            put("generation", 1)
            put("cell_width_px", 0)
            put("cell_height_px", 0)
            put(
                "surface_size",
                buildJsonObject {
                    put("cols", cols)
                    put("rows", rows)
                },
            )
            put("pixel_mouse", false)
            put("direct_graphics", false)
            put("endpoint_keybindings", false)
            put("mouse_capture", false)
            put("surface_active", surfaceActive)
            put("surface_reuse", false)
            put("surface_delta", false)
            put("surface_scroll", false)
            put("snapshot_codecs", buildJsonArray { add("shell.snapshot.v1") })
            put("surface_codecs", buildJsonArray { add("shell.surface.v1") })
            put("input_codecs", buildJsonArray { add("shell.input.semantic.v1") })
            put("blob_codecs", buildJsonArray { add("shell.blob.v1") })
        }
        return encodeControl(HELLO_KIND, hello.toString())
    }

    /** Builds `ClientMessage::ClientShellResize` for a new [cols] x [rows] surface. */
    fun encodeResize(cols: Int, rows: Int): ByteArray =
        frame(CLIENT_SHELL_RESIZE) {
            varULong(0) // cell_width_px
            varULong(0) // cell_height_px
            varULong(cols.toLong())
            varULong(rows.toLong())
            bool(false) // pixel_mouse
        }

    /** Builds `ClientMessage::ClientShellEndpointRequest` for one JSON-RPC [requestJson] line. */
    fun encodeEndpointRequest(bootId: String, requestJson: String): ByteArray =
        frame(CLIENT_SHELL_ENDPOINT_REQUEST) {
            string(bootId)
            string(requestJson)
        }

    /** Reads one frame payload from [input]. Returns null at the end of the stream. */
    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        if (!readFully(input, header, allowEof = true)) return null
        val size = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 24)
        if (size !in 1..MAX_FRAME_SIZE) throw EndpointWireException("Bad endpoint frame size $size")
        val payload = ByteArray(size)
        readFully(input, payload, allowEof = false)
        return payload
    }

    private fun readFully(input: InputStream, target: ByteArray, allowEof: Boolean): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) {
                if (allowEof && offset == 0) return false
                throw EOFException("Endpoint stream ended inside a frame")
            }
            offset += count
        }
        return true
    }

    fun decodeServerMessage(payload: ByteArray): EndpointServerMessage {
        val reader = BincodeReader(payload)
        return when (val variant = reader.varUInt()) {
            SERVER_ENDPOINT_CONTROL -> EndpointServerMessage.Control(kind = reader.string(), data = reader.string())
            SERVER_PANE_SURFACE -> EndpointServerMessage.Surface(reader.surface())
            SERVER_PANE_SURFACE_PATCH -> EndpointServerMessage.Patch(reader.patch())
            SERVER_SHUTDOWN -> EndpointServerMessage.Shutdown(reader.option { reader.string() })
            SERVER_CLIENT_SHELL_ERROR -> EndpointServerMessage.ShellError(reader.string())
            else -> EndpointServerMessage.Other(variant)
        }
    }

    private fun BincodeReader.rect(): SurfaceRect =
        SurfaceRect(
            x = varUInt().toUShort(),
            y = varUInt().toUShort(),
            width = varUInt().toUShort(),
            height = varUInt().toUShort(),
        )

    private fun BincodeReader.cursor(): WireCursor {
        val x = varUInt()
        val y = varUInt()
        val visible = bool()
        val shape = when (u8()) {
            3, 4 -> CursorShape.Underline
            5, 6 -> CursorShape.Bar
            else -> CursorShape.Block
        }
        return WireCursor(x = x, y = y, visible = visible, shape = shape)
    }

    private fun BincodeReader.cell(): CellData {
        val symbol = string()
        val fg = mapColor(u32())
        val bg = mapColor(u32())
        val wireModifier = varUInt()
        val skip = bool()
        val hyperlink = option { u32() }
        val hidden = (wireModifier and WIRE_HIDDEN) != 0
        return CellData(
            symbol = if (hidden && symbol.isNotEmpty()) " " else symbol,
            fg = fg,
            bg = bg,
            modifier = mapModifier(wireModifier),
            skip = skip,
            hyperlink = hyperlink,
        )
    }

    private fun BincodeReader.pane(): WirePane {
        val paneId = string()
        val contentRevision = varULong()
        val rect = rect()
        val innerRect = rect()
        val scrollbarRect = option { rect() }
        val scroll = option { WireScroll(varULong(), varULong(), varULong()) }
        val focused = bool()
        val mouseReporting = bool()
        bool() // sgr_pixel_mouse
        bool() // alternate_screen_active
        varULong() // pixel_width
        varULong() // pixel_height
        return WirePane(
            paneId = paneId,
            contentRevision = contentRevision,
            rect = rect,
            innerRect = innerRect,
            scrollbarRect = scrollbarRect,
            scroll = scroll,
            focused = focused,
            mouseReporting = mouseReporting,
        )
    }

    private fun BincodeReader.split(): WireSplit {
        val dividerIsColumn = when (val direction = varUInt()) {
            0 -> true // Horizontal: panes side by side, the divider is a column
            1 -> false // Vertical: panes stacked, the divider is a row
            else -> throw EndpointWireException("Bad split direction $direction")
        }
        return WireSplit(
            dividerIsColumn = dividerIsColumn,
            position = varUInt(),
            area = rect(),
            hitRect = rect(),
            path = list { bool() },
        )
    }

    private fun BincodeReader.surface(): WireSurface {
        val bootId = string()
        varULong() // projection_revision
        val surfaceRevision = varULong()
        // FrameData field order: cells, width, height, cursor, hyperlinks, graphics.
        val cells = list { cell() }.toTypedArray()
        val width = varUInt()
        val height = varUInt()
        val cursor = option { cursor() }
        list { string() } // hyperlinks
        skipBytes() // graphics
        val panes = list { pane() }
        val splits = list { split() }
        // The popup and graphics scene follow. The surface stream does not use them.
        if (cells.size != width * height) {
            throw EndpointWireException("Surface has ${cells.size} cells for ${width}x$height")
        }
        return WireSurface(
            bootId = bootId,
            surfaceRevision = surfaceRevision,
            width = width,
            height = height,
            cells = cells,
            cursor = cursor,
            panes = panes,
            splits = splits,
        )
    }

    private fun BincodeReader.patch(): WirePatch {
        val bootId = string()
        varULong() // projection_revision
        val baseSurfaceRevision = varULong()
        val surfaceRevision = varULong()
        val rows = list { WirePatchRow(x = varUInt(), y = varUInt(), cells = list { cell() }) }
        val panes = list { pane() }
        val cursor = option { cursor() }
        return WirePatch(
            bootId = bootId,
            baseSurfaceRevision = baseSurfaceRevision,
            surfaceRevision = surfaceRevision,
            rows = rows,
            panes = panes,
            cursor = cursor,
        )
    }

    /** Maps Herdr named colors (`0x01..0x10`) to the 16-color palette. Other values are the same in the app. */
    internal fun mapColor(wire: UInt): UInt =
        if (wire in WIRE_NAMED_COLOR_FIRST..WIRE_NAMED_COLOR_LAST) {
            CellData.indexedColor((wire - WIRE_NAMED_COLOR_FIRST).toInt())
        } else {
            wire
        }

    /** Maps Herdr modifier bits to [CellData] modifier bits. Blink and unknown bits are dropped. */
    internal fun mapModifier(wire: Int): UShort {
        var mapped = 0
        if (wire and WIRE_BOLD != 0) mapped = mapped or CellData.MOD_BOLD.toInt()
        if (wire and WIRE_DIM != 0) mapped = mapped or CellData.MOD_DIM.toInt()
        if (wire and WIRE_ITALIC != 0) mapped = mapped or CellData.MOD_ITALIC.toInt()
        if (wire and WIRE_REVERSED != 0) mapped = mapped or CellData.MOD_REVERSE.toInt()
        if (wire and WIRE_CROSSED_OUT != 0) mapped = mapped or CellData.MOD_STRIKETHROUGH.toInt()
        if (wire and WIRE_UNDERLINED != 0) {
            val style = (wire ushr WIRE_UNDERLINE_STYLE_SHIFT) and 0x0F
            mapped = mapped or if (style == WIRE_UNDERLINE_STYLE_CURLY) {
                CellData.MOD_CURLY_UNDERLINE.toInt()
            } else {
                CellData.MOD_UNDERLINE.toInt()
            }
        }
        return mapped.toUShort()
    }
}
