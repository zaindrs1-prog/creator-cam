package com.creatorcam.app.recording

import android.net.Uri
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.util.AppError

/** UI-observable state of the whole capture pipeline. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data class Countdown(val secondsLeft: Int) : RecordingState
    data class Starting(val step: String) : RecordingState
    data class Recording(
        val startedElapsedMs: Long,
        val dual: Boolean,
        val outputLabel: String,
    ) : RecordingState
    data class Stopping(val step: String) : RecordingState
    data class Composing(val progress: Float) : RecordingState
    data class Done(
        val uri: Uri,
        val durationMs: Long,
        val sizeBytes: Long,
        val label: String,
    ) : RecordingState
    data class Failed(val error: AppError, val partialUri: Uri? = null) : RecordingState
}

data class RecordOptions(
    val layout: DualLayout,
    val mirrorFront: Boolean,
    val micEnabled: Boolean,
    val portrait: Boolean,
    val outputLongEdge: Int,
    val fps: Int,
    val watermarkText: String?,
    val dateTimeOverlay: Boolean,
    val startedWallClockMs: Long,
    val keepSourceFiles: Boolean,
)

data class RecordResult(
    val uri: Uri?,
    val durationMs: Long,
    val sizeBytes: Long,
    val error: AppError?,
)
