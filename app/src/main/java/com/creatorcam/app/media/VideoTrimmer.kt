package com.creatorcam.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.creatorcam.app.util.Logger
import java.io.File
import java.nio.ByteBuffer

/**
 * Fast, lossless trim (+ mute) via MediaExtractor → MediaMuxer sample copy.
 * No re-encode: quality is untouched and a trim takes seconds.
 *
 * Cuts are keyframe-accurate on the video track (standard for lossless
 * trimmers); the requested start snaps back to the previous sync frame.
 */
object VideoTrimmer {

    data class TrimResult(
        val success: Boolean,
        val error: Throwable? = null,
        val durationMs: Long = 0,
    )

    fun trim(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        output: File,
        mute: Boolean,
    ): TrimResult {
        val startUs = (startMs.coerceAtLeast(0L)) * 1000L
        val endUs = (endMs.coerceAtLeast(startMs + 500L)) * 1000L
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, source, null)

            val trackMap = mutableMapOf<Int, Int>() // extractor index -> muxer index
            var maxInputSize = 512 * 1024
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mute && mime.startsWith("audio/")) continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                trackMap[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (trackMap.isEmpty()) {
                return TrimResult(false, IllegalStateException("No copyable tracks"))
            }
            muxer.setOrientationHint(probeRotation(context, source))
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(maxInputSize.coerceAtMost(4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var videoBaseUs: Long? = null
            var audioBaseUs: Long? = null

            for ((extIndex, muxIndex) in trackMap) {
                extractor.unselectTrack(extIndex)
            }
            val entries = trackMap.entries.toList()
            for ((extIndex, muxIndex) in entries) {
                extractor.selectTrack(extIndex)
                val mime = extractor.getTrackFormat(extIndex)
                    .getString(MediaFormat.KEY_MIME) ?: ""
                val isVideo = mime.startsWith("video/")
                if (isVideo) {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                } else {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                var base: Long? = null
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val pts = extractor.sampleTime
                    if (pts >= endUs) break
                    if (base == null) base = pts
                    info.set(0, size, pts - base, extractor.sampleFlags)
                    muxer.writeSampleData(muxIndex, buffer, info)
                    extractor.advance()
                }
                if (isVideo) videoBaseUs = base else audioBaseUs = base
                extractor.unselectTrack(extIndex)
            }
            muxer.stop()
            val durationMs = ((endUs - startUs) / 1000).coerceAtLeast(0L)
            Logger.i("Trim", "Trimmed ${output.name} ${startMs}..${endMs} mute=$mute")
            TrimResult(true, durationMs = durationMs)
        } catch (e: Exception) {
            Logger.e("Trim", "Trim failed", e)
            runCatching { output.delete() }
            TrimResult(false, e)
        } finally {
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun probeRotation(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }
}
