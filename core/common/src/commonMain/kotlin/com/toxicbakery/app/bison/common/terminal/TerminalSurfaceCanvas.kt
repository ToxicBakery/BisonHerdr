package com.toxicbakery.app.bison.common.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toxicbakery.app.bison.common.theme.BisonThemePalette
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.CursorShape
import com.toxicbakery.app.bison.protocol.FrameData
import com.toxicbakery.app.bison.protocol.PaneSurfaceFrame
import com.toxicbakery.app.bison.protocol.PaneSurfacePane
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplit
import com.toxicbakery.app.bison.protocol.PaneSurfaceSplitDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coordinates of a long-press text selection inside a terminal pane.
 */
data class TerminalSelectionRange(
    val paneId: String,
    val startCol: Int,
    val startRow: Int,
    val endCol: Int,
    val endRow: Int,
)

/**
 * 120Hz Hardware-Accelerated Compose Terminal Renderer ("Zen Canvas").
 *
 * Renders [PaneSurfaceFrame] edge-to-edge across 96%+ of the tablet display with:
 * - TrueColor (`0x02_RR_GG_BB`), 256-color (`0x01_00_00_XX`), and 16-ANSI color resolution
 * - Bold, italic, dim, reverse, underline, curly underline, strikethrough, and Mosh predictive echo
 * - Block, Underline, and Bar cursors
 * - Crisp `2.dp` accent border on the focused pane and per-pane scrollbars
 * - Interactive `24.dp` BSP split handles (`PaneSurfaceFrame.splits`) with magnetic snapping
 *   (25% / 33% / 50% / 67% / 75%), live preview line, and double-tap 50% equalize
 * - Touch gestures: tap to focus, double-tap to toggle single-pane zoom, two-finger pinch font size,
 *   vertical inertial scrollback, and long-press text selection overlay.
 */
