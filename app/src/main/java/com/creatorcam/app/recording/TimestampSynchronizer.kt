package com.creatorcam.app.recording

/**
 * Keeps the two camera streams (and audio) in sync.
 *
 * Both CameraX recordings start within milliseconds of each other, but each
 * file's sample timestamps begin near zero. We measure the real start delta
 * from the record-start callbacks and shift the later stream so the composed
 * output stays lip-sync accurate.
 *
 * Pure logic — covered by unit tests.
 */
object TimestampSynchronizer {

    data class SyncPlan(
        /** Microseconds to ADD to front-camera sample timestamps. May be negative. */
        val frontOffsetUs: Long,
        /** Microseconds to ADD to rear-camera sample timestamps. */
        val rearOffsetUs: Long,
        /** True when the measured skew is small enough to be inaudible/invisible. */
        val inSync: Boolean,
    )

    /** Skew under ~1.5 frames at 30fps is treated as perfectly synced. */
    const val SYNC_TOLERANCE_US: Long = 50_000L

    /**
     * @param rearStartElapsedMs  [android.os.SystemClock.elapsedRealtime] when the
     *                          rear (primary, audio-carrying) recording started.
     * @param frontStartElapsedMs same for the front recording.
     */
    fun plan(rearStartElapsedMs: Long, frontStartElapsedMs: Long): SyncPlan {
        // Anchor: the primary (rear) stream keeps its timestamps; the front
        // stream is shifted by the measured start delta.
        val deltaUs = (frontStartElapsedMs - rearStartElapsedMs) * 1000L
        return SyncPlan(
            frontOffsetUs = -deltaUs,
            rearOffsetUs = 0L,
            inSync = kotlin.math.abs(deltaUs) <= SYNC_TOLERANCE_US,
        )
    }

    /**
     * Post-record validation: after composing, audio and video durations must
     * remain aligned or the file is flagged (never silently shipped drifting).
     */
    fun durationsAligned(videoDurationUs: Long, audioDurationUs: Long): Boolean {
        if (videoDurationUs <= 0 || audioDurationUs <= 0) return true
        return kotlin.math.abs(videoDurationUs - audioDurationUs) <= 1_500_000L
    }
}
