package com.creatorcam.app.compose

/**
 * Pure geometry for every layout. Unit-tested ([LayoutSpecTest]).
 *
 * Conventions:
 * - Front = creator (selfie), rear = environment.
 * - SPLIT_50_50 puts the creator on top, environment below (the default hero
 *   layout from the product brief).
 * - PiP windows keep the output's aspect ratio so portrait takes get tall
 *   face windows and landscape takes get wide ones — no squish, no guess.
 */
object LayoutSpec {

    fun rects(
        layout: DualLayout,
        outW: Int,
        outH: Int,
        pip: PipStyle = PipStyle(),
    ): LayoutRects {
        require(outW > 0 && outH > 0)
        return when (layout) {
            DualLayout.SPLIT_50_50 -> LayoutRects(
                rear = Region(0f, 0.5f, 1f, 0.5f),
                front = Region(0f, 0f, 1f, 0.5f),
            )
            DualLayout.SPLIT_SWAPPED -> LayoutRects(
                rear = Region(0f, 0f, 1f, 0.5f),
                front = Region(0f, 0.5f, 1f, 0.5f),
            )
            DualLayout.SIDE_BY_SIDE -> LayoutRects(
                rear = Region(0.5f, 0f, 0.5f, 1f),
                front = Region(0f, 0f, 0.5f, 1f),
            )
            DualLayout.REAR_MAIN_FRONT_PIP -> LayoutRects(
                rear = Region(0f, 0f, 1f, 1f),
                front = pipRect(outW, outH, pip, RegionShape.RECT),
            )
            DualLayout.FRONT_MAIN_REAR_PIP -> LayoutRects(
                // Front fullscreen, rear window. Both the composer and the
                // preview draw the largest region first, so the window lands
                // on top whichever camera owns it.
                rear = pipRect(outW, outH, pip, RegionShape.RECT),
                front = Region(0f, 0f, 1f, 1f),
            )
            DualLayout.CIRCLE_PIP -> LayoutRects(
                rear = Region(0f, 0f, 1f, 1f),
                front = pipRect(outW, outH, pip, RegionShape.CIRCLE),
            )
            DualLayout.ROUNDED_PIP -> LayoutRects(
                rear = Region(0f, 0f, 1f, 1f),
                front = pipRect(outW, outH, pip, RegionShape.ROUNDED_RECT),
            )
            DualLayout.FULLSCREEN_SINGLE -> LayoutRects(
                rear = Region(0f, 0f, 1f, 1f),
                front = null,
            )
        }
    }

    private fun pipRect(
        outW: Int,
        outH: Int,
        pip: PipStyle,
        shape: RegionShape,
    ): Region {
        val scale = pip.scale.coerceIn(0.15f, 0.6f)
        val margin = pip.margin.coerceIn(0f, 0.2f)
        val (w, h) = if (shape == RegionShape.CIRCLE) {
            // Square, sized off the smaller edge.
            val dw = scale * minOf(outW, outH) / outW
            val dh = scale * minOf(outW, outH) / outH
            dw to dh
        } else {
            val w = scale
            val h = scale * outH / outW
            // If the window would exceed the frame height, fit to height.
            if (h > 0.7f) (0.7f * outW / outH) to 0.7f else w to h
        }
        val (x, y) = when (pip.position) {
            PipPosition.TOP_START -> margin to margin * outW / outH
            PipPosition.TOP_END -> (1f - margin - w) to (margin * outW / outH)
            PipPosition.BOTTOM_START -> margin to (1f - h - margin * outW / outH)
            PipPosition.BOTTOM_END -> (1f - margin - w) to (1f - h - margin * outW / outH)
            PipPosition.CENTER -> ((1f - w) / 2f) to ((1f - h) / 2f)
        }
        return Region(
            x = x.coerceIn(0f, 1f - w),
            y = y.coerceIn(0f, 1f - h),
            w = w,
            h = h,
            shape = shape,
            cornerRadius = if (shape == RegionShape.ROUNDED_RECT) pip.cornerRadius else 0f,
        )
    }
}
