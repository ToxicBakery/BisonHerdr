package com.toxicbakery.app.bison.protocol

/**
 * Result of applying a full surface frame, reuse, scroll, or delta in [SurfaceDeltaAssembler].
 *
 * When a `presentation_effects_fence` epoch (`endpoint.presentation.sync.v1`) was pending and is
 * satisfied by the committed frame, [presentationReadyControl] contains the outbound
 * `endpoint.presentation.ready.v1` control message to send back to the Herdr server.
 */
data class AssembledSurfaceResult(
    val frame: PaneSurfaceFrame,
    val presentationReadyControl: EndpointControlEnvelope? = null,
)

/**
 * Pure KMP stateful decoder/assembler that maintains the committed [PaneSurfaceFrame] baseline
 * per machine, applies [PaneSurfacePatch], `surface_reuse`, `surface_scroll`, and `surface_delta`,
 * and tracks `presentation_effects_fence` epochs (`endpoint.presentation.sync.v1` -> `endpoint.presentation.ready.v1`).
 */
class SurfaceDeltaAssembler {

    private val baselines = mutableMapOf<String, PaneSurfaceFrame>()
    private val pendingPresentationEpochs = mutableMapOf<String, ULong>()

    /**
     * Records a server `endpoint.presentation.sync.v1` fence epoch for [machineId].
     * The next coherent frame committed for [machineId] at or after [epoch] will emit
     * `endpoint.presentation.ready.v1`.
     */
    fun onPresentationSync(machineId: String, epoch: ULong) {
        pendingPresentationEpochs[machineId] = epoch
    }

    /**
     * Returns the last committed [PaneSurfaceFrame] for [machineId], or `null` if none exists.
     */
    fun getCommittedFrame(machineId: String): PaneSurfaceFrame? = baselines[machineId]

    /**
     * Commits a full [PaneSurfaceFrame] as the new baseline for [machineId].
     */
    fun commitFullFrame(machineId: String, frame: PaneSurfaceFrame): AssembledSurfaceResult {
        val normalizedCells = normalizeCellGrid(frame.frame.width, frame.frame.height, frame.frame.cells)
        val committed = frame.copy(frame = frame.frame.copy(cells = normalizedCells))
        baselines[machineId] = committed
        return AssembledSurfaceResult(
            frame = committed,
            presentationReadyControl = consumeReadyFenceIfSatisfied(machineId, committed),
        )
    }

    /**
     * Applies `endpoint.surface-reuse.v1`: retains the baseline cell grid from the previous
     * committed frame while updating pane geometry, splits, cursor, popup, graphics, and revision.
     */
    fun applySurfaceReuse(machineId: String, metadataFrame: PaneSurfaceFrame): AssembledSurfaceResult {
        val previous = baselines[machineId]
        val reusedCells = if (
            previous != null &&
            previous.frame.width == metadataFrame.frame.width &&
            previous.frame.height == metadataFrame.frame.height
        ) {
            previous.frame.cells
        } else {
            normalizeCellGrid(
                metadataFrame.frame.width,
                metadataFrame.frame.height,
                metadataFrame.frame.cells,
            )
        }
        val committed = metadataFrame.copy(
            frame = metadataFrame.frame.copy(
                cells = reusedCells,
                cursor = metadataFrame.frame.cursor ?: previous?.frame?.cursor,
            ),
        )
        baselines[machineId] = committed
        return AssembledSurfaceResult(
            frame = committed,
            presentationReadyControl = consumeReadyFenceIfSatisfied(machineId, committed),
        )
    }

    /**
     * Applies an incremental [PaneSurfacePatch] (or `endpoint.surface-delta.v1`) against the
     * baseline frame for [machineId].
     */
    fun applyPatch(machineId: String, patch: PaneSurfacePatch): AssembledSurfaceResult? {
        val previous = baselines[machineId] ?: return null
        val width = patch.width.toInt()
        val height = patch.height.toInt()
        val mutableCells = if (
            previous.frame.width == patch.width &&
            previous.frame.height == patch.height
        ) {
            previous.frame.cells.toMutableList()
        } else {
            MutableList(width * height) { CellData() }
        }

        for (rowPatch in patch.rows) {
            val y = rowPatch.y.toInt()
            if (y !in 0..<height) continue
            val startX = rowPatch.startX.toInt()
            for ((offset, cell) in rowPatch.cells.withIndex()) {
                val x = startX + offset
                if (x in 0..<width) {
                    mutableCells[y * width + x] = cell
                }
            }
        }

        val committed = previous.copy(
            bootId = patch.bootId,
            revision = patch.revision,
            frame = FrameData(
                width = patch.width,
                height = patch.height,
                cells = mutableCells,
                cursor = patch.cursor ?: previous.frame.cursor,
            ),
            panes = patch.panes ?: previous.panes,
            splits = patch.splits ?: previous.splits,
            popup = patch.popup ?: previous.popup,
            graphics = patch.graphics ?: previous.graphics,
            presentationEpoch = patch.presentationEpoch ?: previous.presentationEpoch,
        )
        baselines[machineId] = committed
        return AssembledSurfaceResult(
            frame = committed,
            presentationReadyControl = consumeReadyFenceIfSatisfied(machineId, committed),
        )
    }

