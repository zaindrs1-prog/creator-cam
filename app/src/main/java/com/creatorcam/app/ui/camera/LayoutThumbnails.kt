package com.creatorcam.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.compose.LayoutSpec
import com.creatorcam.app.compose.RegionShape
import com.creatorcam.app.ui.theme.CreatorColors

val SELECTABLE_LAYOUTS = listOf(
    DualLayout.SPLIT_50_50,
    DualLayout.SPLIT_SWAPPED,
    DualLayout.SIDE_BY_SIDE,
    DualLayout.REAR_MAIN_FRONT_PIP,
    DualLayout.FRONT_MAIN_REAR_PIP,
    DualLayout.CIRCLE_PIP,
    DualLayout.ROUNDED_PIP,
)

/**
 * Visual layout picker. Thumbnails are drawn from the same [LayoutSpec]
 * geometry the composer uses, so the icon always matches the real output.
 */
@Composable
fun LayoutPickerSheet(
    selected: DualLayout,
    enabled: Boolean,
    onSelect: (DualLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(20.dp)) {
        Text("Layout", style = MaterialTheme.typography.titleLarge)
        Text(
            if (enabled) "Preview and export use the same framing."
            else "Layout is locked while recording.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            items(SELECTABLE_LAYOUTS) { layout ->
                LayoutThumb(
                    layout = layout,
                    selected = layout == selected,
                    enabled = enabled,
                    onClick = { onSelect(layout) },
                )
            }
        }
    }
}

@Composable
private fun LayoutThumb(
    layout: DualLayout,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val rects = remember(layout) { LayoutSpec.rects(layout, 9, 16) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) CreatorColors.Record
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(6.dp),
    ) {
        Canvas(
            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 13f),
        ) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                Color(0xFF232A32),
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(8f, 8f),
            )
            fun drawRegion(
                x: Float, y: Float, rw: Float, rh: Float,
                shape: RegionShape, color: Color,
            ) {
                val left = x * w
                val top = y * h
                val width = rw * w
                val height = rh * h
                when (shape) {
                    RegionShape.CIRCLE -> drawCircle(
                        color,
                        radius = minOf(width, height) / 2f,
                        center = Offset(left + width / 2f, top + height / 2f),
                    )
                    else -> drawRoundRect(
                        color,
                        topLeft = Offset(left + 1f, top + 1f),
                        size = Size(width - 2f, height - 2f),
                        cornerRadius = CornerRadius(6f, 6f),
                    )
                }
            }
            // Largest first (bottom layer) — same rule as the composer.
            val layers = listOf(
                Triple(rects.rear, Color(0xFF4DA3FF), rects.rear.w * rects.rear.h)
            ) + listOfNotNull(
                rects.front?.let { Triple(it, Color(0xFFFFB84D), it.w * it.h) }
            )
            layers.sortedByDescending { it.third }.forEach { (region, color, _) ->
                drawRegion(
                    region.x, region.y, region.w, region.h,
                    region.shape, color,
                )
            }
        }
        Text(
            layout.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) CreatorColors.Record
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
