package com.creatorcam.app.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.creatorcam.app.settings.StabilizationMode
import com.creatorcam.app.settings.VideoResolution
import com.creatorcam.app.util.AppError
import com.creatorcam.app.util.Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.camera.camera2.interop.Camera2Interop

/**
 * Owns the concurrent front+rear CameraX session.
 *
 * Each camera binds `Preview + VideoCapture(Recorder)` so both lenses stream
 * to the UI *and* to independent encoders. The two recordings are later fused
 * into one file by [com.creatorcam.app.compose.VideoComposer].
 *
 * Lifetime: [open] binds, [close] releases everything. Callers must call
 * [close] when leaving the camera screen so encoders, surfaces and camera
 * handles are released (no leaks, no "camera busy" for the next app).
 */
class DualCameraSession(private val context: Context) {

    data class BoundDual(
        val rearCamera: Camera,
        val frontCamera: Camera,
        val rearPreview: Preview,
        val frontPreview: Preview,
        val rearVideo: VideoCapture<Recorder>,
        val frontVideo: VideoCapture<Recorder>,
    )

    data class SessionSpec(
        val resolution: VideoResolution,
        val fps: Int,
        val stabilization: StabilizationMode,
    )

    private var provider: ProcessCameraProvider? = null
    private var bound: BoundDual? = null
    private val lock = Any()

    val isBound: Boolean get() = synchronized(lock) { bound != null }
    fun current(): BoundDual? = synchronized(lock) { bound }