    /**
     * Alias for [applyPatch] used for `endpoint.surface-delta.v1` payloads decoded to [PaneSurfacePatch].
     */
    fun applySurfaceDelta(machineId: String, patch: PaneSurfacePatch): AssembledSurfaceResult? =
        applyPatch(machineId, patch)

    /**
     * Applies `endpoint.surface-scroll.v1`: shifts rows inside [region] by [deltaRows]
     * (positive = scroll content up / new lines at bottom; negative = scroll content down / new lines at top),
     * clears vacated rows with blank cells, and optionally applies a trailing [patch] for the newly exposed rows.
     */
    fun applySurfaceScroll(
        machineId: String,
        region: SurfaceRect,
        deltaRows: Int,
        patch: PaneSurfacePatch? = null,
    ): AssembledSurfaceResult? {
        val previous = baselines[machineId] ?: return null
        val gridWidth = previous.frame.width.toInt()
        val gridHeight = previous.frame.height.toInt()
        val mutableCells = previous.frame.cells.toMutableList()

        val rx = region.x.toInt().coerceIn(0, gridWidth)
        val ry = region.y.toInt().coerceIn(0, gridHeight)
        val rw = region.width.toInt().coerceAtMost(gridWidth - rx)
        val rh = region.height.toInt().coerceAtMost(gridHeight - ry)

        if (rw > 0 && rh > 0 && deltaRows != 0) {
            val snapshot = mutableCells.toList()
            for (localRow in 0..<rh) {
                val targetRow = ry + localRow
                val sourceLocalRow = localRow + deltaRows
                for (localCol in 0..<rw) {
                    val col = rx + localCol
                    val targetIdx = targetRow * gridWidth + col
                    mutableCells[targetIdx] = if (sourceLocalRow in 0..<rh) {
                        val sourceRow = ry + sourceLocalRow
                        snapshot[sourceRow * gridWidth + col]
                    } else {
                        CellData()
                    }
                }
            }
        }

        val scrolledFrame = previous.copy(
            frame = previous.frame.copy(cells = mutableCells),
        )
        baselines[machineId] = scrolledFrame

        return if (patch != null) {
            applyPatch(machineId, patch)
        } else {
            AssembledSurfaceResult(
                frame = scrolledFrame,
                presentationReadyControl = consumeReadyFenceIfSatisfied(machineId, scrolledFrame),
            )
        }
    }

    fun clear(machineId: String) {
        baselines.remove(machineId)
        pendingPresentationEpochs.remove(machineId)
    }

    private fun consumeReadyFenceIfSatisfied(
        machineId: String,
        frame: PaneSurfaceFrame,
    ): EndpointControlEnvelope? {
        val pendingEpoch = pendingPresentationEpochs[machineId] ?: return null
        val frameEpoch = frame.presentationEpoch
        if (frameEpoch == null || frameEpoch >= pendingEpoch) {
            pendingPresentationEpochs.remove(machineId)
            val resolvedEpoch = frameEpoch ?: pendingEpoch
            return EndpointControlEnvelope(
                kind = KIND_PRESENTATION_READY,
                dataJson = """{"epoch":$resolvedEpoch}""",
            )
        }
        return null
    }

    private fun normalizeCellGrid(
        width: UShort,
        height: UShort,
        cells: List<CellData>,
    ): List<CellData> {
        val total = width.toInt() * height.toInt()
        if (cells.size == total) return cells
        return List(total) { index -> cells.getOrElse(index) { CellData() } }
    }

    companion object {
        const val KIND_PRESENTATION_SYNC: String = "endpoint.presentation.sync.v1"
        const val KIND_PRESENTATION_READY: String = "endpoint.presentation.ready.v1"
    }
}
