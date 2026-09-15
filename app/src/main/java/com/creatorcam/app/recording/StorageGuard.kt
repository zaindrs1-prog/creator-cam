package com.creatorcam.app.recording

import com.creatorcam.app.settings.VideoResolution
import com.creatorcam.app.util.TimeFormat
import java.io.File

/**
 * Preflight + in-recording storage safety. Dual recording needs room for two
 * source streams plus the composed final file, so estimates assume ~2.2x a
 * single stream. We would rather refuse to start than corrupt a take.
 */
object StorageGuard {

    /** Below this we warn; recording may continue. */
    const val WARN_BYTES: Long = 1L * 1024 * 1024 * 1024

    /** Below this we stop safely instead of corrupting the file. */
    const val CRITICAL_BYTES: Long = 250L * 1024 * 1024

    /** Conservative bytes-per-minute per stream (video + container overhead). */
    fun bytesPerMinutePerStream(resolution: VideoResolution): Long = when (resolution) {
        VideoResolution.UHD_4K -> 450L * 1024 * 1024
        VideoResolution.FULL_HD_1080P -> 180L * 1024 * 1024
        VideoResolution.HD_720P -> 90L * 1024 * 1024
        VideoResolution.AUTO -> 180L * 1024 * 1024
    }

    fun estimateDualTenMinutes(resolution: VideoResolution): Long =
        (bytesPerMinutePerStream(resolution) * 2.2 * 10).toLong()

    data class Preflight(
        val availableBytes: Long,
        val estimatedTenMinutesBytes: Long,
        val ready: Boolean,
        val statusLine: String,
    )

    fun preflight(stagingDir: File, resolution: VideoResolution, dual: Boolean): Preflight {
        val available = stagingDir.usableSpace
        val estimate = if (dual) estimateDualTenMinutes(resolution)
        else (bytesPerMinutePerStream(resolution) * 1.2 * 10).toLong()
        val ready = available >= CRITICAL_BYTES * 2 && available >= estimate / 5
        val status = if (ready) {
            "Ready — 10 min ≈ ${TimeFormat.bytes(estimate)} • available ${TimeFormat.bytes(available)}"
        } else {
            "Low — 10 min ≈ ${TimeFormat.bytes(estimate)} • available ${TimeFormat.bytes(available)}"
        }
        return Preflight(available, estimate, ready, status)
    }

    fun shouldWarn(dir: File): Boolean = dir.usableSpace < WARN_BYTES
    fun shouldStop(dir: File): Boolean = dir.usableSpace < CRITICAL_BYTES
}
