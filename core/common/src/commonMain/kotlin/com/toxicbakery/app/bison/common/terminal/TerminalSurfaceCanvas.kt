package com.toxicbakery.app.bison.common.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toxicbakery.app.bison.common.theme.BisonThemePalette
import com.toxicbakery.app.bison.common.theme.LocalBisonPalette
import com.toxicbakery.app.bison.protocol.AgentStatus
import com.toxicbakery.app.bison.protocol.CellData
import com.toxicbakery.app.bison.protocol.ClientKeyCode
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
@Immutable
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
    keyboardTriggerCount: Int = 0,
    onPaneFocus: (paneId: String) -> Unit = {},
    onPaneZoomToggle: (paneId: String) -> Unit = {},
    onSplitRatioChanged: (path: List<Boolean>, ratio: Float) -> Unit = { _, _ -> },
    onFontSizeChange: (newFontSizeSp: Float) -> Unit = {},
    onSurfaceResize: (cols: UShort, rows: UShort, cellWidthPx: Int, cellHeightPx: Int) -> Unit = { _, _, _, _ -> },
    onPaneScroll: (paneId: String, offsetFromBottom: Int) -> Unit = { _, _ -> },
    onSelectionChanged: (TerminalSelectionRange?) -> Unit = {},
    onCopySelectionRequest: (TerminalSelectionRange) -> Unit = {},
    onTextInput: (text: String) -> Unit = {},
    onSpecialKey: (keyCode: ClientKeyCode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalBisonPalette.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 2048)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeState = rememberTextFieldState(initialText = " ", initialSelection = TextRange(1))

    var liveFontSizeSp by remember { mutableFloatStateOf(fontSizeSp.coerceIn(7f, 28f)) }
    var lastDispatchedFontSizeSp by remember { mutableFloatStateOf(fontSizeSp.coerceIn(7f, 28f)) }
    var zoomIndicatorPulse by remember { mutableIntStateOf(0) }
    var showZoomIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(fontSizeSp) {
        if (abs(fontSizeSp - lastDispatchedFontSizeSp) > 0.35f) {
            liveFontSizeSp = fontSizeSp.coerceIn(7f, 28f)
            lastDispatchedFontSizeSp = liveFontSizeSp
        }
    }

    LaunchedEffect(zoomIndicatorPulse) {
        if (zoomIndicatorPulse > 0) {
            showZoomIndicator = true
            delay(1_400L)
            showZoomIndicator = false
        }
    }

    LaunchedEffect(keyboardTriggerCount) {
        if (keyboardTriggerCount > 0) {
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val baseTextStyle = remember(liveFontSizeSp, palette.text) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = liveFontSizeSp.sp,
            color = palette.text,
            lineHeight = liveFontSizeSp.sp,
        )
    }

    val measuredCell = remember(baseTextStyle, density) {
        val result = textMeasurer.measure("M", style = baseTextStyle)
        val w = max(result.size.width, 6)
        val h = max(result.size.height, 10)
        w to h
    }
    val cellWidthPx = measuredCell.first
    val cellHeightPx = measuredCell.second

    var activeDragSplit by remember { mutableStateOf<PaneSurfaceSplit?>(null) }
    var activeDragRatio by remember { mutableFloatStateOf(0.5f) }
    var activeSelection by remember { mutableStateOf<TerminalSelectionRange?>(null) }
    var scrollAccumulatorPx by remember { mutableFloatStateOf(0f) }
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.terminalBg)
            .onSizeChanged { canvasSizePx = it }
            .testTag("terminalSurfaceCanvas")
    ) {
        val canvasWidthPx = (if (canvasSizePx.width > 0) canvasSizePx.width else 1920).toFloat()
        val canvasHeightPx = (if (canvasSizePx.height > 0) canvasSizePx.height else 1200).toFloat()

        val computedCols = max(20, (canvasWidthPx / cellWidthPx).toInt()).toUShort()
        val computedRows = max(8, (canvasHeightPx / cellHeightPx).toInt()).toUShort()

        LaunchedEffect(computedCols, computedRows, cellWidthPx, cellHeightPx, canvasSizePx) {
            if (canvasSizePx.width > 0 && canvasSizePx.height > 0) {
                onSurfaceResize(computedCols, computedRows, cellWidthPx, cellHeightPx)
            }
        }

        val frameCols = (surfaceFrame?.cols?.toInt()?.takeIf { it > 0 } ?: computedCols.toInt())
        val frameRows = (surfaceFrame?.rows?.toInt()?.takeIf { it > 0 } ?: computedRows.toInt())
        val effectiveCellWidth = if (frameCols > 0) canvasWidthPx / frameCols else cellWidthPx.toFloat()
        val effectiveCellHeight = if (frameRows > 0) canvasHeightPx / frameRows else cellHeightPx.toFloat()

        // Scale the monospace font size to fit strictly inside (effectiveCellWidth x effectiveCellHeight)
        // so rows and columns never overlap regardless of the remote pane's column/row dimensions.
        val renderTextStyle = remember(
            liveFontSizeSp,
            effectiveCellWidth,
            effectiveCellHeight,
            cellWidthPx,
            cellHeightPx,
        ) {
            val widthScale = (effectiveCellWidth * 0.98f) / cellWidthPx.toFloat()
            val heightScale = (effectiveCellHeight * 1.18f) / cellHeightPx.toFloat()
            val scale = min(widthScale, heightScale).coerceIn(0.25f, 3.0f)
            val scaledSp = (liveFontSizeSp * scale).coerceIn(4.5f, 40f)
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = scaledSp.sp,
                color = Color.Unspecified,
                lineHeight = scaledSp.sp,
                textDecoration = TextDecoration.None,
            )
        }
        val normalStyle = remember(renderTextStyle) {
            renderTextStyle.copy(
                color = Color.Unspecified,
                fontWeight = FontWeight.Normal,
                fontStyle = FontStyle.Normal,
                textDecoration = TextDecoration.None,
            )
        }
        val boldStyle = remember(renderTextStyle) {
            renderTextStyle.copy(
                color = Color.Unspecified,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Normal,
                textDecoration = TextDecoration.None,
            )
        }
        val italicStyle = remember(renderTextStyle) {
            renderTextStyle.copy(
                color = Color.Unspecified,
                fontWeight = FontWeight.Normal,
                fontStyle = FontStyle.Italic,
                textDecoration = TextDecoration.None,
            )
        }
        val boldItalicStyle = remember(renderTextStyle) {
            renderTextStyle.copy(
                color = Color.Unspecified,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                textDecoration = TextDecoration.None,
            )
        }
        val asciiGlyphCache = remember(normalStyle, boldStyle, italicStyle, boldItalicStyle, textMeasurer, density) {
            AsciiGlyphCache.build(
                textMeasurer = textMeasurer,
                normalStyle = normalStyle,
                boldStyle = boldStyle,
                italicStyle = italicStyle,
                boldItalicStyle = boldItalicStyle,
            )
        }
        val scratchPath = remember { Path() }

        val currentSurfaceFrame by rememberUpdatedState(surfaceFrame)
        val currentEffectiveCellWidth by rememberUpdatedState(effectiveCellWidth)
        val currentEffectiveCellHeight by rememberUpdatedState(effectiveCellHeight)
        val currentFrameCols by rememberUpdatedState(frameCols)
        val currentFrameRows by rememberUpdatedState(frameRows)
        val currentOnPaneFocus by rememberUpdatedState(onPaneFocus)
        val currentOnPaneZoomToggle by rememberUpdatedState(onPaneZoomToggle)
        val currentOnFontSizeChange by rememberUpdatedState(onFontSizeChange)
        val currentOnPaneScroll by rememberUpdatedState(onPaneScroll)
        val currentOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
        val currentOnTextInput by rememberUpdatedState(onTextInput)
        val currentOnSpecialKey by rememberUpdatedState(onSpecialKey)

        // Hidden IME receiver so tapping the terminal or pressing keys types directly into the focused pane
        BasicTextField(
            state = imeState,
            inputTransformation = {
                val raw = asCharSequence().toString()
                when {
                    raw.isEmpty() -> {
                        currentOnSpecialKey(ClientKeyCode.Backspace)
                    }
                    raw.startsWith(" ") && raw.length > 1 -> {
                        val delta = raw.substring(1)
                        if (delta == "\n" || delta == "\r") {
                            currentOnSpecialKey(ClientKeyCode.Enter)
                        } else {
                            currentOnTextInput(delta)
                        }
                    }
                    raw != " " -> {
                        if (raw == "\n" || raw == "\r") {
                            currentOnSpecialKey(ClientKeyCode.Enter)
                        } else {
                            currentOnTextInput(raw)
                        }
                    }
                }
                replace(0, length, " ")
                placeCursorAfterCharAt(0)
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            onKeyboardAction = {
                currentOnSpecialKey(ClientKeyCode.Enter)
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester)
                .testTag("terminalImeInput")
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                onSpecialKey(ClientKeyCode.Enter)
                                true
                            }
                            Key.Backspace -> {
                                onSpecialKey(ClientKeyCode.Backspace)
                                true
                            }
                            Key.Delete -> {
                                onSpecialKey(ClientKeyCode.Delete)
                                true
                            }
                            Key.Tab -> {
                                onSpecialKey(ClientKeyCode.Tab)
                                true
                            }
                            Key.Escape -> {
                                onSpecialKey(ClientKeyCode.Escape)
                                true
                            }
                            Key.DirectionUp -> {
                                onSpecialKey(ClientKeyCode.Up)
                                true
                            }
                            Key.DirectionDown -> {
                                onSpecialKey(ClientKeyCode.Down)
                                true
                            }
                            Key.DirectionLeft -> {
                                onSpecialKey(ClientKeyCode.Left)
                                true
                            }
                            Key.DirectionRight -> {
                                onSpecialKey(ClientKeyCode.Right)
                                true
                            }
                            Key.MoveHome -> {
                                onSpecialKey(ClientKeyCode.Home)
                                true
                            }
                            Key.MoveEnd -> {
                                onSpecialKey(ClientKeyCode.End)
                                true
                            }
                            Key.PageUp -> {
                                onSpecialKey(ClientKeyCode.PageUp)
                                true
                            }
                            Key.PageDown -> {
                                onSpecialKey(ClientKeyCode.PageDown)
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            activeSelection = null
                            currentOnSelectionChanged(null)
                            val col = (offset.x / currentEffectiveCellWidth).toInt()
                            val row = (offset.y / currentEffectiveCellHeight).toInt()
                            val tappedPane = currentSurfaceFrame?.panes?.firstOrNull { pane ->
                                pane.rect.contains(col, row)
                            }
                            if (tappedPane != null && !tappedPane.focused) {
                                currentOnPaneFocus(tappedPane.paneId)
                            }
                            runCatching { focusRequester.requestFocus() }
                            keyboardController?.show()
                        },
                        onDoubleTap = { offset ->
                            val col = (offset.x / currentEffectiveCellWidth).toInt()
                            val row = (offset.y / currentEffectiveCellHeight).toInt()
                            val tappedPane = currentSurfaceFrame?.panes?.firstOrNull { pane ->
                                pane.rect.contains(col, row)
                            } ?: currentSurfaceFrame?.panes?.firstOrNull { it.focused }
                            if (tappedPane != null) {
                                currentOnPaneZoomToggle(tappedPane.paneId)
                            }
                        },
                        onLongPress = { offset ->
                            val col = (offset.x / currentEffectiveCellWidth).toInt().coerceIn(0, currentFrameCols - 1)
                            val row = (offset.y / currentEffectiveCellHeight).toInt().coerceIn(0, currentFrameRows - 1)
                            val pane = currentSurfaceFrame?.panes?.firstOrNull { it.rect.contains(col, row) }
                                ?: currentSurfaceFrame?.panes?.firstOrNull { it.focused }
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
                                currentOnSelectionChanged(selection)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var isPinching = false
                        // One drag scrolls one pane. The offset starts from the pane offset at the
                        // first scroll step and then follows the finger, so that each step adds to it.
                        var dragScrollPaneId: String? = null
                        var dragScrollOffset = 0
                        var dragScrollMax = 0
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2) {
                                isPinching = true
                                showZoomIndicator = true
                                val zoomChange = event.calculateZoom()
                                if (abs(zoomChange - 1f) > 0.002f) {
                                    liveFontSizeSp = (liveFontSizeSp * zoomChange).coerceIn(7f, 28f)
                                }
                                event.changes.forEach { it.consume() }
                            } else if (!isPinching && pressedCount == 1) {
                                val pan = event.calculatePan()
                                if (abs(pan.y) > abs(pan.x) && abs(pan.y) > 3f) {
                                    scrollAccumulatorPx += pan.y
                                    val cellH = currentEffectiveCellHeight.coerceAtLeast(8f)
                                    val lineDelta = (scrollAccumulatorPx / cellH).toInt()
                                    if (lineDelta != 0) {
                                        scrollAccumulatorPx -= lineDelta * cellH
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val col = (centroid.x / currentEffectiveCellWidth).toInt()
                                        val row = (centroid.y / cellH).toInt()
                                        if (dragScrollPaneId == null) {
                                            val targetPane = currentSurfaceFrame?.panes?.firstOrNull { it.rect.contains(col, row) }
                                                ?: currentSurfaceFrame?.panes?.firstOrNull { it.focused }
                                            if (targetPane != null) {
                                                dragScrollPaneId = targetPane.paneId
                                                dragScrollOffset = targetPane.scroll.offsetFromBottom.toInt()
                                                dragScrollMax = targetPane.scroll.maxScrollback.toInt()
                                            }
                                        }
                                        val scrollPaneId = dragScrollPaneId
                                        if (scrollPaneId != null) {
                                            val nextOffset = nextPaneScrollOffset(dragScrollOffset, lineDelta, dragScrollMax)
                                            if (nextOffset != dragScrollOffset) {
                                                dragScrollOffset = nextOffset
                                                currentOnPaneScroll(scrollPaneId, nextOffset)
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (isPinching) {
                            zoomIndicatorPulse++
                            if (abs(liveFontSizeSp - lastDispatchedFontSizeSp) > 0.01f) {
                                lastDispatchedFontSizeSp = liveFontSizeSp
                                currentOnFontSizeChange(liveFontSizeSp)
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
                    normalStyle = normalStyle,
                    boldStyle = boldStyle,
                    italicStyle = italicStyle,
                    boldItalicStyle = boldItalicStyle,
                    asciiGlyphCache = asciiGlyphCache,
                    scratchPath = scratchPath,
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
                        cellWidth = effectiveCellWidth,
                        cellHeight = effectiveCellHeight,
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
            key(split.id) {
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
        }

        // Live Pinch-to-Zoom text size indicator pill
        if (showZoomIndicator) {
            val zoomPercent = ((liveFontSizeSp / 13f) * 100f).roundToInt()
            val roundedSp = ((liveFontSizeSp * 10f).roundToInt() / 10f)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .testTag("zoomIndicatorPill"),
                shape = RoundedCornerShape(18.dp),
                color = palette.panelBg.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier
                        .border(1.dp, palette.accent.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "A-",
                        style = baseTextStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.accent,
                        ),
                        modifier = Modifier
                            .clickable {
                                val next = (liveFontSizeSp - 1.5f).coerceIn(7f, 28f)
                                liveFontSizeSp = next
                                lastDispatchedFontSizeSp = next
                                zoomIndicatorPulse++
                                currentOnFontSizeChange(next)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${roundedSp}sp ($zoomPercent% · ${computedCols}x$computedRows)",
                        style = baseTextStyle.copy(fontSize = 12.sp, color = palette.text),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "A+",
                        style = baseTextStyle.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.accent,
                        ),
                        modifier = Modifier
                            .clickable {
                                val next = (liveFontSizeSp + 1.5f).coerceIn(7f, 28f)
                                liveFontSizeSp = next
                                lastDispatchedFontSizeSp = next
                                zoomIndicatorPulse++
                                currentOnFontSizeChange(next)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (zoomPercent != 100) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Reset",
                            style = baseTextStyle.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.green,
                            ),
                            modifier = Modifier
                                .clickable {
                                    liveFontSizeSp = 13f
                                    lastDispatchedFontSizeSp = 13f
                                    zoomIndicatorPulse++
                                    currentOnFontSizeChange(13f)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
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

@Immutable
private class AsciiGlyphCache(
    private val normal: Array<TextLayoutResult>,
    private val bold: Array<TextLayoutResult>,
    private val italic: Array<TextLayoutResult>,
    private val boldItalic: Array<TextLayoutResult>,
) {
    fun lookup(
        code: Int,
        isBold: Boolean,
        isItalic: Boolean,
    ): TextLayoutResult? {
        if (code !in FIRST_ASCII..LAST_ASCII) return null
        val index = code - FIRST_ASCII
        return when {
            isBold && isItalic -> boldItalic[index]
            isBold -> bold[index]
            isItalic -> italic[index]
            else -> normal[index]
        }
    }

    companion object {
        private const val FIRST_ASCII = 0x21
        private const val LAST_ASCII = 0x7E
        private const val COUNT = LAST_ASCII - FIRST_ASCII + 1
        private val ASCII_STRINGS = Array(COUNT) { (FIRST_ASCII + it).toChar().toString() }

        fun build(
            textMeasurer: TextMeasurer,
            normalStyle: TextStyle,
            boldStyle: TextStyle,
            italicStyle: TextStyle,
            boldItalicStyle: TextStyle,
        ): AsciiGlyphCache = AsciiGlyphCache(
            normal = Array(COUNT) { i ->
                textMeasurer.measure(text = ASCII_STRINGS[i], style = normalStyle, softWrap = false, maxLines = 1)
            },
            bold = Array(COUNT) { i ->
                textMeasurer.measure(text = ASCII_STRINGS[i], style = boldStyle, softWrap = false, maxLines = 1)
            },
            italic = Array(COUNT) { i ->
                textMeasurer.measure(text = ASCII_STRINGS[i], style = italicStyle, softWrap = false, maxLines = 1)
            },
            boldItalic = Array(COUNT) { i ->
                textMeasurer.measure(text = ASCII_STRINGS[i], style = boldItalicStyle, softWrap = false, maxLines = 1)
            },
        )
    }
}

private fun DrawScope.drawFrameCells(
    frame: FrameData,
    cellWidth: Float,
    cellHeight: Float,
    normalStyle: TextStyle,
    boldStyle: TextStyle,
    italicStyle: TextStyle,
    boldItalicStyle: TextStyle,
    asciiGlyphCache: AsciiGlyphCache,
    scratchPath: Path,
    textMeasurer: TextMeasurer,
    palette: BisonThemePalette,
    predictiveEchoUnderlines: Boolean,
) {
    val cols = frame.width.toInt()
    val rows = frame.height.toInt()
    if (cols <= 0 || rows <= 0 || frame.cells.isEmpty()) return

    // Pass 1: Draw contiguous cell backgrounds and line decorations per row
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

            var runEnd = col + 1
            while (runEnd < cols) {
                val next = frame.cellAt(runEnd, row) ?: break
                if (next.skip) break
                if (next.fg != cell.fg || next.bg != cell.bg || next.modifier != cell.modifier) break
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

            if (cell.isUnderline || (predictiveEchoUnderlines && cell.isPredictedEcho)) {
                val stroke = max(1f, cellHeight * 0.07f)
                val lineY = yPx + cellHeight - stroke
                drawLine(
                    color = fgColor,
                    start = Offset(xPx, lineY),
                    end = Offset(xPx + runWidthPx, lineY),
                    strokeWidth = stroke,
                )
            }

            if (cell.isStrikethrough) {
                val stroke = max(1f, cellHeight * 0.07f)
                val lineY = yPx + cellHeight * 0.52f
                drawLine(
                    color = fgColor,
                    start = Offset(xPx, lineY),
                    end = Offset(xPx + runWidthPx, lineY),
                    strokeWidth = stroke,
                )
            }

            if (cell.isCurlyUnderline) {
                drawCurlyUnderline(
                    startX = xPx,
                    endX = xPx + runWidthPx,
                    baselineY = yPx + cellHeight - 2.dp.toPx(),
                    color = palette.red,
                    scratchPath = scratchPath,
                )
            }

            col = runEnd
        }
    }

    // Pass 2: Draw each non-blank cell strictly at its exact (col * cellWidth, row * cellHeight) slot
    // so variable-width fallback glyphs or font rounding can never accumulate drift or overlap across columns.
    for (row in 0 until rows) {
        val yPx = row * cellHeight
        if (yPx > size.height) break
        for (col in 0 until cols) {
            val cell = frame.cellAt(col, row) ?: continue
            if (cell.skip) continue
            val symbol = cell.symbol
            if (symbol.isEmpty() || symbol == " ") continue

            val rawFg = TerminalColorResolver.resolveForeground(cell.fg, palette, isDim = cell.isDim)
            val rawBg = TerminalColorResolver.resolveBackground(cell.bg, palette)
            val fgColor = if (cell.isReverse) rawBg else rawFg
            val xPx = col * cellWidth

            val firstCode = symbol[0].code
            if (symbol.length == 1 && firstCode in 0x2500..0x259F) {
                if (drawBoxOrBlockElement(
                        code = firstCode,
                        cellX = xPx,
                        cellY = yPx,
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        color = fgColor,
                        isBold = cell.isBold,
                    )
                ) {
                    continue
                }
            }

            val layoutResult = if (symbol.length == 1) {
                asciiGlyphCache.lookup(
                    code = firstCode,
                    isBold = cell.isBold,
                    isItalic = cell.isItalic,
                )
            } else {
                null
            } ?: run {
                val glyphStyle = when {
                    cell.isBold && cell.isItalic -> boldItalicStyle
                    cell.isBold -> boldStyle
                    cell.isItalic -> italicStyle
                    else -> normalStyle
                }
                textMeasurer.measure(
                    text = symbol,
                    style = glyphStyle,
                    softWrap = false,
                    maxLines = 1,
                )
            }
            val offsetX = xPx + max(0f, (cellWidth - layoutResult.size.width) * 0.5f)
            val offsetY = yPx + (cellHeight - layoutResult.size.height) * 0.5f
            drawText(
                textLayoutResult = layoutResult,
                color = fgColor,
                topLeft = Offset(offsetX, offsetY),
            )
        }
    }
}

private fun isHeavyBoxChar(code: Int): Boolean = when (code) {
    0x2501, 0x2503, 0x250F, 0x2513, 0x2517, 0x251B, 0x2523, 0x252B, 0x2533, 0x253B, 0x254B -> true
    else -> false
}

private fun DrawScope.drawBoxOrBlockElement(
    code: Int,
    cellX: Float,
    cellY: Float,
    cellWidth: Float,
    cellHeight: Float,
    color: Color,
    isBold: Boolean,
): Boolean {
    val midX = cellX + cellWidth * 0.5f
    val midY = cellY + cellHeight * 0.5f
    val rightX = cellX + cellWidth + 0.5f
    val bottomY = cellY + cellHeight + 0.5f
    val stroke = if (isBold || isHeavyBoxChar(code)) {
        max(1.8f, cellHeight * 0.12f)
    } else {
        max(1.1f, cellHeight * 0.07f)
    }

    fun hLine(x1: Float, x2: Float) {
        drawLine(color = color, start = Offset(x1, midY), end = Offset(x2, midY), strokeWidth = stroke)
    }
    fun vLine(y1: Float, y2: Float) {
        drawLine(color = color, start = Offset(midX, y1), end = Offset(midX, y2), strokeWidth = stroke)
    }

    return when (code) {
        // Horizontal lines
        0x2500, 0x2501, in 0x2504..0x2509, 0x254C, 0x254D, 0x2550, 0x2574, 0x2576, 0x2578, 0x257A, 0x257C, 0x257E -> {
            hLine(cellX, rightX)
            true
        }
        // Vertical lines
        0x2502, 0x2503, 0x2506, 0x2507, 0x250A, 0x250B, 0x254E, 0x254F, 0x2551, 0x2575, 0x2577, 0x2579, 0x257B, 0x257D, 0x257F -> {
            vLine(cellY, bottomY)
            true
        }
        // Top-left corners (┌ ┏ ╔ ╭)
        in 0x250C..0x250F, in 0x2552..0x2554, 0x256D -> {
            hLine(midX, rightX)
            vLine(midY, bottomY)
            true
        }
        // Top-right corners (┐ ┓ ╗ ╮)
        in 0x2510..0x2513, in 0x2555..0x2557, 0x256E -> {
            hLine(cellX, midX)
            vLine(midY, bottomY)
            true
        }
        // Bottom-left corners (└ ┗ ╚ ╰)
        in 0x2514..0x2517, in 0x2558..0x255A, 0x2570 -> {
            vLine(cellY, midY)
            hLine(midX, rightX)
            true
        }
        // Bottom-right corners (┘ ┛ ╝ ╯)
        in 0x2518..0x251B, in 0x255B..0x255D, 0x256F -> {
            vLine(cellY, midY)
            hLine(cellX, midX)
            true
        }
        // Left tees (├ ┣ ╠)
        in 0x251C..0x2523, in 0x255E..0x2560 -> {
            vLine(cellY, bottomY)
            hLine(midX, rightX)
            true
        }
        // Right tees (┤ ┫ ╣)
        in 0x2524..0x252B, in 0x2561..0x2563 -> {
            vLine(cellY, bottomY)
            hLine(cellX, midX)
            true
        }
        // Top tees (┬ ┳ ╦)
        in 0x252C..0x2533, in 0x2564..0x2566 -> {
            hLine(cellX, rightX)
            vLine(midY, bottomY)
            true
        }
        // Bottom tees (┴ ┻ ╩)
        in 0x2534..0x253B, in 0x2567..0x2569 -> {
            hLine(cellX, rightX)
            vLine(cellY, midY)
            true
        }
        // Crosses (┼ ╋ ╬)
        in 0x253C..0x254B, in 0x256A..0x256C -> {
            hLine(cellX, rightX)
            vLine(cellY, bottomY)
            true
        }
        // Block elements
        0x2580 -> {
            drawRect(color = color, topLeft = Offset(cellX, cellY), size = Size(cellWidth + 0.5f, cellHeight * 0.5f))
            true
        }
        in 0x2581..0x2588 -> {
            val frac = (code - 0x2580).toFloat() / 8f
            val h = cellHeight * frac
            drawRect(color = color, topLeft = Offset(cellX, cellY + cellHeight - h), size = Size(cellWidth + 0.5f, h + 0.5f))
            true
        }
        in 0x2589..0x258F -> {
            val frac = (0x2590 - code).toFloat() / 8f
            drawRect(color = color, topLeft = Offset(cellX, cellY), size = Size(cellWidth * frac + 0.5f, cellHeight + 0.5f))
            true
        }
        0x2590 -> {
            drawRect(color = color, topLeft = Offset(midX, cellY), size = Size(cellWidth * 0.5f + 0.5f, cellHeight + 0.5f))
            true
        }
        0x2591 -> {
            drawRect(color = color.copy(alpha = 0.25f), topLeft = Offset(cellX, cellY), size = Size(cellWidth + 0.5f, cellHeight + 0.5f))
            true
        }
        0x2592 -> {
            drawRect(color = color.copy(alpha = 0.50f), topLeft = Offset(cellX, cellY), size = Size(cellWidth + 0.5f, cellHeight + 0.5f))
            true
        }
        0x2593 -> {
            drawRect(color = color.copy(alpha = 0.75f), topLeft = Offset(cellX, cellY), size = Size(cellWidth + 0.5f, cellHeight + 0.5f))
            true
        }
        else -> false
    }
}

private fun DrawScope.drawCurlyUnderline(
    startX: Float,
    endX: Float,
    baselineY: Float,
    color: Color,
    scratchPath: Path,
) {
    val waveLength = 6.dp.toPx().coerceAtLeast(4f)
    val amplitude = 1.5.dp.toPx()
    scratchPath.rewind()
    scratchPath.moveTo(startX, baselineY)
    var x = startX
    var up = true
    while (x < endX) {
        val nextX = min(x + waveLength / 2f, endX)
        val midX = (x + nextX) / 2f
        val controlY = if (up) baselineY - amplitude else baselineY + amplitude
        scratchPath.quadraticTo(midX, controlY, nextX, baselineY)
        x = nextX
        up = !up
    }
    drawPath(path = scratchPath, color = color, style = Stroke(width = 1.2.dp.toPx()))
}

private fun DrawScope.drawPaneChrome(
    pane: PaneSurfacePane,
    cellWidth: Float,
    cellHeight: Float,
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

/**
 * Returns the scroll offset (lines from the bottom) after [lineDelta] lines from [current]. A
 * positive [lineDelta] moves up into the history. The result is in `0..maxOffset`. When
 * [maxOffset] is 0 (not known), only the bottom limit applies.
 */
internal fun nextPaneScrollOffset(current: Int, lineDelta: Int, maxOffset: Int): Int {
    val next = (current.toLong() + lineDelta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return if (maxOffset > 0) next.coerceAtMost(maxOffset) else next
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
