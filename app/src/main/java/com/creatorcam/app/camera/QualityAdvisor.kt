package com.creatorcam.app.camera

import android.util.Size
import androidx.camera.video.Quality
import com.creatorcam.app.settings.FrameRate
import com.creatorcam.app.settings.VideoResolution

/**
 * Smart quality matching: intersects front/rear hardware ceilings and the
 * user's preference, then produces a configuration the device can actually
 * sustain on both cameras at once.
 *
 * Never promises more than the weaker camera supports.
 */
object QualityAdvisor {

    /** Ordered fallback chain offered to CameraX [androidx.camera.video.Recorder]. */
    fun qualityChain(resolution: VideoResolution): List<Quality> = when (resolution) {
        VideoResolution.UHD_4K -> listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        VideoResolution.FULL_HD_1080P -> listOf(Quality.FHD, Quality.HD, Quality.SD)
        VideoResolution.HD_720P -> listOf(Quality.HD, Quality.SD)
        VideoResolution.AUTO -> listOf(Quality.FHD, Quality.HD, Quality.SD, Quality.UHD)
    }

    fun recommend(front: CameraFacingInfo?, rear: CameraFacingInfo?): QualityRecommendation {
        val commonSize = largestCommonSize(front, rear) ?: Size(1280, 720)
        val fps = commonFps(front, rear)
        return QualityRecommendation(
            width = commonSize.width,
            height = commonSize.height,
            fps = fps,
            label = "${sizeLabel(commonSize)} • $fps FPS",
            frontCeiling = front?.largestVideoSize?.let(::sizeLabel) ?: "n/a",
            rearCeiling = rear?.largestVideoSize?.let(::sizeLabel) ?: "n/a",
        )
    }

    /**
     * Resolves the effective capture spec: user preference clamped to what both
     * cameras (or the single active one) can actually do.
     */
    fun resolve(
        front: CameraFacingInfo?,
        rear: CameraFacingInfo?,
        dual: Boolean,
        wantResolution: VideoResolution,
        wantFps: FrameRate,
    ): ResolvedCaptureSpec {
        val active = if (dual) listOfNotNull(front, rear) else listOfNotNull(rear ?: front)
        val maxArea = active.mapNotNull { it.largestVideoSize }
            .minOfOrNull { it.width * it.height } ?: (1280 * 720)

        val resolution = when (wantResolution) {
            VideoResolution.AUTO -> {
                // Recommended: highest quality sustainable on both cameras.
                // 4K dual capture is thermally risky on most phones, so AUTO
                // caps at 1080p unless both ceilings comfortably exceed it.
                if (maxArea >= 3840 * 2160 && !dual) VideoResolution.UHD_4K
                else VideoResolution.FULL_HD_1080P
            }
            else -> wantResolution
        }.let {ClampResolution(it, maxArea) }

        val maxFps = active.map { info ->
            info.fpsRanges.maxOfOrNull { it.last } ?: 30
        }.minOrNull() ?: 30
        val fps = when (wantFps) {
            FrameRate.AUTO -> if (maxFps >= 30) 30 else maxFps.coerceAtLeast(15)
            else -> wantFps.fps.coerceAtMost(maxFps)
        }
        return ResolvedCaptureSpec(resolution, fps, maxArea, maxFps)
    }

    private fun clampResolution(want: VideoResolution, maxArea: Int): VideoResolution {
        val ordered = listOf(
            VideoResolution.HD_720P,
            VideoResolution.FULL_HD_1080P,
            VideoResolution.UHD_4K,
        )
        val areas = mapOf(
            VideoResolution.HD_720P to 1280 * 720,
            VideoResolution.FULL_HD_1080P to 1920 * 1080,
            VideoResolution.UHD_4K to 3840 * 2160,
        )
        var current = want
        while ((areas[current] ?: 0) > maxArea) {
            val idx = ordered.indexOf(current)
            if (idx <= 0) break
            current = ordered[idx - 1]
        }
        return current
    }

    private fun largestCommonSize(front: CameraFacingInfo?, rear: CameraFacingInfo?): Size? {
        val frontSizes = front?.videoSizes.orEmpty()
        val rearSizes = rear?.videoSizes.orEmpty()
        if (frontSizes.isEmpty() || rearSizes.isEmpty()) {
            return (frontSizes + rearSizes).maxByOrNull { it.width * it.height }
        }
        val rearSet = rearSizes.toSet()
        return frontSizes.firstOrNull { it in rearSet }
            ?: frontSizes.firstOrNull { f ->
                rearSizes.any { r -> r.width == f.width && r.height == f.height }
            }
    }

    private fun commonFps(front: CameraFacingInfo?, rear: CameraFacingInfo?): Int {
        val ceilings = listOfNotNull(front, rear).map { info ->
            info.fpsRanges.maxOfOrNull { it.last } ?: 30
        }
        val max = ceilings.minOrNull() ?: 30
        return when {
            max >= 60 -> 30 // Prefer 30 for dual thermal headroom; 60 selectable if common.
            max >= 30 -> 30
            max >= 24 -> 24
            else -> max.coerceAtLeast(15)
        }
    }

    fun sizeLabel(size: Size): String {
        val long = maxOf(size.width, size.height)
        return when {
            long >= 3840 -> "4K"
            long >= 1920 -> "1080p"
            long >= 1280 -> "720p"
            long >= 960 -> "540p"
            else -> "${size.width}×${size.height}"
        }
    }
}

data class ResolvedCaptureSpec(
    val resolution: VideoResolution,
    val fps: Int,
    /** Smallest per-camera max area; the honest ceiling for "common" claims. */
    val ceilingArea: Int,
    val ceilingFps: Int,
)
