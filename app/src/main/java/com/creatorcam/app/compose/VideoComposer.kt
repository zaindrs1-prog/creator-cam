package com.creatorcam.app.compose

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.creatorcam.app.recording.TimestampSynchronizer
import com.creatorcam.app.util.Logger
import java.io.File
import java.nio.ByteBuffer

data class ComposeInput(
    val file: File,
    val region: Region,
    val mirror: Boolean,
    /** Microseconds to add to this input's sample timestamps (sync shift). */
    val timestampOffsetUs: Long,
    val providesAudio: Boolean,
)

data class ComposeRequest(
    val inputs: List<ComposeInput>,
    /** Index into [inputs] whose audio track is muxed, or null for silent. */
    val audioInputIndex: Int?,
    val outputFile: File,
    val outputWidth: Int,
    val outputHeight: Int,
    val fps: Int,
    val bitrate: Int? = null,
    val overlays: List<OverlayLayer> = emptyList(),
)

data class ComposeResult(
    val success: Boolean,
    val error: Throwable? = null,
    val videoDurationUs: Long = 0,
    val audioDurationUs: Long = 0,
    val durationsAligned: Boolean = true,
)

/**
 * Fuses 1–2 camera files into a single MP4: hardware decoders → shared EGL
 * context → layout renderer → AVC encoder → MediaMuxer, with the primary
 * audio track copied losslessly (no re-encode, no drift).
 *
 * Runs entirely on the calling thread (must be a background thread).
 * Frame pacing uses a fixed output cadence; decoded frames are sampled at
 * "latest frame at or before target PTS + per-input sync offset", so streams
 * with different frame rates or start skew stay aligned.
 */
class VideoComposer {

    fun compose(request: ComposeRequest, onProgress: (Float) -> Unit): ComposeResult {
        if (request.inputs.isEmpty()) {
            return ComposeResult(false, IllegalArgumentException("No inputs"))
        }
        val session = Session(request, onProgress)
        return try {
            session.run()
        } catch (e: Exception) {
            Logger.e("Compose", "Composition failed", e)
            ComposeResult(false, e)
        } finally {
            session.release()
        }
    }

    private class Session(
        private val request: ComposeRequest,
        private val onProgress: (Float) -> Unit,
    ) {
        private data class Input(
            val spec: ComposeInput,
            val extractor: MediaExtractor,
            val videoTrack: Int,
            val format: MediaFormat,
            val width: Int,
            val height: Int,
            val rotation: Int,
            val durationUs: Long,
            val displayAspect: Float,
            var decoder: MediaCodec? = null,
            var surface: Surface? = null,
            var surfaceTexture: SurfaceTexture? = null,
            var texId: Int = 0,
            var inputEos: Boolean = false,
            var outputEos: Boolean = false,
            var pendingIndex: Int = -1,
            var pendingInfo: MediaCodec.BufferInfo? = null,
            var hasFrame: Boolean = false,
            val frameLock: Any = Any(),
            var frameAvailable: Boolean = false,
            val stMatrix: FloatArray = FloatArray(16),
        )

        private var muxer: MediaMuxer? = null
        private var encoder: MediaCodec? = null
        private var encoderSurface: Surface? = null
        private var egl: EglCore? = null
        private var renderer: GlComposerRenderer? = null
        private var frameThread: HandlerThread? = null
        private var videoTrackIndex = -1
        private var audioTrackIndex = -1
        private var muxerStarted = false
        private var eosSpins = 0
        private var pendingAudioFormat: MediaFormat? = null
        private val inputs = mutableListOf<Input>()
        private val overlayTexIds = mutableListOf<Int>()

        fun run(): ComposeResult {
            val outW = request.outputWidth
            val outH = request.outputHeight
            val fps = request.fps.coerceIn(15, 60)
            val frameDurUs = 1_000_000L / fps

            openInputs()
            if (inputs.isEmpty()) {
                return ComposeResult(false, IllegalStateException("No decodable inputs"))
            }
            val maxDurUs = inputs.maxOf { it.durationUs + it.spec.timestampOffsetUs }
                .coerceAtLeast(frameDurUs)
            val totalFrames = ((maxDurUs + frameDurUs - 1) / frameDurUs).toInt().coerceAtLeast(1)
            Logger.i(
                "Compose",
                "Composing ${inputs.size} inputs -> ${outW}x$outH@${fps} " +
                    "frames=$totalFrames dur=${maxDurUs / 1000}ms",
            )

            pendingAudioFormat = probeAudioFormat()
            setupEncoderMuxer(outW, outH, fps)
            setupGl()

            // Pre-roll: make sure every decoder has produced its first frame
            // so output frame 0 is never black.
            for (preroll in 0 until 40) {
                var allReady = true
                for (input in inputs) {
                    if (!input.hasFrame) {
                        pumpTo(input, 0L, frameDurUs)
                        if (!input.hasFrame) allReady = false
                    }
                }
                if (allReady) break
                Thread.sleep(5)
            }

            var lastProgress = -1
            for (n in 0 until totalFrames) {
                val targetUs = n * frameDurUs
                for (input in inputs) pumpTo(input, targetUs, frameDurUs)
                renderFrame(outW, outH)
                egl!!.setPresentationTimeNs(targetUs * 1000L)
                egl!!.swapBuffers()
                drainEncoder(endOfStream = false)
                val progress = ((n + 1) * 90 / totalFrames).coerceIn(0, 90)
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress / 100f)
                }
            }
            encoder!!.signalEndOfInputStream()
            drainEncoder(endOfStream = true)
            onProgress(0.93f)

