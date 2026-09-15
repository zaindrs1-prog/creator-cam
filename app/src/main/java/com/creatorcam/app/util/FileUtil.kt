package com.creatorcam.app.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object FileUtil {

    /**
     * App-private staging directory on external storage (no permission needed,
     * plenty of room for large video segments).
     */
    fun stagingDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(base, "CreatorCam/.staging").apply { mkdirs() }
    }

    fun newStagingFile(context: Context, prefix: String): File {
        val dir = stagingDir(context)
        return File(dir, "${prefix}_${TimeFormat.fileTimestamp()}_${(0..9999).random()}.mp4")
    }

    fun copyToStream(file: File, out: OutputStream) {
        FileInputStream(file).use { input -> input.copyTo(out) }
    }

    fun deleteQuietly(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Logger.w("File", "Could not delete ${file.name}", it) }
    }

    /** Best-effort cleanup of stale staging files older than [maxAgeMs]. */
    fun pruneStaging(context: Context, maxAgeMs: Long = 48L * 60 * 60 * 1000) {
        val dir = stagingDir(context)
        val files = dir.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        files.forEach { if (it.lastModified() < cutoff) deleteQuietly(it) }
    }
}
