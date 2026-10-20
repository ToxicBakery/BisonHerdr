package com.toxicbakery.app.bison.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Complete rendered terminal surface for the active Herdr tab (`shell.surface.v1`).
 */
@Serializable
data class PaneSurfaceFrame(
    @SerialName("boot_id") val bootId: String = "boot-default",
    val revision: ULong = 0u,
    val frame: FrameData,
    val panes: List<PaneSurfacePane> = emptyList(),
    val splits: List<PaneSurfaceSplit> = emptyList(),
    val popup: ClientShellPopupSurface? = null,
    val graphics: SurfaceGraphicsScene = SurfaceGraphicsScene(),
    @SerialName("presentation_epoch") val presentationEpoch: ULong? = null,
) {
    val cols: UShort get() = frame.width
    val rows: UShort get() = frame.height
}

/**
 * Origin-relative 2D cell grid and cursor state for a surface or popup.
 */
@Serializable
data class FrameData(
    val width: UShort,
    val height: UShort,
    val cells: List<CellData> = emptyList(),
    val cursor: CursorState? = null,
) {
    fun cellAt(col: Int, row: Int): CellData? {
        val w = width.toInt()
        val h = height.toInt()
        if (col !in 0..<w || row !in 0..<h) return null
        val index = row * w + col
        return cells.getOrNull(index)
    }
}

/**
 * Single terminal cell in [FrameData].
 *
 * Color encoding follows Herdr wire convention:
 * - `0x00_00_00_00`: Default theme foreground/background
 * - `0x01_00_00_XX`: 256-color indexed palette (`XX` = 0..255)
 * - `0x02_RR_GG_BB`: 24-bit TrueColor RGB
 */
@Serializable
data class CellData(
    val symbol: String = " ",
    val fg: UInt = DEFAULT_COLOR,
    val bg: UInt = DEFAULT_COLOR,
    val modifier: UShort = 0u,
    val skip: Boolean = false,
    val hyperlink: UInt? = null,
) {
    val isBold: Boolean get() = (modifier.toInt() and MOD_BOLD.toInt()) != 0
    val isDim: Boolean get() = (modifier.toInt() and MOD_DIM.toInt()) != 0
    val isItalic: Boolean get() = (modifier.toInt() and MOD_ITALIC.toInt()) != 0
    val isUnderline: Boolean get() = (modifier.toInt() and MOD_UNDERLINE.toInt()) != 0
    val isCurlyUnderline: Boolean get() = (modifier.toInt() and MOD_CURLY_UNDERLINE.toInt()) != 0
    val isReverse: Boolean get() = (modifier.toInt() and MOD_REVERSE.toInt()) != 0
    val isStrikethrough: Boolean get() = (modifier.toInt() and MOD_STRIKETHROUGH.toInt()) != 0
    val isPredictedEcho: Boolean get() = (modifier.toInt() and MOD_PREDICTED_ECHO.toInt()) != 0

    companion object {
        const val DEFAULT_COLOR: UInt = 0u

        const val MOD_BOLD: UShort = 0x0001u
        const val MOD_DIM: UShort = 0x0002u
        const val MOD_ITALIC: UShort = 0x0004u
        const val MOD_UNDERLINE: UShort = 0x0008u
        const val MOD_CURLY_UNDERLINE: UShort = 0x0010u
        const val MOD_REVERSE: UShort = 0x0020u
        const val MOD_STRIKETHROUGH: UShort = 0x0040u
        const val MOD_PREDICTED_ECHO: UShort = 0x0080u

        private val ASCII_SYMBOLS = Array(128) { it.toChar().toString() }

        val EMPTY: CellData = CellData()

        fun symbolForChar(ch: Char): String =
            if (ch.code < 128) ASCII_SYMBOLS[ch.code] else ch.toString()

        fun symbolForCodepoint(cp: Int): String =
            if (cp in 0..<128) ASCII_SYMBOLS[cp] else cp.toUnicodeSymbol()

        private fun Int.toUnicodeSymbol(): String = when (this) {
            in 0..0xFFFF -> toChar().toString()
            in 0x10000..0x10FFFF -> {
                val offset = this - 0x10000
                val high = ((offset ushr 10) + 0xD800).toChar()
                val low = ((offset and 0x3FF) + 0xDC00).toChar()
                charArrayOf(high, low).concatToString()
            }
            else -> "\uFFFD"
        }

        fun rgbColor(r: Int, g: Int, b: Int): UInt =
            0x02000000u or
                ((r.coerceIn(0, 255).toUInt()) shl 16) or
                ((g.coerceIn(0, 255).toUInt()) shl 8) or
                (b.coerceIn(0, 255).toUInt())

        fun indexedColor(index: Int): UInt =
            0x01000000u or (index.coerceIn(0, 255).toUInt())
    }
}

