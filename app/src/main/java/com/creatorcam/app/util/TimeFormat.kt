package com.creatorcam.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormat {

    /** 00:00:00 style recording timer. */
    fun recordingClock(elapsedMs: Long): String {
        val safe = elapsedMs.coerceAtLeast(0L)
        val h = TimeUnit.MILLISECONDS.toHours(safe)
        val m = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** Compact media duration, e.g. 4:32. */
    fun mediaDuration(durationMs: Long): String {
        val safe = durationMs.coerceAtLeast(0L)
        val m = TimeUnit.MILLISECONDS.toMinutes(safe)
        val s = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    fun fileTimestamp(whenMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(whenMs))

    fun displayDate(whenMs: Long): String =
        SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(Date(whenMs))

    fun overlayDate(whenMs: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(whenMs))

    fun overlayTime(whenMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(whenMs))

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
