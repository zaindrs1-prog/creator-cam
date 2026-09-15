package com.creatorcam.app.media

import android.net.Uri

/** One row in My Recordings, backed by a MediaStore entry. */
data class RecordingItem(
    val uri: Uri,
    val id: Long,
    val displayName: String,
    val dateTakenMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
) {
    val resolutionLabel: String
        get() {
            val long = maxOf(width, height)
            return when {
                long >= 3840 -> "4K"
                long >= 1920 -> "1080p"
                long >= 1280 -> "720p"
                width > 0 -> "${width}×$height"
                else -> "—"
            }
        }
}
