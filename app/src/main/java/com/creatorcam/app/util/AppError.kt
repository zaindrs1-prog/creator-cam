package com.creatorcam.app.util

/**
 * Specific, user-actionable error model. The UI renders [title] + [message] and,
 * when present, an action such as "Open settings".
 */
sealed class AppError(
    open val title: String,
    open val message: String,
    open val recoverable: Boolean = true,
) {
    data class DualCameraUnavailable(
        override val title: String = "Dual Camera Unavailable",
        override val message: String =
            "This device cannot run the front and rear cameras at the same time. " +
                "Single-camera recording is still available.",
    ) : AppError(title, message)

    data class CameraBusy(
        override val title: String = "Camera Busy",
        override val message: String =
            "Another app is currently using the camera. Close it and try again.",
    ) : AppError(title, message)

    data class CameraMissing(
        override val title: String = "Camera Not Found",
        override val message: String =
            "CreatorCam could not find the required camera on this device.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class MicUnavailable(
        override val title: String = "Microphone Unavailable",
        override val message: String =
            "Please close other apps using the microphone, or continue without audio.",
    ) : AppError(title, message)

    data class PermissionRequired(
        val permissionLabel: String,
        override val title: String = "Permission Required",
        override val message: String =
            "CreatorCam needs $permissionLabel to record. You can enable it in Android settings.",
    ) : AppError(title, message)

    data class StorageTooLow(
        override val title: String = "Storage Too Low",
        override val message: String =
            "Free more storage before recording. Dual-camera video needs room for two streams plus the final file.",
    ) : AppError(title, message)

    data class BatteryLow(
        override val title: String = "Battery Low",
        override val message: String =
            "Battery is below 15%. A long recording may stop unexpectedly.",
    ) : AppError(title, message)

    data class ThermalWarning(
        override val title: String = "Device Too Hot",
        override val message: String =
            "Your device is overheating. Recording has been stopped safely to protect your video.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class RecordingFailed(
        override val title: String = "Recording Failed",
        override val message: String =
            "The camera stopped unexpectedly. Anything captured so far has been saved if possible.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class ExportFailed(
        override val title: String = "Export Failed",
        override val message: String =
            "The final video could not be assembled. Your original camera files were kept so you can retry.",
        override val recoverable: Boolean = false,
    ) : AppError(title, message, recoverable)

    data class UnsupportedCombo(
        override val title: String = "Unsupported Combination",
        override val message: String =
            "This device cannot run that resolution and frame-rate combination on both cameras. " +
                "The recommended quality has been selected instead.",
    ) : AppError(title, message)
}