@Serializable
data class CursorState(
    val x: UShort,
    val y: UShort,
    val shape: CursorShape = CursorShape.Block,
    val visible: Boolean = true,
    val fg: UInt? = null,
    val bg: UInt? = null,
)

@Serializable
enum class CursorShape {
    @SerialName("block")
    Block,

    @SerialName("underline")
    Underline,

    @SerialName("bar")
    Bar,
}

@Serializable
data class SurfaceRect(
    val x: UShort,
    val y: UShort,
    val width: UShort,
    val height: UShort,
) {
    fun contains(col: Int, row: Int): Boolean {
        val minX = x.toInt()
        val minY = y.toInt()
        return col >= minX &&
            col < minX + width.toInt() &&
            row >= minY &&
            row < minY + height.toInt()
    }
}

@Serializable
data class PaneSurfaceScroll(
    @SerialName("offset_from_bottom") val offsetFromBottom: UInt = 0u,
    @SerialName("max_scrollback") val maxScrollback: UInt = 0u,
    @SerialName("viewport_rows") val viewportRows: UShort = 0u,
)

@Serializable
data class PaneSurfacePane(
    @SerialName("pane_id") val paneId: String,
    val rect: SurfaceRect,
    @SerialName("inner_rect") val innerRect: SurfaceRect = rect,
    @SerialName("scrollbar_rect") val scrollbarRect: SurfaceRect? = null,
    val scroll: PaneSurfaceScroll = PaneSurfaceScroll(),
    val focused: Boolean = false,
    val zoomed: Boolean = false,
    @SerialName("mouse_reporting") val mouseReporting: Boolean = false,
    @SerialName("content_revision") val contentRevision: ULong = 0u,
    val title: String = "",
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.Unknown,
)

@Serializable
enum class PaneSurfaceSplitDirection {
    @SerialName("vertical")
    Vertical,

    @SerialName("horizontal")
    Horizontal,
}

@Serializable
data class PaneSurfaceSplit(
    val id: String = "s0:root",
    val direction: PaneSurfaceSplitDirection,
    val ratio: Float = 0.5f,
    @SerialName("hit_rect") val hitRect: SurfaceRect,
    val path: List<Boolean> = emptyList(),
    @SerialName("workspace_rect") val workspaceRect: SurfaceRect = hitRect,
)

@Serializable
data class ClientShellPopupSurface(
    @SerialName("terminal_id") val terminalId: String,
    val title: String = "",
    val rect: SurfaceRect,
    val frame: FrameData,
)

@Serializable
data class SurfaceGraphicsScene(
    val assets: List<SurfaceGraphicsAsset> = emptyList(),
    val placements: List<SurfaceGraphicsPlacement> = emptyList(),
)

@Serializable
data class SurfaceGraphicsAsset(
    val id: UInt,
    @SerialName("width_px") val widthPx: UInt,
    @SerialName("height_px") val heightPx: UInt,
    val format: String = "png",
    @SerialName("data_base64") val dataBase64: String = "",
)

@Serializable
data class SurfaceGraphicsPlacement(
    @SerialName("asset_id") val assetId: UInt,
    @SerialName("pane_id") val paneId: String? = null,
    val rect: SurfaceRect,
    @SerialName("z_index") val zIndex: Int = 0,
)

@Serializable
data class PaneSurfacePatch(
    @SerialName("boot_id") val bootId: String = "boot-default",
    val revision: ULong,
    val width: UShort,
    val height: UShort,
    val rows: List<PaneSurfacePatchRow> = emptyList(),
    val cursor: CursorState? = null,
    val panes: List<PaneSurfacePane>? = null,
    val splits: List<PaneSurfaceSplit>? = null,
    val popup: ClientShellPopupSurface? = null,
    val graphics: SurfaceGraphicsScene? = null,
    @SerialName("presentation_epoch") val presentationEpoch: ULong? = null,
)

@Serializable
data class PaneSurfacePatchRow(
    val y: UShort,
    @SerialName("start_x") val startX: UShort = 0u,
    val cells: List<CellData>,
)
