package com.creatorcam.app.ui.camera

import android.app.Application
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.creatorcam.app.audio.MicrophoneMonitor
import com.creatorcam.app.camera.CameraCapabilityChecker
import com.creatorcam.app.camera.CameraFacingInfo
import com.creatorcam.app.camera.DeviceCapabilityReport
import com.creatorcam.app.camera.DualCameraSession
import com.creatorcam.app.camera.QualityAdvisor
import com.creatorcam.app.camera.SingleCameraSession
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.recording.BatteryThermalMonitor
import com.creatorcam.app.recording.DualRecordingController
import com.creatorcam.app.recording.RecordOptions
import com.creatorcam.app.recording.RecordingState
import com.creatorcam.app.recording.StorageGuard
import com.creatorcam.app.settings.AppSettings
import com.creatorcam.app.settings.FrameRate
import com.creatorcam.app.settings.SettingsRepository
import com.creatorcam.app.settings.StabilizationMode
import com.creatorcam.app.settings.VideoResolution
import com.creatorcam.app.util.AppError
import com.creatorcam.app.util.FileUtil
import com.creatorcam.app.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ScreenMode { DUAL, SINGLE_REAR, SINGLE_FRONT }

sealed interface BindingState {
    data object Checking : BindingState
    data object Binding : BindingState
    data object Ready : BindingState
    data class Error(val error: AppError) : BindingState
}

