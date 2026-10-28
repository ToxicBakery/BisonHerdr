package com.toxicbakery.app.bison.ssh

import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.CursorState
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.PaneLayoutPane
import com.toxicbakery.app.bison.protocol.PaneLayoutSnapshot
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.SurfaceRect
import com.toxicbakery.app.bison.protocol.deriveSplitsFromLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Android [MoshSessionEngine]. This version has no Mosh transport, so the engine starts no session.
 *
 * [startSession] writes [MoshSessionEngine.NOT_AVAILABLE] to the debug log and returns false. Thus each other
 * function finds no session and does nothing. The app connects each machine over the SSH endpoint bridge.
 *
 * The engine keeps the parts that do not use the network, for the Mosh path of issue #22
 * (https://github.com/ToxicBakery/BisonHerdr/issues/22):
 * - Client-side predictive echo (`CellData.MOD_PREDICTED_ECHO` underlined cells).
 * - The parked state of a session ([setParked]) and its roam count ([sendWakeupDatagram]).
 * - A [PaneSurfaceFrame] on [PaneSurfaceBus] from the screen lines and the [PaneLayoutSnapshot]
 *   (`deriveSplitsFromLayout`), with row `0` removed when `tabCount > 1`.
 *
 * An earlier version sent UDP datagrams with no encryption and read no reply, and a real `mosh-server` does not
 * accept such datagrams. Issue #22 lists the work for a real Mosh path.
 */
class MoshUdpEngine(
    private val sshDebugLog: SshDebugLog,
    private val paneSurfaceBus: PaneSurfaceBus,
) : MoshSessionEngine {

    private val sessions = ConcurrentHashMap<String, MoshSessionState>()

    internal data class PredictedCell(
        val col: Int,
        val row: Int,
        val symbol: String,
    )

    internal class MoshSessionState(
        val machineId: String,
        val host: String,
        val udpPort: Int,
        val sessionKey: String,
        var cols: UShort,
        var rows: UShort,
        var localEchoEnabled: Boolean,
        var tabCount: Int = 1,
        var parked: Boolean = false,
        var roamedCount: Int = 0,
        val frameRevision: AtomicLong = AtomicLong(1L),
        var cursorCol: Int = 0,
        var cursorRow: Int = 0,
        var layoutSnapshot: PaneLayoutSnapshot? = null,
        val predictions: MutableList<PredictedCell> = mutableListOf(),
        val screenLines: MutableList<String> = mutableListOf(),
    )

    /**
     * Writes [MoshSessionEngine.NOT_AVAILABLE] to the debug log and returns false. This version has no Mosh
     * transport (issue #22), so the engine starts no session.
     */
    override suspend fun startSession(
        machineId: String,
        host: String,
        udpPort: Int,
        sessionKey: String,
        cols: UShort,
        rows: UShort,
        localEcho: Boolean,
    ): Boolean {
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = MoshSessionEngine.NOT_AVAILABLE,
            isError = true,
        )
        return false
    }

    /**
     * Adds the predictive echo of [bytes] to the session of [machineId]. Returns false when the machine has no
     * session. This version sends no datagram.
     */
    override suspend fun sendInput(machineId: String, bytes: ByteArray): Boolean {
        val state = sessions[machineId] ?: return false
        if (bytes.isEmpty()) return true

        // Apply client-side predictive echo for printable ASCII/UTF-8 input when enabled
        if (state.localEchoEnabled) {
            val text = bytes.decodeToString()
            synchronized(state) {
                for (ch in text) {
                    when {
                        ch == '\r' || ch == '\n' -> {
                            state.predictions.clear()
                            state.cursorCol = 0
                            state.cursorRow = (state.cursorRow + 1).coerceAtMost(state.rows.toInt() - 1)
                        }

                        ch == '\u007f' || ch == '\b' -> {
                            if (state.predictions.isNotEmpty()) {
                                state.predictions.removeAt(state.predictions.lastIndex)
                            }
                            state.cursorCol = (state.cursorCol - 1).coerceAtLeast(0)
                        }

                        !ch.isISOControl() -> {
                            state.predictions.add(
                                PredictedCell(
                                    col = state.cursorCol,
                                    row = state.cursorRow,
                                    symbol = charToSymbol(ch),
                                )
                            )
                            state.cursorCol = (state.cursorCol + 1).coerceAtMost(state.cols.toInt() - 1)
                        }
                    }
                }
            }
            synthesizeAndPublishFrame(state)
        }
        return true
    }

    /**
     * Sets the viewport of the session of [machineId] to [cols] x [rows]. Returns false when the machine has no
     * session. This version sends no datagram.
     */
    override suspend fun resize(machineId: String, cols: UShort, rows: UShort): Boolean {
        val state = sessions[machineId] ?: return false
        state.cols = cols
        state.rows = rows
        // Request rows + 1 on the Mosh PTY when tabCount > 1 so row 0 (top tab bar) can be stripped cleanly
        val ptyRows = if (state.tabCount > 1) (rows.toInt() + 1).toUShort() else rows
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "Resized Mosh viewport to ${cols}x$rows (ptyRows=$ptyRows, tabCount=${state.tabCount})",
        )
        synthesizeAndPublishFrame(state)
        return true
    }

    override suspend fun setSuspended(machineId: String, suspended: Boolean) {
        setParked(machineId, suspended)
    }

    /**
     * Sets the parked state of the session of [machineId]. A Mosh client sends no acknowledgment while it is
     * parked, so that `mosh-server` stops its updates and keeps the session open. This version sends no datagram.
     */
    suspend fun setParked(machineId: String, parked: Boolean) {
        val state = sessions[machineId] ?: return
        val wasParked = state.parked
        state.parked = parked
        if (parked) {
            sshDebugLog.record(
                machineId = machineId,
                tag = LOG_TAG,
                message = "Mosh session parked",
            )
        } else if (wasParked) {
            sshDebugLog.record(
                machineId = machineId,
                tag = LOG_TAG,
                message = "Mosh session not parked",
            )
            synthesizeAndPublishFrame(state)
        }
    }

    override suspend fun notifyNetworkRoamed(machineId: String): Int =
        sendWakeupDatagram(machineId)

    /**
     * Clears the parked state of the session of [machineId], adds one to its roam count, and returns the count.
     * Returns 0 when the machine has no session.
     *
     * A Mosh client sends a datagram from its new address after a network change, so that `mosh-server` learns
     * the address. This version sends no datagram (issue #22).
     */
    suspend fun sendWakeupDatagram(machineId: String): Int {
        val state = sessions[machineId] ?: return 0
        state.parked = false
        state.roamedCount += 1
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "Network change ${state.roamedCount} of the Mosh session to ${state.host}:${state.udpPort}",
        )
        synthesizeAndPublishFrame(state)
        return state.roamedCount
    }

    /**
     * Updates the [PaneLayoutSnapshot] (`pane.layout`) from the Herdr Control Sideband and re-synthesizes
     * the [PaneSurfaceFrame] with derived BSP split dividers (`deriveSplitsFromLayout`).
     */
    fun updateLayoutSnapshot(
        machineId: String,
        layout: PaneLayoutSnapshot,
        tabCount: Int = 1,
    ) {
        val state = sessions[machineId] ?: return
        state.layoutSnapshot = layout
        state.tabCount = tabCount.coerceAtLeast(1)
        synthesizeAndPublishFrame(state)
    }

    /**
     * Feeds remote terminal lines into the VT framebuffer, clears confirmed predictive echo overlays,
     * and publishes the updated [PaneSurfaceFrame] to [PaneSurfaceBus].
     */
    fun applyRemoteTerminalOutput(
        machineId: String,
        lines: List<String>,
        tabCount: Int = 1,
    ) {
        val state = sessions[machineId] ?: return
        synchronized(state) {
            state.tabCount = tabCount.coerceAtLeast(1)
            // Strip row 0 (Herdr's 1-row top tab bar) when tabCount > 1 per Part IV.1
            val effectiveLines = if (state.tabCount > 1 && lines.size > 1) {
                lines.drop(1)
            } else {
                lines
            }
            state.screenLines.clear()
            state.screenLines.addAll(effectiveLines)
            state.predictions.clear()
        }
        synthesizeAndPublishFrame(state)
    }

    override suspend fun stopSession(machineId: String) {
        sessions.remove(machineId) ?: return
        sshDebugLog.record(
            machineId = machineId,
            tag = LOG_TAG,
            message = "Stopped Mosh session for $machineId",
        )
    }

    override fun isSessionActive(machineId: String): Boolean =
        sessions.containsKey(machineId)

    fun isParked(machineId: String): Boolean =
        sessions[machineId]?.parked == true

    private fun synthesizeAndPublishFrame(state: MoshSessionState) {
        val cols: UShort
        val rows: UShort
        val width: Int
        val height: Int
        val cursorCol: Int
        val cursorRow: Int
        val layout: PaneLayoutSnapshot?
        val grid: MutableList<CellData>

        synchronized(state) {
            cols = state.cols
            rows = state.rows
            width = cols.toInt().coerceAtLeast(1)
            height = rows.toInt().coerceAtLeast(1)
            cursorCol = state.cursorCol
            cursorRow = state.cursorRow
            layout = state.layoutSnapshot
            grid = MutableList(width * height) { BLANK_CELL }

            for ((rowIdx, line) in state.screenLines.withIndex()) {
                if (rowIdx >= height) break
                for ((colIdx, ch) in line.withIndex()) {
                    if (colIdx >= width) break
                    grid[rowIdx * width + colIdx] = CellData(
                        symbol = charToSymbol(ch),
                        fg = DEFAULT_FG_COLOR,
                    )
                }
            }

            // Overlay tentative client-side predictive echo cells with underline modifier
            for (pred in state.predictions) {
                if (pred.row in 0..<height && pred.col in 0..<width) {
                    val idx = pred.row * width + pred.col
                    grid[idx] = CellData(
                        symbol = pred.symbol,
                        fg = PREDICTED_ECHO_FG_COLOR,
                        modifier = PREDICTED_ECHO_MODIFIER,
                    )
                }
            }
        }

        val splits = if (layout != null) {
            deriveSplitsFromLayout(layout)
        } else {
            emptyList()
        }
        val panes = if (layout != null && layout.panes.isNotEmpty()) {
            if (layout.zoomed) {
                val focusedPane = layout.panes.firstOrNull { it.focused } ?: layout.panes.first()
                listOf(
                    PaneSurfacePane(
                        paneId = focusedPane.paneId,
                        rect = SurfaceRect(0u, 0u, cols, rows),
                        focused = true,
                        zoomed = true,
                        title = focusedPane.paneId,
                        agentStatus = AgentStatus.Working,
                    )
                )
            } else {
                layout.panes.map(PaneLayoutPane::toSurfacePane)
            }
        } else {
            listOf(
                PaneSurfacePane(
                    paneId = "w1:p1",
                    rect = SurfaceRect(0u, 0u, cols, rows),
                    focused = true,
                    title = "${state.machineId} · mosh-ssp",
                    agentStatus = AgentStatus.Idle,
                )
            )
        }

        val rev = state.frameRevision.getAndIncrement().toULong()
        val frame = PaneSurfaceFrame(
            bootId = "mosh-${state.machineId}",
            revision = rev,
            frame = FrameData(
                width = cols,
                height = rows,
                cells = grid,
                cursor = CursorState(
                    x = cursorCol.coerceIn(0, width - 1).toUShort(),
                    y = cursorRow.coerceIn(0, height - 1).toUShort(),
                    shape = CursorShape.Block,
                    visible = true,
                ),
            ),
            panes = panes,
            splits = splits,
        )
        paneSurfaceBus.publish(state.machineId, frame)
    }

    private fun charToSymbol(ch: Char): String =
        if (ch.code in 0..<128) ASCII_SYMBOLS[ch.code] else ch.toString()

    companion object {
        /** The debug log tag of the engine. */
        const val LOG_TAG: String = "MoshUDP"

        private val BLANK_CELL = CellData()
        private val ASCII_SYMBOLS = Array(128) { it.toChar().toString() }
        private val DEFAULT_FG_COLOR = CellData.rgbColor(0xEB, 0xDB, 0xB2)
        private val PREDICTED_ECHO_FG_COLOR = CellData.rgbColor(0xD7, 0x99, 0x21)
        private val PREDICTED_ECHO_MODIFIER =
            (CellData.MOD_UNDERLINE.toInt() or CellData.MOD_PREDICTED_ECHO.toInt()).toUShort()
    }
}

private fun PaneLayoutPane.toSurfacePane(): PaneSurfacePane =
    PaneSurfacePane(
        paneId = paneId,
        rect = rect,
        innerRect = rect,
        focused = focused,
        title = paneId,
    )