    /**
     * Binds both cameras. Must be called off the main thread; surface providers
     * are attached separately via [attachPreviewViews] once the UI exists.
     */
    suspend fun open(owner: LifecycleOwner, spec: SessionSpec): BoundDual =
        withContext(Dispatchers.Main) {
            close()
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
            }
            Logger.i("Camera", "Binding concurrent session: $spec")
            try {
                val rearGroup = buildGroup(spec, targetRotation())
                val frontGroup = buildGroup(spec, targetRotation())
                val primary = ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    rearGroup.group,
                    owner,
                )
                val secondary = ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    frontGroup.group,
                    owner,
                )
                val concurrent = cameraProvider.bindToLifecycle(listOf(primary, secondary))
                val cameras = concurrent.cameras
                check(cameras.size == 2) { "Expected 2 concurrent cameras, got ${cameras.size}" }
                val result = BoundDual(
                    rearCamera = cameras[0],
                    frontCamera = cameras[1],
                    rearPreview = rearGroup.preview,
                    frontPreview = frontGroup.preview,
                    rearVideo = rearGroup.video,
                    frontVideo = frontGroup.video,
                )
                synchronized(lock) {
                    provider = cameraProvider
                    bound = result
                }
                Logger.i("Camera", "Concurrent session bound (rear=${cameras[0]}, front=${cameras[1]})")
                result
            } catch (e: Exception) {
                Logger.e("Camera", "Concurrent bind failed", e)
                runCatching { cameraProvider.unbindAll() }
                throw mapBindError(e)
            }
        }

    /** Rebinds with a new spec (quality / fps / stabilization). Idle only. */
    suspend fun rebind(owner: LifecycleOwner, spec: SessionSpec): BoundDual {
        val rearView = rearPreviewView
        val frontView = frontPreviewView
        val result = open(owner, spec)
        if (rearView != null && frontView != null) attachPreviewViews(rearView, frontView)
        return result
    }

    private var rearPreviewView: PreviewView? = null
    private var frontPreviewView: PreviewView? = null

    /**
     * Connects live previews to the UI. PreviewViews must use
     * [PreviewView.ImplementationMode.COMPATIBLE] (TextureView) so PiP windows
     * can overlap, clip (circle/rounded) and transform without SurfaceView
     * z-ordering artifacts. Views must also use FILL_CENTER scale so the live
     * framing matches the composer (both center-crop into the layout region).
     */
    fun attachPreviewViews(rearView: PreviewView, frontView: PreviewView) {
        val b = synchronized(lock) { bound } ?: return
        rearPreviewView = rearView
        frontPreviewView = frontView
        b.rearPreview.setSurfaceProvider(rearView.surfaceProvider)
        b.frontPreview.setSurfaceProvider(frontView.surfaceProvider)
        Logger.d("Camera", "Preview surfaces attached")
    }

    fun detachPreviews() {
        val b = synchronized(lock) { bound }
        rearPreviewView = null
        frontPreviewView = null
        // Clearing providers stops frames flowing to dead views after rotation.
        b?.rearPreview?.setSurfaceProvider(null)
        b?.frontPreview?.setSurfaceProvider(null)
    }

    fun updateTargetRotation(rotation: Int) {
        val b = synchronized(lock) { bound } ?: return
        b.rearPreview.targetRotation = rotation
        b.frontPreview.targetRotation = rotation
        b.rearVideo.targetRotation = rotation
        b.frontVideo.targetRotation = rotation
    }

    /** Releases cameras, encoders and surfaces. Safe to call repeatedly. */
    fun close() {
        val p: ProcessCameraProvider?
        synchronized(lock) {
            p = provider
            provider = null
            bound = null
            rearPreviewView = null
            frontPreviewView = null
        }
        runCatching { p?.unbindAll() }
            .onSuccess { Logger.i("Camera", "Session closed") }
            .onFailure { Logger.w("Camera", "unbindAll failed", it) }
    }

    // ------------------------------------------------------------------ controls

    fun setZoom(camera: Camera, ratio: Float) {
        val clamped = ratio.coerceIn(zoomRange(camera))
        runCatching { camera.cameraControl.setZoomRatio(clamped) }
            .onFailure { Logger.w("Camera", "setZoomRatio($clamped) failed", it) }
    }

    fun zoomRange(camera: Camera): ClosedFloatingPointRange<Float> {
        val state = camera.cameraInfo.zoomState.value
        return if (state == null) 1f..1f
        else state.minZoomRatio..state.maxZoomRatio
    }

    fun setTorch(camera: Camera, enabled: Boolean) {
        if (!camera.cameraInfo.hasFlashUnit()) {
            Logger.d("Camera", "Torch requested but no flash unit")
            return
        }
        runCatching { camera.cameraControl.enableTorch(enabled) }
            .onFailure { Logger.w("Camera", "enableTorch($enabled) failed", it) }
    }

    fun hasFlash(camera: Camera): Boolean = camera.cameraInfo.hasFlashUnit()

    fun setExposure(camera: Camera, index: Int) {
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        runCatching { camera.cameraControl.setExposureCompensationIndex(clamped) }
            .onFailure { Logger.w("Camera", "Exposure set failed", it) }
    }

    fun exposureRange(camera: Camera): IntRange {
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        return range.lower..range.upper
    }

    /** Tap-to-focus on the given preview at view coordinates. */
    fun focusAt(camera: Camera, view: PreviewView, x: Float, y: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat())
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        runCatching { camera.cameraControl.startFocusAndMetering(action) }
            .onFailure { Logger.w("Camera", "Focus/metering failed", it) }
    }

    // ------------------------------------------------------------------ binding

    private data class Group(
        val group: UseCaseGroup,
        val preview: Preview,
        val video: VideoCapture<Recorder>,
    )

    private fun buildGroup(spec: SessionSpec, rotation: Int): Group {
        val previewBuilder = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(rotation)
        // Real stabilization + frame-rate control via Camera2 interop on the
        // capture session shared by preview and the recorder stream.
        val interop = Camera2Interop.Extender(previewBuilder)
        if (spec.stabilization != StabilizationMode.OFF) {
            interop.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            )
        }
        if (spec.fps > 0) {
            interop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(spec.fps, spec.fps),
            )
        }
        val preview = previewBuilder.build()

        val qualityList = QualityAdvisor.qualityChain(spec.resolution)
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    qualityList,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
                )
            )
            .build()
        val video = VideoCapture.withOutput(recorder)
        video.targetRotation = rotation

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(video)
            .build()
        return Group(group, preview, video)
    }

    private fun targetRotation(): Int {
        // Display rotation is applied properly once views exist; default to portrait.
        return android.view.Surface.ROTATION_0
    }

    private fun mapBindError(e: Exception): Exception {
        val appError: AppError = when (e) {
            is androidx.camera.core.CameraInfoUnavailableException ->
                AppError.DualCameraUnavailable()
            is IllegalArgumentException ->
                AppError.DualCameraUnavailable(
                    message = "This device cannot run this camera combination. ${e.message ?: ""}".trim(),
                )
            is SecurityException -> AppError.PermissionRequired("Camera")
            is IllegalStateException -> AppError.CameraBusy()
            is java.util.concurrent.TimeoutException -> AppError.CameraBusy()
            else -> AppError.RecordingFailed(
                title = "Camera Unavailable",
                message = "The camera could not be started (${e.javaClass.simpleName}). Close other camera apps and retry.",
            )
        }
        return CameraSessionException(appError, e)
    }

    class CameraSessionException(val error: AppError, cause: Throwable) :
        RuntimeException("${error.title}: ${error.message}", cause)
}
