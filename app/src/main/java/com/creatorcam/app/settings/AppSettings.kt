package com.creatorcam.app.settings

import com.creatorcam.app.compose.DualLayout

/**
 * Persisted user preferences. Everything here is a hint: the camera engine
 * always intersects these choices with real hardware capabilities and falls
 * back to the recommended configuration when a choice is impossible.
 */
data class AppSettings(
    // Camera
    val defaultLayout: DualLayout = DualLayout.SPLIT_50_50,
    val resolution: VideoResolution = VideoResolution.AUTO,
    val frameRate: FrameRate = FrameRate.AUTO,
    val mirrorFront: Boolean = true,
    val stabilization: StabilizationMode = StabilizationMode.STANDARD,
    val defaultMode: CameraMode = CameraMode.DUAL,
    // Audio
    val micEnabled: Boolean = true,
    // Recording
    val countdownSeconds: Int = 3,
    val keepSourceFiles: Boolean = false,
    // Overlay
    val watermarkEnabled: Boolean = false,
    val watermarkText: String = "CreatorCam",
    val dateTimeOverlay: Boolean = false,
    // App
    val theme: AppTheme = AppTheme.DARK,
)

enum class CameraMode { DUAL, SINGLE_REAR, SINGLE_FRONT }

enum class VideoResolution(val label: String, val longEdge: Int) {
    AUTO("Auto (recommended)", 0),
    HD_720P("720p", 1280),
    FULL_HD_1080P("1080p", 1920),
    UHD_4K("4K", 3840),
}

enum class FrameRate(val label: String, val fps: Int) {
    AUTO("Auto", 0),
    FPS_24("24 FPS", 24),
    FPS_30("30 FPS", 30),
    FPS_60("60 FPS", 60),
}

enum class StabilizationMode(val label: String) {
    OFF("Off"),
    STANDARD("Standard"),
}

enum class AppTheme(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System"),
}
