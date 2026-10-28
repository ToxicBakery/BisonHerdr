package com.toxicbakery.app.bison.protocol

import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response payload from `pane.layout` used to synthesize `PaneSurfaceSplit` hit rects
 * and BSP paths.
 */
@Serializable
data class PaneLayoutSnapshot(
    @SerialName("workspace_id") val workspaceId: String = "w1",
    @SerialName("tab_id") val tabId: String = "w1:t1",
    val zoomed: Boolean = false,
    val panes: List<PaneLayoutPane> = emptyList(),
    val splits: List<PaneLayoutSplit> = emptyList(),
)

@Serializable
data class PaneLayoutPane(
    @SerialName("pane_id") val paneId: String,
    val rect: SurfaceRect,
    val focused: Boolean = false,
)

@Serializable
data class PaneLayoutSplit(
    val id: String,
    val direction: PaneSurfaceSplitDirection,
    val ratio: Float,
    val rect: SurfaceRect,
)

/**
 * Parses a Herdr split ID (such as `"s0:root"`, `"s1:l"`, `"s2:lr"`, `"s3:rlr"`) into a
 * binary space partition path (`List<Boolean>`, where `false` = left/top branch and `true` = right/bottom branch).
 */
fun parseBspPathFromSplitId(splitId: String): List<Boolean> {
    val branchToken = splitId.substringAfter(':', missingDelimiterValue = splitId).trim().lowercase()
    if (branchToken.isEmpty() || branchToken == "root") {
        return emptyList()
    }
    return buildList(branchToken.length) {
        for (ch in branchToken) {
            when (ch) {
                'l', '0' -> add(false)
                'r', '1' -> add(true)
            }
        }
    }
}

/**
 * Pure KMP derivation of [PaneSurfaceSplit] items from a [PaneLayoutSnapshot].
 *
 * - Returns `emptyList()` when [PaneLayoutSnapshot.zoomed] is `true` (since a zoomed tab shows only
 *   the maximized focused pane).
 * - Parses the BSP tree `path` from each [PaneLayoutSplit.id] (`"s0:root"`, `"s1:lr"`).
 * - Computes the 1-cell divider `hitRect` from `direction`, `ratio`, and the split's bounding `rect`.
 */
fun deriveSplitsFromLayout(layout: PaneLayoutSnapshot): List<PaneSurfaceSplit> {
    if (layout.zoomed) return emptyList()
    return layout.splits.map { split ->
        val clampedRatio = split.ratio.coerceIn(0.1f, 0.9f)
        val path = parseBspPathFromSplitId(split.id)
        val hitRect = when (split.direction) {
            PaneSurfaceSplitDirection.Vertical -> {
                val width = split.rect.width.toInt().coerceAtLeast(1)
                val offsetCols = (width * clampedRatio).roundToInt().coerceIn(0, width - 1)
                val dividerCol = (split.rect.x.toInt() + offsetCols).toUShort()
                SurfaceRect(
                    x = dividerCol,
                    y = split.rect.y,
                    width = 1u,
                    height = split.rect.height,
                )
            }

            PaneSurfaceSplitDirection.Horizontal -> {
                val height = split.rect.height.toInt().coerceAtLeast(1)
                val offsetRows = (height * clampedRatio).roundToInt().coerceIn(0, height - 1)
                val dividerRow = (split.rect.y.toInt() + offsetRows).toUShort()
                SurfaceRect(
                    x = split.rect.x,
                    y = dividerRow,
                    width = split.rect.width,
                    height = 1u,
                )
            }
        }
        PaneSurfaceSplit(
            id = split.id,
            direction = split.direction,
            ratio = clampedRatio,
            hitRect = hitRect,
            path = path,
            workspaceRect = split.rect,
        )
    }
}
