package com.creatorcam.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.creatorcam.app.camera.DualCameraSession
import com.creatorcam.app.camera.SingleCameraSession
import com.creatorcam.app.compose.ComposeInput
import com.creatorcam.app.compose.ComposeRequest
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.compose.LayoutSpec
import com.creatorcam.app.compose.OverlayFactory
import com.creatorcam.app.compose.VideoComposer
import com.creatorcam.app.media.MediaStoreSaver
import com.creatorcam.app.media.RecordingItem
import com.creatorcam.app.media.VideoMetadata
import com.creatorcam.app.settings.VideoResolution
import com.creatorcam.app.util.AppError
import com.creatorcam.app.util.FileUtil
import com.creatorcam.app.util.Logger
import com.creatorcam.app.util.TimeFormat
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates the full dual-capture pipeline:
 *
 *  preflight (storage/battery/mic) → countdown → start both encoders with
 *  measured timestamps → monitor (storage/thermal) → stop → finalize both
 *  files → measured-offset A/V sync → OpenGL composition → MediaStore save.
 *
 * Audio is captured only by the rear (primary) recording to avoid two
 * consumers fighting for the microphone; the front file is video-only and the
 * composer muxes the primary audio track into the final file.
 *
 * All long work runs on Dispatchers.IO; state is exposed for the UI.
 */