            val audioDurUs = copyAudio(pendingAudioFormat)
            onProgress(1f)

            val aligned = TimestampSynchronizer.durationsAligned(maxDurUs, audioDurUs)
            if (!aligned) {
                Logger.w("Compose", "A/V durations diverged: video=$maxDurUs audio=$audioDurUs")
            }
            val outFile = request.outputFile
            if (!outFile.exists() || outFile.length() == 0L) {
                return ComposeResult(false, IllegalStateException("Empty output"))
            }
            Logger.i("Compose", "Done: ${outFile.length()} bytes")
            return ComposeResult(
                success = true,
                videoDurationUs = maxDurUs,
                audioDurationUs = audioDurUs,
                durationsAligned = aligned,
            )
        }

        // ------------------------------------------------------------ setup

        private fun openInputs() {
            for (spec in request.inputs) {
                try {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(spec.file.path)
                    val videoTrack = findTrack(extractor, "video/")
                        ?: throw IllegalStateException("No video track in ${spec.file.name}")
                    val format = extractor.getTrackFormat(videoTrack)
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        format.getInteger(MediaFormat.KEY_ROTATION)
                    } else 0
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    if (durationUs <= 0) throw IllegalStateException("Empty video track")
                    val (dw, dh) = if (rotation == 90 || rotation == 270) h to w else w to h
                    extractor.selectTrack(videoTrack)
                    inputs += Input(
                        spec = spec,
                        extractor = extractor,
                        videoTrack = videoTrack,
                        format = format,
                        width = w,
                        height = h,
                        rotation = rotation,
                        durationUs = durationUs,
                        displayAspect = dw.toFloat() / dh.coerceAtLeast(1).toFloat(),
                    )
                    Logger.d(
                        "Compose",
                        "Input ${spec.file.name}: ${w}x$h rot=$rotation " +
                            "dur=${durationUs / 1000}ms off=${spec.timestampOffsetUs}",
                    )
                } catch (e: Exception) {
                    Logger.e("Compose", "Skipping undecodable input ${spec.file.name}", e)
                }
            }
        }

        private fun probeAudioFormat(): MediaFormat? {
            val index = request.audioInputIndex ?: return null
            val spec = request.inputs.getOrNull(index) ?: return null
            if (!spec.providesAudio) return null
            return try {
                val extractor = MediaExtractor()
                extractor.setDataSource(spec.file.path)
                val track = findTrack(extractor, "audio/")
                val format = track?.let { extractor.getTrackFormat(it) }
                extractor.release()
                format
            } catch (e: Exception) {
                Logger.w("Compose", "Audio probe failed; output will be silent", e)
                null
            }
        }

        private fun setupEncoderMuxer(outW: Int, outH: Int, fps: Int) {
            muxer = MediaMuxer(request.outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // ~0.12 bits per pixel, clamped to sane mobile-encoder bounds.
            val bitrate = request.bitrate
                ?: (outW.toLong() * outH * fps * 0.12).toInt().coerceIn(3_000_000, 45_000_000)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            Logger.d("Compose", "Encoder ${outW}x$outH bitrate=$bitrate")
        }

        private fun setupGl() {
            val eglCore = EglCore()
            eglCore.init(encoderSurface!!)
            egl = eglCore
            val gl = GlComposerRenderer()
            gl.init()
            renderer = gl

            val thread = HandlerThread("ComposeFrames").apply { start() }
            frameThread = thread
            val handler = Handler(thread.looper)
            for (input in inputs) {
                input.texId = gl.createOesTexture()
                val st = SurfaceTexture(input.texId)
                st.setDefaultBufferSize(input.width, input.height)
                st.setOnFrameAvailableListener(
                    {
                        synchronized(input.frameLock) {
                            input.frameAvailable = true
                            (input.frameLock as Object).notifyAll()
                        }
                    },
                    handler,
                )
                input.surfaceTexture = st
                val surface = Surface(st)
                input.surface = surface
                val mime = input.format.getString(MediaFormat.KEY_MIME)
                    ?: MediaFormat.MIMETYPE_VIDEO_AVC
                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(input.format, surface, null, 0)
                decoder.start()
                input.decoder = decoder
            }
            for (overlay in request.overlays) {
                overlayTexIds += gl.uploadOverlay(overlay.bitmap)
            }
        }

        // ------------------------------------------------------------ decode

        /** Decodes + renders every frame at or before [targetUs] (plus offset). */
        private fun pumpTo(input: Input, targetUs: Long, frameDurUs: Long) {
            val decoder = input.decoder ?: return
            // Feed input: everything up to one frame past the target, bounded
            // per tick so memory stays flat on 60fps sources.
            var fed = 0
            while (!input.inputEos && fed < 8) {
                val nextPts = input.extractor.sampleTime
                if (nextPts < 0) {
                    val inIndex = decoder.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    decoder.queueInputBuffer(
                        inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    input.inputEos = true
                    break
                }
                if (nextPts + input.spec.timestampOffsetUs > targetUs + frameDurUs) break
                val inIndex = decoder.dequeueInputBuffer(0)
                if (inIndex < 0) break
                val buffer = decoder.getInputBuffer(inIndex) ?: break
                val size = input.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(
                        inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    input.inputEos = true
                } else {
                    decoder.queueInputBuffer(
                        inIndex, 0, size, input.extractor.sampleTime,
                        input.extractor.sampleFlags,
                    )
                    input.extractor.advance()
                    fed++
                }
            }
            // Drain output until we pass the target (holding one pending frame).
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (input.pendingIndex >= 0) {
                    val pts = (input.pendingInfo!!.presentationTimeUs +
                        input.spec.timestampOffsetUs).coerceAtLeast(0L)
                    if (pts > targetUs || input.outputEos) return
                    decoder.releaseOutputBuffer(input.pendingIndex, true)
                    input.pendingIndex = -1
                    awaitFrame(input)
                    continue
                }
                if (input.outputEos) return
                val outIndex = decoder.dequeueOutputBuffer(info, 0)
                when {
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            input.outputEos = true
                            decoder.releaseOutputBuffer(outIndex, false)
                            return
                        }
                        val pts = (info.presentationTimeUs +
                            input.spec.timestampOffsetUs).coerceAtLeast(0L)
                        if (pts <= targetUs) {
                            decoder.releaseOutputBuffer(outIndex, true)
                            awaitFrame(input)
                        } else {
                            input.pendingIndex = outIndex
                            input.pendingInfo = MediaCodec.BufferInfo().apply {
                                set(info.offset, info.size, info.presentationTimeUs, info.flags)
                            }
                            return
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> return // TRY_AGAIN_LATER
                }
            }
        }

        private fun awaitFrame(input: Input) {
            val deadline = System.currentTimeMillis() + 1500
            synchronized(input.frameLock) {
                while (!input.frameAvailable) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        Logger.w("Compose", "Frame wait timed out; holding last frame")
                        return
                    }
                    (input.frameLock as Object).wait(remaining)
                }
                input.frameAvailable = false
            }
            input.surfaceTexture?.updateTexImage()
            input.surfaceTexture?.getTransformMatrix(input.stMatrix)
            input.hasFrame = true
        }

        // ------------------------------------------------------------ render

        private fun renderFrame(outW: Int, outH: Int) {
            val gl = renderer!!
            gl.clearFrame()
            // Largest region first so PiP windows always land on top of the
            // fullscreen layer, whichever camera owns it.
            val ordered = inputs.sortedByDescending {
                it.spec.region.w * it.spec.region.h
            }
            for (input in ordered) {
                if (!input.hasFrame) continue
                val r = input.spec.region
                val vx = (r.x * outW).toInt().coerceIn(0, outW - 1)
                val vw = (r.w * outW).toInt().coerceIn(1, outW - vx)
                val vh = (r.h * outH).toInt().coerceIn(1, outH)
                val vy = (outH - (r.y * outH).toInt() - vh).coerceIn(0, outH - 1)
                gl.drawVideoFrame(
                    texId = input.texId,
                    stMatrix = input.stMatrix,
                    rotation = input.rotation,
                    mirror = input.spec.mirror,
                    displayAspect = input.displayAspect,
                    viewportPx = intArrayOf(vx, vy, vw, vh),
                    shape = r.shape,
                    cornerRadiusFraction = r.cornerRadius,
                )
            }
            request.overlays.forEachIndexed { index, overlay ->
                val vx = (overlay.x * outW).toInt()
                val vw = (overlay.w * outW).toInt().coerceAtLeast(1)
                val vh = (overlay.h * outH).toInt().coerceAtLeast(1)
                val vy = (outH - (overlay.y * outH).toInt() - vh)
                gl.drawOverlay(
                    overlayTexIds[index], overlay.alpha, intArrayOf(vx, vy, vw, vh)
                )
            }
        }

        // ------------------------------------------------------------ muxing

        private fun drainEncoder(endOfStream: Boolean) {
            val codec = encoder ?: return
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(info, 0)
                when {
                    outIndex >= 0 -> {
                        val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (isEos) {
                            codec.releaseOutputBuffer(outIndex, false)
                            return
                        }
                        if (info.size > 0 && muxerStarted) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(videoTrackIndex, buffer, info)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                            pendingAudioFormat?.let {
                                audioTrackIndex = muxer!!.addTrack(it)
                            }
                            muxer!!.start()
                            muxerStarted = true
                        }
                    }
                    else -> {
                        if (!endOfStream) return
                        // After EOS signal, spin briefly until the EOS flag arrives.
                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            Thread.sleep(10)
                            eosSpins++
                            if (eosSpins > 500) return
                        } else return
                    }
                }
            }
        }

        private fun copyAudio(knownFormat: MediaFormat?): Long {
            val index = request.audioInputIndex
            if (index == null || knownFormat == null || !muxerStarted || audioTrackIndex < 0) {
                return 0L
            }
            val spec = request.inputs[index]
            var lastPts = 0L
            var extractor: MediaExtractor? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(spec.file.path)
                val track = findTrack(extractor, "audio/") ?: return 0L
                extractor.selectTrack(track)
                val buffer = ByteBuffer.allocateDirect(256 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    lastPts = extractor.sampleTime
                    muxer!!.writeSampleData(audioTrackIndex, buffer, info)
                    extractor.advance()
                }
            } catch (e: Exception) {
                Logger.w("Compose", "Audio copy hit an error; video is intact", e)
            } finally {
                runCatching { extractor?.release() }
            }
            return lastPts
        }

        fun release() {
            for (input in inputs) {
                runCatching { input.decoder?.stop() }
                runCatching { input.decoder?.release() }
                runCatching { input.surface?.release() }
                runCatching { input.surfaceTexture?.release() }
                runCatching { input.extractor.release() }
                if (input.texId != 0) runCatching {
                    renderer?.deleteTexture(input.texId, true)
                }
            }
            overlayTexIds.forEach { runCatching { renderer?.deleteTexture(it, false) } }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { encoderSurface?.release() }
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { frameThread?.quitSafely() }
            request.overlays.forEach { runCatching { it.bitmap.recycle() } }
        }

        private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith(prefix)) return i
            }
            return null
        }
    }
}
