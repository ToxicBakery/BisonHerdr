package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import com.toxicbakery.app.bison.protocol.SurfaceRect
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [EndpointWire] and [EndpointSurfaceState] against frames from a real Herdr 0.9.5 server.
 *
 * `herdr-0.9.5-split-60x12.bin` is the output of `herdr remote-client-bridge` for a 60x12 surface
 * with two panes (split right at 0.6). The left pane shows `bold red` (bold, color 1), `reverse`
 * (reverse video), `rgb` (RGB 10,20,30), and the prompt `$ > typed`. A patch then adds `!`.
 * Only the welcome, surface, and patch frames are kept.
 */
class EndpointWireTest {

    private fun resource(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/endpoint/$name"), "Missing $name").use { it.readBytes() }

    private fun recordedMessages(): List<EndpointServerMessage> {
        val input = ByteArrayInputStream(resource("herdr-0.9.5-split-60x12.bin"))
        return generateSequence { EndpointWire.readFrame(input) }
            .map { EndpointWire.decodeServerMessage(it) }
            .toList()
    }

    private fun rowText(snapshot: EndpointSurfaceSnapshot, row: Int, fromCol: Int, toCol: Int): String =
        (fromCol until toCol).joinToString("") { snapshot.cells[row * snapshot.width + it].symbol }

    @Test
    fun helloMatchesTheHandshakeThatHerdrAccepted() {
        assertContentEquals(resource("hello-60x12.bin"), EndpointWire.encodeHello(cols = 60, rows = 12))
    }

    @Test
    fun resizeUsesClientShellResizeVariantAndVariableLengthIntegers() {
        assertContentEquals(
            byteArrayOf(6, 0, 0, 0, 12, 0, 0, 120, 40, 0),
            EndpointWire.encodeResize(cols = 120, rows = 40),
        )
        assertContentEquals(
            byteArrayOf(8, 0, 0, 0, 12, 0, 0, 251.toByte(), 44, 1, 40, 0),
            EndpointWire.encodeResize(cols = 300, rows = 40),
        )
    }

    @Test
    fun variableLengthIntegersRoundTrip() {
        val values = listOf(0L, 250L, 251L, 65_535L, 65_536L, 4_294_967_295L, 4_294_967_296L)
        val writer = BincodeWriter()
        values.forEach { writer.varULong(it) }
        val reader = BincodeReader(writer.toByteArray())
        assertEquals(values, values.map { reader.varULong() })
        assertEquals(0, reader.remaining)
    }

    @Test
    fun decodesTheRecordedMessageSequence() {
        val messages = recordedMessages()
        assertEquals(3, messages.size)
        val welcome = assertIs<EndpointServerMessage.Control>(messages[0])
        assertEquals(EndpointWire.WELCOME_KIND, welcome.kind)
        assertTrue(welcome.data.contains("\"generation\":1"))
        assertIs<EndpointServerMessage.Surface>(messages[1])
        assertIs<EndpointServerMessage.Patch>(messages[2])
    }

    @Test
    fun surfaceHasTheRealCursorPanesAndSplit() {
        val surface = assertIs<EndpointServerMessage.Surface>(recordedMessages()[1]).surface
        assertEquals(60, surface.width)
        assertEquals(12, surface.height)
        assertEquals(WireCursor(x = 10, y = 2, visible = true, shape = CursorShape.Block), surface.cursor)

        val (left, right) = surface.panes
        assertEquals("w2:p1", left.paneId)
        assertTrue(left.focused)
        assertEquals(SurfaceRect(0u, 0u, 36u, 12u), left.rect)
        assertEquals(SurfaceRect(1u, 1u, 33u, 10u), left.innerRect)
        assertEquals("w2:p2", right.paneId)
        assertFalse(right.focused)
        assertEquals(SurfaceRect(36u, 0u, 24u, 12u), right.rect)

        val split = surface.splits.single()
        assertTrue(split.dividerIsColumn)
        assertEquals(36, split.position)
        assertEquals(SurfaceRect(0u, 0u, 60u, 12u), split.area)
        assertEquals(SurfaceRect(35u, 0u, 2u, 12u), split.hitRect)
        assertEquals(emptyList(), split.path)
    }

    @Test
    fun surfaceCellsKeepTextColorsAndBold() {
        val surface = assertIs<EndpointServerMessage.Surface>(recordedMessages()[1]).surface
        fun cell(col: Int, row: Int) = surface.cells[row * surface.width + col]

        assertEquals("b", cell(1, 1).symbol)
        assertEquals(CellData.indexedColor(1), cell(1, 1).fg)
        assertTrue(cell(1, 1).isBold)

        // Herdr applies reverse video itself and sends the final colors.
        assertEquals("r", cell(10, 1).symbol)
        assertEquals(CellData.rgbColor(0, 0, 0), cell(10, 1).fg)
        assertEquals(CellData.rgbColor(255, 255, 255), cell(10, 1).bg)

        assertEquals(CellData.rgbColor(10, 20, 30), cell(18, 1).fg)
        assertEquals("┌", cell(0, 0).symbol)
    }

    @Test
    fun stateAppliesThePatchAndMovesTheCursor() {
        val messages = recordedMessages()
        val state = EndpointSurfaceState()
        state.apply(assertIs<EndpointServerMessage.Surface>(messages[1]).surface)
        assertEquals("$ > typed ", rowText(state.snapshot(), row = 2, fromCol = 1, toCol = 11))

        assertTrue(state.apply(assertIs<EndpointServerMessage.Patch>(messages[2]).patch))
        val patched = state.snapshot()
        assertEquals(2L, patched.revision)
        assertEquals("$ > typed!", rowText(patched, row = 2, fromCol = 1, toCol = 11))
        assertEquals(WireCursor(x = 11, y = 2, visible = true, shape = CursorShape.Block), patched.cursor)
    }

    @Test
    fun stateRejectsAPatchForAnotherRevision() {
        val messages = recordedMessages()
        val state = EndpointSurfaceState()
        val patch = assertIs<EndpointServerMessage.Patch>(messages[2]).patch
        assertFalse(state.apply(patch), "A patch before any surface must not apply")

        state.apply(assertIs<EndpointServerMessage.Surface>(messages[1]).surface)
        assertTrue(state.apply(patch))
        assertFalse(state.apply(patch), "The same patch must not apply two times")
    }

    @Test
    fun modifierBitsMapToAppBits() {
        assertEquals(CellData.MOD_REVERSE, EndpointWire.mapModifier(0x0040))
        assertEquals(CellData.MOD_STRIKETHROUGH, EndpointWire.mapModifier(0x0100))
        assertEquals(CellData.MOD_UNDERLINE, EndpointWire.mapModifier(0x0008 or (1 shl 12)))
        assertEquals(CellData.MOD_CURLY_UNDERLINE, EndpointWire.mapModifier(0x0008 or (3 shl 12)))
        assertEquals(0.toUShort(), EndpointWire.mapModifier(0x0010 or 0x0020), "Blink bits are dropped")
        assertEquals(0.toUShort(), EndpointWire.mapModifier(0x0080), "Hidden is not the predicted echo bit")
    }

    @Test
    fun namedColorsMapToThe16ColorPalette() {
        assertEquals(CellData.DEFAULT_COLOR, EndpointWire.mapColor(0u))
        assertEquals(CellData.indexedColor(0), EndpointWire.mapColor(0x01u))
        assertEquals(CellData.indexedColor(15), EndpointWire.mapColor(0x10u))
        assertEquals(CellData.indexedColor(200), EndpointWire.mapColor(CellData.indexedColor(200)))
    }

    @Test
    fun streamFrameUsesTheRealCursorAndAppSplitDirection() {
        val surface = assertIs<EndpointServerMessage.Surface>(recordedMessages()[1]).surface
        val state = EndpointSurfaceState().apply { apply(surface) }
        val client = RealHerdrSshClient(
            sshDebugLog = SshDebugLog(),
            endpointSnapshotBus = EndpointSnapshotBus(),
            paneSurfaceBus = PaneSurfaceBus(),
            semanticNotificationBus = SemanticNotificationBus(),
            remoteBatchExecutor = { _, _, _, _ -> "" },
        )
        val frame = client.buildStreamSurfaceFrame("m1", state.snapshot())

        val cursor = assertNotNull(frame.frame.cursor)
        assertEquals(10, cursor.x.toInt())
        assertEquals(2, cursor.y.toInt())
        assertTrue(cursor.visible)
        assertEquals(60, frame.frame.width.toInt())
        assertEquals(listOf("w2:p1", "w2:p2"), frame.panes.map { it.paneId })
        assertTrue(frame.panes.first().focused)

        val split = frame.splits.single()
        assertEquals(PaneSurfaceSplitDirection.Vertical, split.direction)
        assertEquals(0.6f, split.ratio, 0.001f)
        assertEquals("s0:root", split.id)
        assertEquals(SurfaceRect(35u, 0u, 2u, 12u), split.hitRect)
    }

    @Test
    fun truncatedFrameFailsWithAWireError() {
        val payload = byteArrayOf(20, 5, 'a'.code.toByte())
        val error = runCatching { EndpointWire.decodeServerMessage(payload) }.exceptionOrNull()
        assertIs<EndpointWireException>(error)
        assertNull(EndpointWire.readFrame(ByteArrayInputStream(ByteArray(0))))
    }
}