class DualRecordingController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    private val thermal = BatteryThermalMonitor(context)
    private val saver = MediaStoreSaver(context)
    private val composer = VideoComposer()

    private var session: ActiveSession? = null
    private var monitorJob: Job? = null
    /** Bumped on every cancel so a stale countdown can never start a take. */
    private var generation = 0
    private val mainExecutor: Executor by lazy {
        ContextCompat.getMainExecutor(context)
    }

    private data class ActiveSession(
        val rearRecording: Recording?,
        val frontRecording: Recording?,
        val rearFile: File,
        val frontFile: File?,
        val rearStartDeferred: CompletableDeferred<Long>,
        val frontStartDeferred: CompletableDeferred<Long>,
        val rearFinalize: CompletableDeferred<FinalizeInfo>,
        val frontFinalize: CompletableDeferred<FinalizeInfo>?,
        val recordJob: CompletableJob,
    )

    private data class FinalizeInfo(val ok: Boolean, val errorCode: Int, val cause: Throwable?)

    val isBusy: Boolean
        get() = _state.value !is RecordingState.Idle &&
            _state.value !is RecordingState.Done &&
            _state.value !is RecordingState.Failed

    val isRecording: Boolean get() = _state.value is RecordingState.Recording

    // ------------------------------------------------------------ dual path

    /**
     * Runs a complete dual recording. Suspends until the final file is saved
     * (or the pipeline fails). Call [requestStop] to end the take.
     */
    suspend fun recordDual(
        bound: DualCameraSession.BoundDual,
        options: RecordOptions,
        resolution: VideoResolution,
        countdownSeconds: Int,
    ): Unit = withContext(Dispatchers.Main) {
        if (isBusy) return@withContext
        FileUtil.pruneStaging(context)
        val myGeneration = ++generation

        _state.value = RecordingState.Starting("Checking storage")
        val staging = FileUtil.stagingDir(context)
        val preflight = StorageGuard.preflight(staging, resolution, dual = true)
        if (!preflight.ready) {
            _state.value = RecordingState.Failed(AppError.StorageTooLow())
            return@withContext
        }
        val battery = thermal.batteryPercent()
        if (battery in 1..14 && !thermal.isCharging()) {
            // Warn but continue — the UI shows the warning badge.
            Logger.w("Record", "Low battery at start: $battery%")
        }
        val micOk = options.micEnabled && hasAudioPermission() && probeMicWithRetry()
        if (options.micEnabled && !micOk) {
            Logger.w("Record", "Mic unavailable; continuing silent")
        }

        if (countdownSeconds > 0) {
            for (s in countdownSeconds downTo 1) {
                if (myGeneration != generation) return@withContext
                _state.value = RecordingState.Countdown(s)
                delay(1000)
            }
        }
        if (myGeneration != generation) return@withContext

        _state.value = RecordingState.Starting("Preparing cameras")
        val rearFile = FileUtil.newStagingFile(context, "rear")
        val frontFile = FileUtil.newStagingFile(context, "front")
        try {
            val rearStart = CompletableDeferred<Long>()
            val frontStart = CompletableDeferred<Long>()
            val rearFin = CompletableDeferred<FinalizeInfo>()
            val frontFin = CompletableDeferred<FinalizeInfo>()

            val rearRecording = startStream(
                bound.rearVideo, rearFile, withAudio = micOk,
                onStart = { rearStart.complete(SystemClock.elapsedRealtime()) },
                onFinalize = { rearFin.complete(it) },
            )
            // Start the second stream back-to-back; the measured delta feeds sync.
            val frontRecording = startStream(
                bound.frontVideo, frontFile, withAudio = false,
                onStart = { frontStart.complete(SystemClock.elapsedRealtime()) },
                onFinalize = { frontFin.complete(it) },
            )
            val startedMs = SystemClock.elapsedRealtime()
            val job = Job()
            session = ActiveSession(
                rearRecording, frontRecording, rearFile, frontFile,
                rearStart, frontStart, rearFin, frontFin, job,
            )
            _state.value = RecordingState.Recording(
                startedElapsedMs = startedMs,
                dual = true,
                outputLabel = options.layout.title,
            )
            startMonitors(staging)
            Logger.i("Record", "Dual recording started (audio=$micOk)")

            // Suspend here until requestStop()/requestCancel() completes the job.
            withContext(Dispatchers.IO) { job.join() }
            finishDual(options, resolution, micOk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Record", "Dual recording failed", e)
            abortSession()
            _state.value = RecordingState.Failed(mapFailure(e))
        } finally {
            monitorJob?.cancel()
            monitorJob = null
        }
    }

    /**
     * Ends the take and finalizes the final video. Safe to call any time;
     * during the countdown it cancels the take before anything is recorded.
     */
    fun requestStop() {
        val s = session
        if (s == null) {
            generation++
            if (_state.value !is RecordingState.Idle) {
                _state.value = RecordingState.Idle
            }
        } else {
            s.recordJob.complete()
        }
    }

    /** Aborts and discards staging files. */
    fun requestCancel() {
        generation++
        val s = session
        session = null
        monitorJob?.cancel()
        runCatching { s?.rearRecording?.stop() }
        runCatching { s?.frontRecording?.stop() }
        runCatching { s?.rearRecording?.close() }
        runCatching { s?.frontRecording?.close() }
        s?.recordJob?.cancel(CancellationException("cancelled"))
        FileUtil.deleteQuietly(s?.rearFile)
        FileUtil.deleteQuietly(s?.frontFile)
        _state.value = RecordingState.Idle
    }

    private suspend fun finishDual(
        options: RecordOptions,
        resolution: VideoResolution,
        audioExpected: Boolean,
    ) {
        val s = session
        session = null
        monitorJob?.cancel()
        if (s == null) {
            _state.value = RecordingState.Idle
            return
        }
        _state.value = RecordingState.Stopping("Finalizing camera files")
        runCatching { s.rearRecording?.stop() }
        runCatching { s.frontRecording?.stop() }

        val rearFin = withTimeoutOrNull(20_000) { s.rearFinalize.await() }
        val frontFin = withTimeoutOrNull(20_000) { s.frontFinalize?.await() }
        runCatching { s.rearRecording?.close() }
        runCatching { s.frontRecording?.close() }

        val rearStart = runCatching { s.rearStartDeferred.await() }.getOrNull()
        val frontStart = runCatching { s.frontStartDeferred.await() }.getOrNull()
        val sync = if (rearStart != null && frontStart != null) {
            TimestampSynchronizer.plan(rearStart, frontStart)
        } else {
            TimestampSynchronizer.plan(0L, 0L)
        }
        Logger.i(
            "Record",
            "Finalized rear ok=${rearFin?.ok} front ok=${frontFin?.ok} " +
                "skewUs=${-sync.frontOffsetUs} inSync=${sync.inSync}",
        )

        val rearValid = rearFin?.ok == true && s.rearFile.exists() && s.rearFile.length() > 0
        val frontValid = frontFin?.ok == true &&
            s.frontFile?.exists() == true && (s.frontFile?.length() ?: 0) > 0
        if (!rearValid && !frontValid) {
            cleanupStaging(s)
            _state.value = RecordingState.Failed(AppError.RecordingFailed())
            return
        }
        // Degraded but honest: if one stream failed, compose from the survivor
        // fullscreen rather than shipping a half-black frame.
        val layout = if (rearValid && frontValid) options.layout else DualLayout.FULLSCREEN_SINGLE

        _state.value = RecordingState.Composing(0f)
        try {
            val outFile = FileUtil.newStagingFile(context, "final")
            val (outW, outH) = outputSize(options, resolution)
            val request = buildComposeRequest(
                options = options,
                layout = layout,
                rearFile = if (rearValid) s.rearFile else null,
                frontFile = if (frontValid) s.frontFile else null,
                sync = sync,
                audioExpected = audioExpected && rearValid,
                outFile = outFile,
                outW = outW,
                outH = outH,
            )
            val result = withContext(Dispatchers.IO) {
                composer.compose(request) { p ->
                    _state.value = RecordingState.Composing(p)
                }
            }
            if (!result.success) {
                throw result.error ?: IllegalStateException("compose failed")
            }
            val meta = VideoMetadata.of(outFile)
            val uri = withContext(Dispatchers.IO) {
                saver.saveVideo(outFile, displayName(options))
            }
            FileUtil.deleteQuietly(outFile)
            if (options.keepSourceFiles) {
                // Preserve the per-camera originals in the gallery so the
                // editor can re-compose the take with a different layout.
                preserveSources(s, options)
            }
            cleanupStaging(s)
            Logger.i("Record", "Done: $uri duration=${meta.durationMs} size=${meta.sizeBytes}")
            _state.value = RecordingState.Done(
                uri = uri,
                durationMs = meta.durationMs,
                sizeBytes = meta.sizeBytes,
                label = "${outW}×$outH • ${options.layout.title}",
            )
        } catch (e: Exception) {
            Logger.e("Record", "Composition/export failed", e)
            // Keep sources so the user can retry — never silently delete takes.
            _state.value = RecordingState.Failed(AppError.ExportFailed())
        }
    }

    // ---------------------------------------------------------- single path

    suspend fun recordSingle(
        bound: SingleCameraSession.BoundSingle,
        options: RecordOptions,
        resolution: VideoResolution,
        countdownSeconds: Int,
        isFront: Boolean,
    ): Unit = withContext(Dispatchers.Main) {
        if (isBusy) return@withContext
        FileUtil.pruneStaging(context)
        val myGeneration = ++generation
        _state.value = RecordingState.Starting("Checking storage")
        val staging = FileUtil.stagingDir(context)
        if (!StorageGuard.preflight(staging, resolution, dual = false).ready) {
            _state.value = RecordingState.Failed(AppError.StorageTooLow())
            return@withContext
        }
        val micOk = options.micEnabled && hasAudioPermission() && probeMicWithRetry()
        if (options.micEnabled && !micOk) {
            Logger.w("Record", "Mic unavailable; continuing silent")
        }
        if (countdownSeconds > 0) {
            for (s in countdownSeconds downTo 1) {
                if (myGeneration != generation) return@withContext
                _state.value = RecordingState.Countdown(s)
                delay(1000)
            }
        }
        if (myGeneration != generation) return@withContext

        _state.value = RecordingState.Starting("Preparing camera")
        val file = FileUtil.newStagingFile(context, "single")
        try {
            val startD = CompletableDeferred<Long>()
            val finD = CompletableDeferred<FinalizeInfo>()
            val recording = startStream(
                bound.video, file, withAudio = micOk,
                onStart = { startD.complete(SystemClock.elapsedRealtime()) },
                onFinalize = { finD.complete(it) },
            )
            val job = Job()
            session = ActiveSession(
                recording, null, file, null,
                startD, CompletableDeferred<Long>().apply { complete(0L) },
                finD, null, job,
            )
            _state.value = RecordingState.Recording(
                startedElapsedMs = SystemClock.elapsedRealtime(),
                dual = false,
                outputLabel = if (isFront) "Front camera" else "Rear camera",
            )
            startMonitors(staging)
            withContext(Dispatchers.IO) { job.join() }

            session = null
            monitorJob?.cancel()
            _state.value = RecordingState.Stopping("Finalizing video")
            runCatching { recording.stop() }
            val fin = withTimeoutOrNull(20_000) { finD.await() }
            runCatching { recording.close() }
            if (fin?.ok != true || !file.exists() || file.length() == 0L) {
                FileUtil.deleteQuietly(file)
                _state.value = RecordingState.Failed(AppError.RecordingFailed())
                return@withContext
            }
            // Front selfies + overlays need a processing pass; otherwise the
            // encoder output is already the final file (no pointless re-encode).
            val needsProcessing = (isFront && options.mirrorFront) ||
                options.watermarkText != null || options.dateTimeOverlay
            val finalFile = if (needsProcessing) {
                _state.value = RecordingState.Composing(0f)
                val (outW, outH) = outputSize(options, resolution)
                val req = buildSingleComposeRequest(options, file, isFront, outW, outH, micOk)
                val res = withContext(Dispatchers.IO) {
                    composer.compose(req) { p -> _state.value = RecordingState.Composing(p) }
                }
                if (!res.success) throw res.error ?: IllegalStateException("compose failed")
                req.outputFile
            } else file
            val meta = VideoMetadata.of(finalFile)
            val uri = withContext(Dispatchers.IO) {
                saver.saveVideo(finalFile, displayName(options))
            }
            FileUtil.deleteQuietly(finalFile)
            if (needsProcessing) FileUtil.deleteQuietly(file)
            _state.value = RecordingState.Done(
                uri, meta.durationMs, meta.sizeBytes, "Single camera"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Record", "Single recording failed", e)
            abortSession()
            _state.value = RecordingState.Failed(mapFailure(e))
        } finally {
            monitorJob?.cancel()
        }
    }

    // -------------------------------------------------------------- internals

    private fun startStream(
        videoCapture: VideoCapture<Recorder>,
        file: File,
        withAudio: Boolean,
        onStart: () -> Unit,
        onFinalize: (FinalizeInfo) -> Unit,
    ): Recording {
        val pending = videoCapture.output.prepareRecording(
            context, FileOutputOptions.Builder(file).build()
        )
        if (withAudio) {
            pending.withAudioEnabled() // throws SecurityException without permission
        }
        return pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Logger.d("Record", "Stream started: ${file.name} audio=$withAudio")
                    onStart()
                }
                is VideoRecordEvent.Finalize -> {
                    Logger.i(
                        "Record",
                        "Stream finalized: ${file.name} error=${event.hasError()} " +
                            "code=${event.error}",
                    )
                    onFinalize(FinalizeInfo(!event.hasError(), event.error, event.cause))
                }
                is VideoRecordEvent.Status -> Unit // reserved for bitrate telemetry
                else -> Unit
            }
        }
    }

    private fun startMonitors(staging: File) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(2000)
                if (StorageGuard.shouldStop(staging)) {
                    Logger.w("Record", "Storage critical — stopping safely")
                    withContext(Dispatchers.Main) { requestStop() }
                    return@launch
                }
                val status = thermal.thermalStatus()
                if (thermal.mustStop(status)) {
                    Logger.w("Record", "Thermal severe — stopping safely")
                    withContext(Dispatchers.Main) { requestStop() }
                    return@launch
                }
            }
        }
    }

    private fun abortSession() {
        val s = session
        session = null
        runCatching { s?.rearRecording?.stop() }
        runCatching { s?.frontRecording?.stop() }
        runCatching { s?.rearRecording?.close() }
        runCatching { s?.frontRecording?.close() }
        s?.recordJob?.cancel()
        FileUtil.deleteQuietly(s?.rearFile)
        FileUtil.deleteQuietly(s?.frontFile)
    }

    private fun cleanupStaging(s: ActiveSession) {
        FileUtil.deleteQuietly(s.rearFile)
        FileUtil.deleteQuietly(s.frontFile)
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The idle level meter releases the mic just before a take; one retry
     * covers the handoff window so takes don't go silent spuriously.
     */
    private suspend fun probeMicWithRetry(): Boolean {
        if (MicrophoneProbe(context).available()) return true
        delay(400)
        return MicrophoneProbe(context).available()
    }

    private fun mapFailure(e: Exception): AppError = when (e) {
        is SecurityException -> AppError.PermissionRequired("Microphone")
        is IllegalStateException -> AppError.CameraBusy()
        else -> AppError.RecordingFailed()
    }

    private fun outputSize(options: RecordOptions, resolution: VideoResolution): Pair<Int, Int> {
        val long = when (resolution) {
            VideoResolution.UHD_4K -> 3840
            VideoResolution.FULL_HD_1080P -> 1920
            VideoResolution.HD_720P -> 1280
            VideoResolution.AUTO -> options.outputLongEdge.coerceIn(1280, 3840)
        }.let { (it / 2) * 2 }
        val short = (long * 9 / 16 / 2) * 2
        return if (options.portrait) short to long else long to short
    }

    private fun displayName(options: RecordOptions): String {
        val mode = if (options.layout == DualLayout.FULLSCREEN_SINGLE) "single" else "dual"
        return "CreatorCam_${mode}_${TimeFormat.fileTimestamp(options.startedWallClockMs)}.mp4"
    }

    private suspend fun preserveSources(s: ActiveSession, options: RecordOptions) {
        val base = displayName(options).removeSuffix(".mp4")
        if (s.rearFile.exists()) {
            runCatching {
                saver.saveVideo(s.rearFile, "${base}_camA_rear.mp4")
            }.onFailure { Logger.w("Record", "Could not preserve rear source", it) }
        }
        if (s.frontFile?.exists() == true) {
            runCatching {
                saver.saveVideo(s.frontFile, "${base}_camB_front.mp4")
            }.onFailure { Logger.w("Record", "Could not preserve front source", it) }
        }
    }

    private fun buildComposeRequest(
        options: RecordOptions,
        layout: DualLayout,
        rearFile: File?,
        frontFile: File?,
        sync: TimestampSynchronizer.SyncPlan,
        audioExpected: Boolean,
        outFile: File,
        outW: Int,
        outH: Int,
    ): ComposeRequest {
        val rects = LayoutSpec.rects(layout, outW, outH)
        val inputs = mutableListOf<ComposeInput>()
        var audioIndex: Int? = null
        if (rearFile != null) {
            audioIndex = 0
            inputs += ComposeInput(
                file = rearFile,
                region = rects.rear,
                mirror = false,
                timestampOffsetUs = sync.rearOffsetUs,
                providesAudio = audioExpected,
            )
        }
        if (frontFile != null) {
            inputs += ComposeInput(
                file = frontFile,
                region = rects.front ?: rects.rear,
                mirror = options.mirrorFront,
                timestampOffsetUs = sync.frontOffsetUs,
                providesAudio = false,
            )
            // Note: the front stream never carries audio (single mic owner).
        }
        return ComposeRequest(
            inputs = inputs,
            audioInputIndex = if (audioExpected && rearFile != null) audioIndex else null,
            outputFile = outFile,
            outputWidth = outW,
            outputHeight = outH,
            fps = 30,
            overlays = OverlayFactory.build(
                context = context,
                outW = outW,
                outH = outH,
                watermarkText = options.watermarkText,
                dateTimeMs = if (options.dateTimeOverlay) options.startedWallClockMs else null,
            ),
        )
    }

    private fun buildSingleComposeRequest(
        options: RecordOptions,
        file: File,
        isFront: Boolean,
        outW: Int,
        outH: Int,
        audioExpected: Boolean,
    ): ComposeRequest {
        val rects = LayoutSpec.rects(DualLayout.FULLSCREEN_SINGLE, outW, outH)
        return ComposeRequest(
            inputs = listOf(
                ComposeInput(
                    file = file,
                    region = rects.rear,
                    mirror = isFront && options.mirrorFront,
                    timestampOffsetUs = 0L,
                    providesAudio = audioExpected,
                )
            ),
            audioInputIndex = if (audioExpected) 0 else null,
            outputFile = FileUtil.newStagingFile(context, "final"),
            outputWidth = outW,
            outputHeight = outH,
            fps = 30,
            overlays = OverlayFactory.build(
                context, outW, outH, options.watermarkText,
                if (options.dateTimeOverlay) options.startedWallClockMs else null,
            ),
        )
    }

    fun release() {
        monitorJob?.cancel()
        abortSession()
    }

    companion object {
        /**
         * Finds preserved per-camera sources for a take. Rear (audio carrier)
         * is first, front second. Null when sources were not kept.
         */
        fun siblingSources(
            recordings: List<RecordingItem>,
            takeDisplayName: String,
        ): Pair<RecordingItem, RecordingItem>? {
            val base = takeDisplayName.removeSuffix(".mp4")
            val rear = recordings.firstOrNull {
                it.displayName == "${base}_camA_rear.mp4"
            } ?: return null
            val front = recordings.firstOrNull {
                it.displayName == "${base}_camB_front.mp4"
            } ?: return null
            return rear to front
        }
    }
}

/** Tiny inline probe so the controller doesn't drag the level-meter lifecycle. */
private class MicrophoneProbe(private val context: Context) {
    fun available(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        var r: android.media.AudioRecord? = null
        return try {
            val sr = 16000
            val min = android.media.AudioRecord.getMinBufferSize(
                sr,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) return false
            r = android.media.AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(min * 2)
                .build()
            if (r.state != android.media.AudioRecord.STATE_INITIALIZED) return false
            r.startRecording()
            r.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING
        } catch (e: Exception) {
            Logger.w("Record", "Mic probe failed", e)
            false
        } finally {
            runCatching {
                r?.stop()
                r?.release()
            }
        }
    }
}
