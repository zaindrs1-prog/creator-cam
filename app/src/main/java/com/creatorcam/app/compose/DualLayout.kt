package com.creatorcam.app.compose

/**
 * The fixed preset layouts of V1. Geometry lives in [LayoutSpec], which is
 * the single source of truth shared by the live preview UI and the offline
 * [VideoComposer] — what you frame is what you export.
 *
 * Custom PiP (drag/resize/radius) is structured in via [PipStyle]: the UI can
 * already pass custom values through [LayoutSpec.rects]; the gesture editors
 * land in V2.
 */
enum class DualLayout(val title: String, val shortLabel: String) {
    SPLIT_50_50("Split 50/50", "50/50"),
    SPLIT_SWAPPED("Split swapped", "Swap"),
    SIDE_BY_SIDE("Side by side", "Side"),
    REAR_MAIN_FRONT_PIP("Rear + face", "Rear+"),
    FRONT_MAIN_REAR_PIP("Face + rear", "Face+"),
    CIRCLE_PIP("Circle face", "Circle"),
    ROUNDED_PIP("Rounded PiP", "PiP"),
    /** Internal: one surviving stream fullscreen (degraded-mode / single). */
    FULLSCREEN_SINGLE("Single", "1-cam"),
}

/** Normalized region (origin top-left, 0..1) inside the output frame. */
data class Region(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val shape: RegionShape = RegionShape.RECT,
    /** Corner radius as a fraction of the region's smaller side. */
    val cornerRadius: Float = 0f,
)

enum class RegionShape { RECT, ROUNDED_RECT, CIRCLE }

data class LayoutRects(val rear: Region, val front: Region?)

enum class PipPosition { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END, CENTER }

data class PipStyle(
    /** PiP width as a fraction of output width. */
    val scale: Float = 0.36f,
    /** Margin as a fraction of output width/height. */
    val margin: Float = 0.035f,
    val position: PipPosition = PipPosition.BOTTOM_END,
    /** Rounded-PiP corner radius (fraction of smaller side). */
    val cornerRadius: Float = 0.08f,
)
