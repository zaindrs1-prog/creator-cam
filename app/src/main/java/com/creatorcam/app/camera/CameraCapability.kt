package com.creatorcam.app.camera

import android.util.Size

/**
 * Result of the real hardware capability probe. Nothing here is guessed:
 * every flag comes from CameraManager / CameraCharacteristics, and concurrent
 * support additionally requires the front+rear pair to appear in the same
 * concurrent-camera set (API 30+) and in CameraX's concurrent info list.
 */
data class DeviceCapabilityReport(
    val frontCamera: CameraFacingInfo?,
    val rearCamera: CameraFacingInfo?,
    val concurrentSupported: Boolean,
    val concurrentDetail: String,
    val microphonePresent: Boolean,
    val recommendation: QualityRecommendation,
) {
    val frontSupported: Boolean get() = frontCamera != null
    val rearSupported: Boolean get() = rearCamera != null
    val dualReady: Boolean get() = concurrentSupported && frontSupported && rearSupported
}

data class CameraFacingInfo(
    val cameraId: String,
    val facing: Facing,
    /** All video-capable sizes for RECORDER-class outputs, largest first. */
    val videoSizes: List<Size>,
    /** AE target FPS ranges reported by the HAL. */
    val fpsRanges: List<IntRange>,
    val opticalStabilization: Boolean,
    val videoStabilizationModes: IntArray,
    val maxDigitalZoom: Float,
    val flashAvailable: Boolean,
) {
    val largestVideoSize: Size? get() = videoSizes.firstOrNull()
    val supportsVideoStabilization: Boolean
        get() = videoStabilizationModes.any { it != 0 }
}

enum class Facing { FRONT, REAR }

/**
 * Best common synchronized configuration for both cameras, plus the
 * per-camera ceilings it was derived from.
 */
data class QualityRecommendation(
    val width: Int,
    val height: Int,
    val fps: Int,
    val label: String,
    val frontCeiling: String,
    val rearCeiling: String,
)
