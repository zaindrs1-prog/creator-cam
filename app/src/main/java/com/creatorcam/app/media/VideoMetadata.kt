package com.creatorcam.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File

data class VideoMetadata(
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
) {
    companion object {
        fun of(context: Context, uri: Uri): VideoMetadata {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                extract(retriever, sizeBytes = querySize(context, uri))
            } catch (e: Exception) {
                VideoMetadata(0L, 0L, 0, 0, 0)
            } finally {
                runCatching { retriever.release() }
            }
        }

        fun of(file: File): VideoMetadata {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.path)
                extract(retriever, file.length())
            } catch (e: Exception) {
                VideoMetadata(0L, file.length(), 0, 0, 0)
            } finally {
                runCatching { retriever.release() }
            }
        }

        private fun querySize(context: Context, uri: Uri): Long {
            return runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media.SIZE),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
            }.getOrDefault(0L)
        }

        private fun extract(
            retriever: MediaMetadataRetriever,
            sizeBytes: Long,
        ): VideoMetadata = VideoMetadata(
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L,
            sizeBytes = sizeBytes,
            width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0,
            height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0,
            rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0,
        )
    }
}