@Composable
fun TerminalSurfaceCanvas(
    surfaceFrame: PaneSurfaceFrame?,
    fontSizeSp: Float,
    predictiveEchoUnderlines: Boolean = true,
    onPaneFocus: (paneId: String) -> Unit = {},
    onPaneZoomToggle: (paneId: String) -> Unit = {},
    onSplitRatioChanged: (path: List<Boolean>, ratio: Float) -> Unit = { _, _ -> },
    onFontSizeChange: (newFontSizeSp: Float) -> Unit = {},
    onSurfaceResize: (cols: UShort, rows: UShort, cellWidthPx: Int, cellHeightPx: Int) -> Unit = { _, _, _, _ -> },
    onPaneScroll: (paneId: String, offsetFromBottom: Int) -> Unit = { _, _ -> },
    onSelectionChanged: (TerminalSelectionRange?) -> Unit = {},
    onCopySelectionRequest: (TerminalSelectionRange) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 512)

    val baseTextStyle = remember(fontSizeSp, palette.text) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            color = palette.text,
            lineHeight = (fontSizeSp * 1.28f).sp,
        )
    }

    val measuredCell = remember(baseTextStyle, density) {
        val result = textMeasurer.measure("M", style = baseTextStyle)
        val w = max(result.size.width, 8)
        val h = max(result.size.height, 14)
        w to h
    }
    val cellWidthPx = measuredCell.first
    val cellHeightPx = measuredCell.second

    var activeDragSplit by remember { mutableStateOf<PaneSurfaceSplit?>(null) }
    var activeDragRatio by remember { mutableFloatStateOf(0.5f) }
    var activeSelection by remember { mutableStateOf<TerminalSelectionRange?>(null) }
    var scrollAccumulatorPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.terminalBg)
            .testTag("terminalSurfaceCanvas")
    ) {
        val canvasWidthPx = with(density) { maxWidth.toPx() }
        val canvasHeightPx = with(density) { maxHeight.toPx() }

        val computedCols = max(20, (canvasWidthPx / cellWidthPx).toInt()).toUShort()
        val computedRows = max(8, (canvasHeightPx / cellHeightPx).toInt()).toUShort()

        LaunchedEffect(computedCols, computedRows, cellWidthPx, cellHeightPx) {
            onSurfaceResize(computedCols, computedRows, cellWidthPx, cellHeightPx)
        }

        val frameCols = (surfaceFrame?.cols?.toInt()?.takeIf { it > 0 } ?: computedCols.toInt())
        val frameRows = (surfaceFrame?.rows?.toInt()?.takeIf { it > 0 } ?: computedRows.toInt())
        val effectiveCellWidth = if (frameCols > 0) canvasWidthPx / frameCols else cellWidthPx.toFloat()
        val effectiveCellHeight = if (frameRows > 0) canvasHeightPx / frameRows else cellHeightPx.toFloat()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(surfaceFrame, effectiveCellWidth, effectiveCellHeight) {
                    detectTapGestures(
                        onTap = { offset ->
                            activeSelection = null
                            onSelectionChanged(null)
                            val col = (offset.x / effectiveCellWidth).toInt()
                            val row = (offset.y / effectiveCellHeight).toInt()
                            val tappedPane = surfaceFrame?.panes?.firstOrNull { pane ->
                                pane.rect.contains(col, row)
                            }
                            if (tappedPane != null && !tappedPane.focused) {
                                onPaneFocus(tappedPane.paneId)
                            }
                        },
                        onDoubleTap = { offset ->
                            val col = (offset.x / effectiveCellWidth).toInt()
                            val row = (offset.y / effectiveCellHeight).toInt()
                            val tappedPane = surfaceFrame?.panes?.firstOrNull { pane ->
                                pane.rect.contains(col, row)
                            } ?: surfaceFrame?.panes?.firstOrNull { it.focused }
                            if (tappedPane != null) {
                                onPaneZoomToggle(tappedPane.paneId)
                            }
                        },
                        onLongPress = { offset ->
                            val col = (offset.x / effectiveCellWidth).toInt().coerceIn(0, frameCols - 1)
                            val row = (offset.y / effectiveCellHeight).toInt().coerceIn(0, frameRows - 1)
                            val pane = surfaceFrame?.panes?.firstOrNull { it.rect.contains(col, row) }
                                ?: surfaceFrame?.panes?.firstOrNull { it.focused }
                            if (pane != null) {
                                val startCol = max(pane.innerRect.x.toInt(), col - 4)
                                val endCol = min(
                                    (pane.innerRect.x + pane.innerRect.width).toInt() - 1,
                                    col + 8,
                                )
                                val selection = TerminalSelectionRange(
                                    paneId = pane.paneId,
                                    startCol = startCol,
                                    startRow = row,
                                    endCol = max(startCol, endCol),
                                    endRow = row,
                                )
                                activeSelection = selection
                                onSelectionChanged(selection)
                            }
                        },
                    )
                }
                .pointerInput(surfaceFrame, fontSizeSp, effectiveCellHeight) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (abs(zoom - 1f) > 0.025f) {
                            val updatedFontSize = (fontSizeSp * zoom).coerceIn(8f, 28f)
                            if (abs(updatedFontSize - fontSizeSp) >= 0.25f) {
                                onFontSizeChange(updatedFontSize)
                            }
                        } else if (abs(pan.y) > abs(pan.x) && abs(pan.y) > 2f) {
                            scrollAccumulatorPx += pan.y
                            val lineDelta = (scrollAccumulatorPx / effectiveCellHeight).toInt()
                            if (lineDelta != 0) {
                                scrollAccumulatorPx -= lineDelta * effectiveCellHeight
                                val col = (centroid.x / effectiveCellWidth).toInt()
                                val row = (centroid.y / effectiveCellHeight).toInt()
                                val targetPane = surfaceFrame?.panes?.firstOrNull { it.rect.contains(col, row) }
                                    ?: surfaceFrame?.panes?.firstOrNull { it.focused }
                                if (targetPane != null) {
                                    val currentOffset = targetPane.scroll.offsetFromBottom.toInt()
                                    val nextOffset = (currentOffset + lineDelta).coerceAtLeast(0)
                                    onPaneScroll(targetPane.paneId, nextOffset)
                                }
                            }
                        }
                    }
                }
        ) {
            drawRect(color = palette.terminalBg, size = size)

            if (surfaceFrame != null) {
                drawFrameCells(
                    frame = surfaceFrame.frame,
                    cellWidth = effectiveCellWidth,
                    cellHeight = effectiveCellHeight,
                    baseTextStyle = baseTextStyle,
                    textMeasurer = textMeasurer,
                    palette = palette,
                    predictiveEchoUnderlines = predictiveEchoUnderlines,
                )

                val selection = activeSelection
                if (selection != null) {
                    drawSelectionHighlight(
                        selection = selection,
                        cellWidth = effectiveCellWidth,
                        cellHeight = effectiveCellHeight,
                        palette = palette,
                    )
                }

                surfaceFrame.panes.forEach { pane ->
                    drawPaneChrome(
                        pane = pane,
                        totalPanes = surfaceFrame.panes.size,
                        cellWidth = effectiveCellWidth,
                        cellHeight = effectiveCellHeight,
                        baseTextStyle = baseTextStyle,
                        textMeasurer = textMeasurer,
                        palette = palette,
                    )
                }

                surfaceFrame.frame.cursor?.let { cursor ->
                    if (cursor.visible) {
                        drawCursor(
                            col = cursor.x.toInt(),
                            row = cursor.y.toInt(),
                            shape = cursor.shape,
                            cellWidth = effectiveCellWidth,
                            cellHeight = effectiveCellHeight,
                            palette = palette,
                        )
                    }
                }

                surfaceFrame.popup?.let { popup ->
                    drawPopupOverlay(
                        popup = popup,
                        cellWidth = effectiveCellWidth,
                        cellHeight = effectiveCellHeight,
                        baseTextStyle = baseTextStyle,
                        textMeasurer = textMeasurer,
                        palette = palette,
                    )
                }

                val draggingSplit = activeDragSplit
                if (draggingSplit != null) {
                    drawSplitDragPreview(
                        split = draggingSplit,
                        ratio = activeDragRatio,
                        cellWidth = effectiveCellWidth,
                        cellHeight = effectiveCellHeight,
                        palette = palette,
                    )
                }
            }
        }

        // Interactive 24.dp BSP Split Handles
        surfaceFrame?.splits?.forEach { split ->
            val touchTargetPx = with(density) { 24.dp.toPx() }
            val hitX = split.hitRect.x.toInt() * effectiveCellWidth
            val hitY = split.hitRect.y.toInt() * effectiveCellHeight
            val hitW = max(split.hitRect.width.toInt() * effectiveCellWidth, effectiveCellWidth)
            val hitH = max(split.hitRect.height.toInt() * effectiveCellHeight, effectiveCellHeight)

            val boxLeftPx = if (split.direction == PaneSurfaceSplitDirection.Vertical) {
                hitX + (hitW / 2f) - (touchTargetPx / 2f)
            } else {
                hitX
            }
            val boxTopPx = if (split.direction == PaneSurfaceSplitDirection.Horizontal) {
                hitY + (hitH / 2f) - (touchTargetPx / 2f)
            } else {
                hitY
            }
            val boxWidthDp = with(density) {
                if (split.direction == PaneSurfaceSplitDirection.Vertical) 24.dp else hitW.toDp()
            }
            val boxHeightDp = with(density) {
                if (split.direction == PaneSurfaceSplitDirection.Horizontal) 24.dp else hitH.toDp()
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(boxLeftPx.roundToInt(), boxTopPx.roundToInt()) }
                    .size(width = boxWidthDp, height = boxHeightDp)
                    .testTag("splitHandle_${split.id}")
                    .pointerInput(split, effectiveCellWidth, effectiveCellHeight) {
                        detectTapGestures(
                            onDoubleTap = {
                                onSplitRatioChanged(split.path, 0.5f)
                            }
                        )
                    }
                    .pointerInput(split, effectiveCellWidth, effectiveCellHeight) {
                        var currentRatio = split.ratio
                        detectDragGestures(
                            onDragStart = {
                                currentRatio = split.ratio
                                activeDragSplit = split
                                activeDragRatio = currentRatio
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val workspaceSpanPx = if (split.direction == PaneSurfaceSplitDirection.Vertical) {
                                    max(1f, split.workspaceRect.width.toInt() * effectiveCellWidth)
                                } else {
                                    max(1f, split.workspaceRect.height.toInt() * effectiveCellHeight)
                                }
                                val deltaRatio = if (split.direction == PaneSurfaceSplitDirection.Vertical) {
                                    dragAmount.x / workspaceSpanPx
                                } else {
                                    dragAmount.y / workspaceSpanPx
                                }
                                currentRatio = (currentRatio + deltaRatio).coerceIn(0.10f, 0.90f)
                                activeDragRatio = snapSplitRatio(currentRatio)
                            },
                            onDragEnd = {
                                val finalRatio = snapSplitRatio(currentRatio)
                                activeDragSplit = null
                                onSplitRatioChanged(split.path, finalRatio)
                            },
                            onDragCancel = {
                                activeDragSplit = null
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Pill grabber visual cue on the divider
                Box(
                    modifier = Modifier
                        .size(
                            width = if (split.direction == PaneSurfaceSplitDirection.Vertical) 4.dp else 32.dp,
                            height = if (split.direction == PaneSurfaceSplitDirection.Vertical) 32.dp else 4.dp,
                        )
                        .background(
                            color = palette.accent.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(2.dp),
                        )
                )
            }
        }

        // Long-press selection action pill ("Copy Selection" / "Clear")
        val currentSelection = activeSelection
        if (currentSelection != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp),
                shape = RoundedCornerShape(20.dp),
                color = palette.panelBg.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier
                        .border(1.dp, palette.accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Selection (${currentSelection.paneId})",
                        style = baseTextStyle.copy(fontSize = 12.sp, color = palette.text),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Copy",
                        style = baseTextStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.accent,
                        ),
                        modifier = Modifier.pointerInput(currentSelection) {
                            detectTapGestures {
                                onCopySelectionRequest(currentSelection)
                                activeSelection = null
                                onSelectionChanged(null)
                            }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "✕",
                        style = baseTextStyle.copy(fontSize = 12.sp, color = palette.red),
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures {
                                activeSelection = null
                                onSelectionChanged(null)
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawFrameCells(
    frame: FrameData,
    cellWidth: Float,
    cellHeight: Float,
    baseTextStyle: TextStyle,
    textMeasurer: TextMeasurer,
    palette: BisonThemePalette,
    predictiveEchoUnderlines: Boolean,
) {
    val cols = frame.width.toInt()
    val rows = frame.height.toInt()
    if (cols <= 0 || rows <= 0 || frame.cells.isEmpty()) return

    // Pass 1: Draw non-default cell backgrounds and contiguous runs of characters per row
    for (row in 0 until rows) {
        val yPx = row * cellHeight
        if (yPx > size.height) break
        var col = 0
        while (col < cols) {
            val cell = frame.cellAt(col, row)
            if (cell == null || cell.skip) {
                col++
                continue
            }

            val rawFg = TerminalColorResolver.resolveForeground(cell.fg, palette, isDim = cell.isDim)
            val rawBg = TerminalColorResolver.resolveBackground(cell.bg, palette)
            val fgColor = if (cell.isReverse) rawBg else rawFg
            val bgColor = if (cell.isReverse) rawFg else rawBg

            // Group contiguous cells on the same row that share identical styling to minimize draw calls
            var runEnd = col + 1
            val runBuilder = StringBuilder(cell.symbol.ifEmpty { " " })
            while (runEnd < cols) {
                val next = frame.cellAt(runEnd, row) ?: break
                if (next.skip) break
                if (next.fg != cell.fg || next.bg != cell.bg || next.modifier != cell.modifier) break
                runBuilder.append(next.symbol.ifEmpty { " " })
                runEnd++
            }

            val runCols = runEnd - col
            val xPx = col * cellWidth
            val runWidthPx = runCols * cellWidth

            if (bgColor != palette.terminalBg) {
                drawRect(
                    color = bgColor,
                    topLeft = Offset(xPx, yPx),
                    size = Size(runWidthPx + 0.5f, cellHeight + 0.5f),
                )
            }

            val runText = runBuilder.toString()
            if (runText.isNotBlank()) {
                val decorations = buildList {
                    if (cell.isUnderline || (predictiveEchoUnderlines && cell.isPredictedEcho)) {
                        add(TextDecoration.Underline)
                    }
                    if (cell.isStrikethrough) {
                        add(TextDecoration.LineThrough)
                    }
                }
                val style = baseTextStyle.copy(
                    color = fgColor,
                    fontWeight = if (cell.isBold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (cell.isItalic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (decorations.isEmpty()) {
                        TextDecoration.None
                    } else {
                        TextDecoration.combine(decorations)
                    },
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = runText,
                    topLeft = Offset(xPx, yPx),
                    style = style,
                    softWrap = false,
                )
            }

            if (cell.isCurlyUnderline) {
                drawCurlyUnderline(
                    startX = xPx,
                    endX = xPx + runWidthPx,
                    baselineY = yPx + cellHeight - 2.dp.toPx(),
                    color = palette.red,
                )
            }

            col = runEnd
        }
    }
}

private fun DrawScope.drawCurlyUnderline(
    startX: Float,
    endX: Float,
    baselineY: Float,
    color: Color,
) {
    val waveLength = 6.dp.toPx().coerceAtLeast(4f)
    val amplitude = 1.5.dp.toPx()
    val path = Path().apply {
        moveTo(startX, baselineY)
        var x = startX
        var up = true
        while (x < endX) {
            val nextX = min(x + waveLength / 2f, endX)
            val midX = (x + nextX) / 2f
            val controlY = if (up) baselineY - amplitude else baselineY + amplitude
            quadraticTo(midX, controlY, nextX, baselineY)
            x = nextX
            up = !up
        }
    }
    drawPath(path = path, color = color, style = Stroke(width = 1.2.dp.toPx()))
}

private fun DrawScope.drawPaneChrome(
    pane: PaneSurfacePane,
    totalPanes: Int,
    cellWidth: Float,
    cellHeight: Float,
    baseTextStyle: TextStyle,
    textMeasurer: TextMeasurer,
    palette: BisonThemePalette,
) {
    val left = pane.rect.x.toInt() * cellWidth
    val top = pane.rect.y.toInt() * cellHeight
    val width = pane.rect.width.toInt() * cellWidth
    val height = pane.rect.height.toInt() * cellHeight

    val borderColor = when {
        pane.agentStatus == AgentStatus.Blocked -> palette.red
        pane.focused -> palette.accent
        else -> palette.text.copy(alpha = 0.18f)
    }
    val strokeWidth = if (pane.focused || pane.agentStatus == AgentStatus.Blocked) {
        2.dp.toPx()
    } else {
        1.dp.toPx()
    }

    drawRect(
        color = borderColor,
        topLeft = Offset(left + strokeWidth / 2f, top + strokeWidth / 2f),
        size = Size(max(0f, width - strokeWidth), max(0f, height - strokeWidth)),
        style = Stroke(width = strokeWidth),
    )

    // Draw subtle pane badge when split into multiple panes or when an agent is active
    if (totalPanes > 1 || pane.title.isNotBlank()) {
        val statusPrefix = when (pane.agentStatus) {
            AgentStatus.Blocked -> "🔴 "
            AgentStatus.Working -> "🟠 "
            AgentStatus.Done -> "🟢 "
            else -> ""
        }
        val badgeText = buildString {
            append(statusPrefix)
            append(pane.title.ifBlank { pane.paneId })
            if (pane.zoomed) append(" [ZOOM]")
        }
        val badgeStyle = baseTextStyle.copy(
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (pane.focused) palette.terminalBg else palette.text,
        )
        val measured = textMeasurer.measure(badgeText, style = badgeStyle)
        val padH = 6.dp.toPx()
        val padV = 2.dp.toPx()
        val badgeW = measured.size.width + padH * 2
        val badgeH = measured.size.height + padV * 2
        val badgeX = (left + width - badgeW - 8.dp.toPx()).coerceAtLeast(left + 4.dp.toPx())
        val badgeY = top + 4.dp.toPx()

        drawRoundRect(
            color = if (pane.focused) borderColor else palette.panelBg.copy(alpha = 0.88f),
            topLeft = Offset(badgeX, badgeY),
            size = Size(badgeW, badgeH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(badgeX + padH, badgeY + padV),
        )
    }

    // Per-pane scrollbar when scrollback exists
    val maxScrollback = pane.scroll.maxScrollback.toInt()
    val offsetFromBottom = pane.scroll.offsetFromBottom.toInt()
    if (maxScrollback > 0 || offsetFromBottom > 0) {
        val viewportRows = max(1, pane.scroll.viewportRows.toInt().takeIf { it > 0 } ?: pane.rect.height.toInt())
        val totalRows = max(viewportRows + 1, maxScrollback + viewportRows)
        val thumbFraction = (viewportRows.toFloat() / totalRows.toFloat()).coerceIn(0.1f, 0.9f)
        val thumbHeight = height * thumbFraction
        val scrollFraction = (offsetFromBottom.toFloat() / max(1, maxScrollback).toFloat()).coerceIn(0f, 1f)
        val thumbTop = top + (height - thumbHeight) * (1f - scrollFraction)
        val barWidth = 3.dp.toPx()
        drawRoundRect(
            color = palette.accent.copy(alpha = 0.65f),
            topLeft = Offset(left + width - barWidth - 2.dp.toPx(), thumbTop),
            size = Size(barWidth, thumbHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
        )
    }
}

private fun DrawScope.drawCursor(
    col: Int,
    row: Int,
    shape: CursorShape,
    cellWidth: Float,
    cellHeight: Float,
    palette: BisonThemePalette,
) {
    val x = col * cellWidth
    val y = row * cellHeight
    when (shape) {
        CursorShape.Block -> {
            drawRect(
                color = palette.accent.copy(alpha = 0.72f),
                topLeft = Offset(x, y),
                size = Size(cellWidth, cellHeight),
            )
        }
        CursorShape.Underline -> {
            val h = max(2.dp.toPx(), cellHeight * 0.14f)
            drawRect(
                color = palette.accent,
                topLeft = Offset(x, y + cellHeight - h),
                size = Size(cellWidth, h),
            )
        }
        CursorShape.Bar -> {
            val w = max(2.dp.toPx(), cellWidth * 0.16f)
            drawRect(
                color = palette.accent,
                topLeft = Offset(x, y),
                size = Size(w, cellHeight),
            )
        }
    }
}

private fun DrawScope.drawSelectionHighlight(
    selection: TerminalSelectionRange,
    cellWidth: Float,
    cellHeight: Float,
    palette: BisonThemePalette,
) {
    val minRow = min(selection.startRow, selection.endRow)
    val maxRow = max(selection.startRow, selection.endRow)
    val minCol = min(selection.startCol, selection.endCol)
    val maxCol = max(selection.startCol, selection.endCol)
    for (r in minRow..maxRow) {
        val left = minCol * cellWidth
        val top = r * cellHeight
        val width = (maxCol - minCol + 1) * cellWidth
        drawRect(
            color = palette.accent.copy(alpha = 0.28f),
            topLeft = Offset(left, top),
            size = Size(width, cellHeight),
        )
    }
}

private fun DrawScope.drawPopupOverlay(
    popup: com.toxicbakery.app.bison.protocol.ClientShellPopupSurface,
    cellWidth: Float,
    cellHeight: Float,
    baseTextStyle: TextStyle,
    textMeasurer: TextMeasurer,
    palette: BisonThemePalette,
) {
    val left = popup.rect.x.toInt() * cellWidth
    val top = popup.rect.y.toInt() * cellHeight
    val width = popup.rect.width.toInt() * cellWidth
    val height = popup.rect.height.toInt() * cellHeight

    drawRect(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = Offset.Zero,
        size = size,
    )
    drawRoundRect(
        color = palette.panelBg,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
    )
    drawRoundRect(
        color = palette.accent,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
        style = Stroke(width = 2.dp.toPx()),
    )
    if (popup.title.isNotBlank()) {
        drawText(
            textMeasurer = textMeasurer,
            text = popup.title,
            topLeft = Offset(left + 8.dp.toPx(), top + 4.dp.toPx()),
            style = baseTextStyle.copy(fontWeight = FontWeight.Bold, color = palette.accent),
        )
    }
}

private fun DrawScope.drawSplitDragPreview(
    split: PaneSurfaceSplit,
    ratio: Float,
    cellWidth: Float,
    cellHeight: Float,
    palette: BisonThemePalette,
) {
    val wsLeft = split.workspaceRect.x.toInt() * cellWidth
    val wsTop = split.workspaceRect.y.toInt() * cellHeight
    val wsWidth = split.workspaceRect.width.toInt() * cellWidth
    val wsHeight = split.workspaceRect.height.toInt() * cellHeight

    if (split.direction == PaneSurfaceSplitDirection.Vertical) {
        val x = wsLeft + wsWidth * ratio
        drawLine(
            color = palette.accent,
            start = Offset(x, wsTop),
            end = Offset(x, wsTop + wsHeight),
            strokeWidth = 3.dp.toPx(),
        )
    } else {
        val y = wsTop + wsHeight * ratio
        drawLine(
            color = palette.accent,
            start = Offset(wsLeft, y),
            end = Offset(wsLeft + wsWidth, y),
            strokeWidth = 3.dp.toPx(),
        )
    }
}