data class CameraUiState(
    val mode: ScreenMode = ScreenMode.DUAL,
    val binding: BindingState = BindingState.Checking,
    val layout: DualLayout = DualLayout.SPLIT_50_50,
    val recording: RecordingState = RecordingState.Idle,
    val mirrorFront: Boolean = true,
    val micEnabled: Boolean = true,
    val micLevel: Float = 0f,
    val torchRear: Boolean = false,
    val torchFront: Boolean = false,
    val zoomOnRear: Boolean = true,
    val zoomRear: Float = 1f,
    val zoomFront: Float = 1f,
    val zoomRangeRear: ClosedFloatingPointRange<Float> = 1f..1f,
    val zoomRangeFront: ClosedFloatingPointRange<Float> = 1f..1f,
    val flashRear: Boolean = false,
    val flashFront: Boolean = false,
    val exposureRear: Int = 0,
    val exposureFront: Int = 0,
    val exposureRangeRear: IntRange = 0..0,
    val exposureRangeFront: IntRange = 0..0,
    val recommendation: String = "",
    val qualityLabel: String = "",
    val qualityRes: VideoResolution = VideoResolution.AUTO,
    val qualityFps: FrameRate = FrameRate.AUTO,
    val qualityStab: StabilizationMode = StabilizationMode.STANDARD,
    val frontCeiling: String = "",
    val rearCeiling: String = "",
    val ceilingFps: Int = 30,
    val stabilizationAvailable: Boolean = false,
    val storageLine: String = "",
    val storageWarn: Boolean = false,
    val batteryPct: Int = -1,
    val thermalWarn: Boolean = false,
    val countdown: Int = 3,
    val portrait: Boolean = true,
    val banner: AppError? = null,
) {
    val isRecording: Boolean get() = recording is RecordingState.Recording
    val isBusy: Boolean get() = recording !is RecordingState.Idle &&
        recording !is RecordingState.Done && recording !is RecordingState.Failed
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val checker = CameraCapabilityChecker(app)
    private val dualSession = DualCameraSession(app)
    private val singleSession = SingleCameraSession(app)
    private val controller = DualRecordingController(app)
    private val micMonitor = MicrophoneMonitor(app)
    private val batteryThermal = BatteryThermalMonitor(app)

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui

    private var report: DeviceCapabilityReport? = null
    private var settings: AppSettings = AppSettings()
    private var boundOwner: LifecycleOwner? = null
    private var recordJob: Job? = null
    private var started = false

    private fun update(f: (CameraUiState) -> CameraUiState) {
        _ui.value = f(_ui.value)
    }

    /** Called once from the screen with the requested mode. */
    fun start(mode: ScreenMode, owner: LifecycleOwner, portrait: Boolean) {
        update { it.copy(mode = mode, portrait = portrait) }
        if (started) {
            if (boundOwner !== owner && !ui.value.isBusy) bind(owner)
            return
        }
        started = true
        boundOwner = owner
        viewModelScope.launch {
            settings = settingsRepo.settings.first()
            update {
                it.copy(
                    layout = settings.defaultLayout
                        .takeUnless { l -> l == DualLayout.FULLSCREEN_SINGLE }
                        ?: DualLayout.SPLIT_50_50,
                    mirrorFront = settings.mirrorFront,
                    micEnabled = settings.micEnabled,
                    countdown = settings.countdownSeconds,
                    qualityRes = settings.resolution,
                    qualityFps = settings.frameRate,
                    qualityStab = settings.stabilization,
                )
            }
            launch { observeRecording() }
            launch { observeMicLevel() }
            launch { pollHealth() }
            probeAndBind(owner)
        }
    }

    fun setPortrait(portrait: Boolean) {
        update { it.copy(portrait = portrait) }
    }

    // ------------------------------------------------------------- lifecycle

    private suspend fun probeAndBind(owner: LifecycleOwner) {
        update { it.copy(binding = BindingState.Checking) }
        val r = try {
            checker.probe()
        } catch (e: Exception) {
            Logger.e("CameraVM", "Probe failed", e)
            update {
                it.copy(binding = BindingState.Error(AppError.CameraMissing()))
            }
            return
        }
        report = r
        val ceilingFps = listOfNotNull(r.frontCamera, r.rearCamera)
            .map { info -> info.fpsRanges.maxOfOrNull { it.last } ?: 30 }
            .minOrNull() ?: 30
        update {
            it.copy(
                recommendation = "Recommended: ${r.recommendation.label}",
                frontCeiling = r.frontCamera?.largestVideoSize
                    ?.let(QualityAdvisor::sizeLabel) ?: "—",
                rearCeiling = r.rearCamera?.largestVideoSize
                    ?.let(QualityAdvisor::sizeLabel) ?: "—",
                ceilingFps = ceilingFps,
                stabilizationAvailable =
                    (r.frontCamera?.supportsVideoStabilization == true) ||
                        (r.rearCamera?.supportsVideoStabilization == true),
            )
        }
        val wantDual = ui.value.mode == ScreenMode.DUAL
        if (wantDual && !r.dualReady) {
            update { it.copy(binding = BindingState.Error(AppError.DualCameraUnavailable())) }
            return
        }
        bind(owner)
    }

    private fun bind(owner: LifecycleOwner) {
        boundOwner = owner
        viewModelScope.launch {
            update { it.copy(binding = BindingState.Binding) }
            try {
                val r = report ?: checker.probe().also { report = it }
                val spec = QualityAdvisor.resolve(
                    front = r.frontCamera,
                    rear = r.rearCamera,
                    dual = ui.value.mode == ScreenMode.DUAL,
                    wantResolution = settings.resolution,
                    wantFps = settings.frameRate,
                )
                update {
                    it.copy(
                        qualityLabel = "${spec.resolution.label} • ${spec.fps} FPS",
                    )
                }
                when (ui.value.mode) {
                    ScreenMode.DUAL -> {
                        dualSession.open(
                            owner,
                            DualCameraSession.SessionSpec(
                                resolution = spec.resolution,
                                fps = spec.fps,
                                stabilization = settings.stabilization,
                            ),
                        )
                        singleSession.close()
                    }
                    ScreenMode.SINGLE_REAR, ScreenMode.SINGLE_FRONT -> {
                        singleSession.open(
                            owner,
                            SingleCameraSession.SessionSpec(
                                selector = if (ui.value.mode == ScreenMode.SINGLE_REAR)
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                else CameraSelector.DEFAULT_FRONT_CAMERA,
                                resolution = spec.resolution,
                                fps = spec.fps,
                                stabilization = settings.stabilization,
                            ),
                        )
                        dualSession.close()
                    }
                }
                refreshControlRanges()
                attachPendingViews()
                update { it.copy(binding = BindingState.Ready, banner = null) }
                restartMicMeter()
            } catch (e: DualCameraSession.CameraSessionException) {
                Logger.e("CameraVM", "Bind failed", e)
                update { it.copy(binding = BindingState.Error(e.error)) }
            } catch (e: Exception) {
                Logger.e("CameraVM", "Bind failed", e)
                update { it.copy(binding = BindingState.Error(AppError.CameraBusy())) }
            }
        }
    }

    fun retry() {
        val owner = boundOwner ?: return
        viewModelScope.launch { probeAndBind(owner) }
    }

    fun switchMode(mode: ScreenMode) {
        if (ui.value.isBusy || ui.value.mode == mode) return
        update { it.copy(mode = mode) }
        viewModelScope.launch {
            val owner = boundOwner ?: return@launch
            if (mode == ScreenMode.DUAL && report?.dualReady != true) {
                probeAndBind(owner)
            } else {
                bind(owner)
            }
        }
    }

    fun updateTargetRotation(rotation: Int) {
        dualSession.updateTargetRotation(rotation)
        singleSession.updateTargetRotation(rotation)
    }

    // ----------------------------------------------------------------- views

    private var rearView: PreviewView? = null
    private var frontView: PreviewView? = null
    private var singleView: PreviewView? = null

    fun attachRear(view: PreviewView) {
        rearView = view
        attachPendingViews()
    }

    fun attachFront(view: PreviewView) {
        frontView = view
        attachPendingViews()
    }

    fun attachSingle(view: PreviewView) {
        singleView = view
        if (singleSession.current() != null) singleSession.attachPreviewView(view)
    }

    fun detachViews() {
        rearView = null
        frontView = null
        singleView = null
        dualSession.detachPreviews()
        singleSession.detachPreview()
    }

    private fun attachPendingViews() {
        val rear = rearView
        val front = frontView
        if (rear != null && front != null && dualSession.current() != null) {
            dualSession.attachPreviewViews(rear, front)
        }
        val single = singleView
        if (single != null && singleSession.current() != null) {
            singleSession.attachPreviewView(single)
        }
    }

    // ------------------------------------------------------------------ state

    fun selectLayout(layout: DualLayout) {
        if (ui.value.isBusy) return
        if (layout == DualLayout.FULLSCREEN_SINGLE) return
        update { it.copy(layout = layout) }
        viewModelScope.launch { settingsRepo.setLayout(layout) }
    }

    fun toggleMirror() {
        if (ui.value.isBusy) return
        val next = !ui.value.mirrorFront
        update { it.copy(mirrorFront = next) }
        viewModelScope.launch { settingsRepo.setMirrorFront(next) }
    }

    fun toggleMic() {
        if (ui.value.isBusy) return
        val next = !ui.value.micEnabled
        update { it.copy(micEnabled = next) }
        viewModelScope.launch { settingsRepo.setMicEnabled(next) }
        restartMicMeter()
    }

    fun toggleTorchRear() {
        val next = !ui.value.torchRear
        update { it.copy(torchRear = next) }
        dualSession.current()?.let { dualSession.setTorch(it.rearCamera, next) }
        singleSession.current()?.let {
            if (ui.value.mode == ScreenMode.SINGLE_REAR) singleSession.setTorch(it.camera, next)
        }
    }

    fun toggleTorchFront() {
        val next = !ui.value.torchFront
        update { it.copy(torchFront = next) }
        dualSession.current()?.let { dualSession.setTorch(it.frontCamera, next) }
        singleSession.current()?.let {
            if (ui.value.mode == ScreenMode.SINGLE_FRONT) singleSession.setTorch(it.camera, next)
        }
    }

    fun setZoomTargetRear(rear: Boolean) {
        update { it.copy(zoomOnRear = rear) }
    }

    fun setZoom(ratio: Float) {
        val s = ui.value
        if (s.mode == ScreenMode.DUAL) {
            val bound = dualSession.current() ?: return
            if (s.zoomOnRear) {
                dualSession.setZoom(bound.rearCamera, ratio)
                update { it.copy(zoomRear = ratio.coerceIn(it.zoomRangeRear)) }
            } else {
                dualSession.setZoom(bound.frontCamera, ratio)
                update { it.copy(zoomFront = ratio.coerceIn(it.zoomRangeFront)) }
            }
        } else {
            val bound = singleSession.current() ?: return
            singleSession.setZoom(bound.camera, ratio)
            if (s.mode == ScreenMode.SINGLE_REAR) update { it.copy(zoomRear = ratio) }
            else update { it.copy(zoomFront = ratio) }
        }
    }

    fun setExposure(index: Int) {
        val s = ui.value
        if (s.mode == ScreenMode.DUAL) {
            val bound = dualSession.current() ?: return
            if (s.zoomOnRear) {
                dualSession.setExposure(bound.rearCamera, index)
                update { it.copy(exposureRear = index) }
            } else {
                dualSession.setExposure(bound.frontCamera, index)
                update { it.copy(exposureFront = index) }
            }
        } else {
            val bound = singleSession.current() ?: return
            singleSession.setExposure(bound.camera, index)
            if (s.mode == ScreenMode.SINGLE_REAR) update { it.copy(exposureRear = index) }
            else update { it.copy(exposureFront = index) }
        }
    }

    fun focus(rear: Boolean, view: PreviewView, x: Float, y: Float) {
        if (ui.value.mode == ScreenMode.DUAL) {
            val bound = dualSession.current() ?: return
            if (rear) dualSession.focusAt(bound.rearCamera, view, x, y)
            else dualSession.focusAt(bound.frontCamera, view, x, y)
        } else {
            val bound = singleSession.current() ?: return
            singleSession.focusAt(bound.camera, view, x, y)
        }
    }

    fun setQuality(resolution: VideoResolution, fps: FrameRate, stab: StabilizationMode) {
        if (ui.value.isBusy) return
        update { it.copy(qualityRes = resolution, qualityFps = fps, qualityStab = stab) }
        viewModelScope.launch {
            settingsRepo.setResolution(resolution)
            settingsRepo.setFrameRate(fps)
            settingsRepo.setStabilization(stab)
            settings = settingsRepo.settings.first()
            boundOwner?.let { bind(it) }
        }
    }

    fun setCountdown(seconds: Int) {
        if (ui.value.isBusy) return
        update { it.copy(countdown = seconds.coerceIn(0, 10)) }
        viewModelScope.launch { settingsRepo.setCountdown(seconds) }
    }

    fun dismissBanner() {
        update { it.copy(banner = null) }
    }

    fun consumeTerminalState() {
        // After the UI navigates away from Done/Failed, reset to Idle so a
        // return to this screen starts clean.
        val r = ui.value.recording
        if (r is RecordingState.Done || r is RecordingState.Failed) {
            update { it.copy(recording = RecordingState.Idle) }
            restartMicMeter()
        }
    }

    private fun refreshControlRanges() {
        val dual = dualSession.current()
        if (dual != null) {
            update {
                it.copy(
                    zoomRangeRear = dualSession.zoomRange(dual.rearCamera),
                    zoomRangeFront = dualSession.zoomRange(dual.frontCamera),
                    flashRear = dualSession.hasFlash(dual.rearCamera),
                    flashFront = dualSession.hasFlash(dual.frontCamera),
                    exposureRangeRear = dualSession.exposureRange(dual.rearCamera),
                    exposureRangeFront = dualSession.exposureRange(dual.frontCamera),
                )
            }
            return
        }
        val single = singleSession.current()
        if (single != null) {
            val zoom = single.camera.cameraInfo.zoomState.value
            val range = if (zoom == null) 1f..1f else zoom.minZoomRatio..zoom.maxZoomRatio
            val exp = single.camera.cameraInfo.exposureState.exposureCompensationRange
            val flash = single.camera.cameraInfo.hasFlashUnit()
            if (ui.value.mode == ScreenMode.SINGLE_REAR) {
                update {
                    it.copy(
                        zoomRangeRear = range, flashRear = flash,
                        exposureRangeRear = exp.lower..exp.upper,
                    )
                }
            } else {
                update {
                    it.copy(
                        zoomRangeFront = range, flashFront = flash,
                        exposureRangeFront = exp.lower..exp.upper,
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------- recording

    fun startRecording() {
        if (ui.value.binding !is BindingState.Ready || ui.value.isBusy) return
        micMonitor.stop()
        val s = ui.value
        val spec = QualityAdvisor.resolve(
            front = report?.frontCamera,
            rear = report?.rearCamera,
            dual = s.mode == ScreenMode.DUAL,
            wantResolution = settings.resolution,
            wantFps = settings.frameRate,
        )
        val options = RecordOptions(
            layout = if (s.mode == ScreenMode.DUAL) s.layout else DualLayout.FULLSCREEN_SINGLE,
            mirrorFront = s.mirrorFront,
            micEnabled = s.micEnabled,
            portrait = s.portrait,
            outputLongEdge = when (spec.resolution) {
                VideoResolution.UHD_4K -> 3840
                VideoResolution.FULL_HD_1080P -> 1920
                else -> 1280
            },
            fps = spec.fps,
            watermarkText = settings.watermarkText.takeIf { settings.watermarkEnabled },
            dateTimeOverlay = settings.dateTimeOverlay,
            startedWallClockMs = System.currentTimeMillis(),
            keepSourceFiles = settings.keepSourceFiles,
        )
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            if (s.mode == ScreenMode.DUAL) {
                val bound = dualSession.current()
                if (bound == null) {
                    update { it.copy(banner = AppError.CameraBusy()) }
                    return@launch
                }
                controller.recordDual(bound, options, spec.resolution, s.countdown)
            } else {
                val bound = singleSession.current()
                if (bound == null) {
                    update { it.copy(banner = AppError.CameraBusy()) }
                    return@launch
                }
                controller.recordSingle(
                    bound, options, spec.resolution, s.countdown,
                    isFront = s.mode == ScreenMode.SINGLE_FRONT,
                )
            }
        }
    }

    fun stopRecording() {
        controller.requestStop()
    }

    fun cancelRecording() {
        controller.requestCancel()
        recordJob?.cancel()
        update { it.copy(recording = RecordingState.Idle) }
        restartMicMeter()
    }

    private suspend fun observeRecording() {
        controller.state.collect { state ->
            update { it.copy(recording = state) }
            if (state is RecordingState.Idle) restartMicMeter()
            if (state is RecordingState.Done || state is RecordingState.Failed) {
                restartMicMeter()
            }
        }
    }

    private fun restartMicMeter() {
        if (!ui.value.micEnabled) {
            micMonitor.stop()
            return
        }
        if (ui.value.binding is BindingState.Ready && !ui.value.isBusy) {
            micMonitor.start(viewModelScope)
        } else {
            micMonitor.stop()
        }
    }

    private suspend fun observeMicLevel() {
        micMonitor.level.collect { level ->
            update { it.copy(micLevel = level) }
        }
    }

    private suspend fun pollHealth() {
        val app = getApplication<Application>()
        while (isActive) {
            val staging = FileUtil.stagingDir(app)
            val preflight = StorageGuard.preflight(staging, settings.resolution, dual = true)
            val pct = batteryThermal.batteryPercent()
            val thermal = batteryThermal.thermalStatus()
            update {
                it.copy(
                    storageLine = preflight.statusLine,
                    storageWarn = StorageGuard.shouldWarn(staging),
                    batteryPct = pct,
                    thermalWarn = batteryThermal.shouldWarn(thermal) ||
                        batteryThermal.mustStop(thermal),
                )
            }
            delay(3000)
        }
    }

    /** Surface rotation changed — keep capture orientation correct. */
    fun onDisplayRotation(rotation: Int) {
        val normalized = when (rotation) {
            Surface.ROTATION_0 -> Surface.ROTATION_0
            Surface.ROTATION_90 -> Surface.ROTATION_90
            Surface.ROTATION_180 -> Surface.ROTATION_180
            Surface.ROTATION_270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        updateTargetRotation(normalized)
    }

    override fun onCleared() {
        recordJob?.cancel()
        micMonitor.stop()
        if (controller.isRecording) controller.requestStop()
        dualSession.close()
        singleSession.close()
        controller.release()
        super.onCleared()
    }

    fun reportForDiagnostics(): DeviceCapabilityReport? = report
    fun facingInfo(rear: Boolean): CameraFacingInfo? =
        if (rear) report?.rearCamera else report?.frontCamera
}
