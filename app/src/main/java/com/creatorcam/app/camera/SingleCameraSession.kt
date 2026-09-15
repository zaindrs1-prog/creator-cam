package com.creatorcam.app.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
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
 * Fallback path for devices without concurrent front+rear streaming.
 * Same guarantees as the dual session: real preview + real encoded output,
 * identical control surface, clean release.
 */
class SingleCameraSession(private val context: Context) {

    data class BoundSingle(
        val camera: Camera,
        val preview: Preview,
        val video: VideoCapture<Recorder>,
        val selector: CameraSelector,
    )

    data class SessionSpec(
        val selector: CameraSelector,
        val resolution: VideoResolution,
        val fps: Int,
        val stabilization: StabilizationMode,
    )

    private var provider: ProcessCameraProvider? = null
    private var bound: BoundSingle? = null
    private var previewView: PreviewView? = null
    private val lock = Any()

    fun current(): BoundSingle? = synchronized(lock) { bound }

    suspend fun open(owner: LifecycleOwner, spec: SessionSpec): BoundSingle =
        withContext(Dispatchers.Main) {
            close()
            val cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
            }
            Logger.i("Camera", "Binding single session: $spec")
            try {
                val previewBuilder = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
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
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            QualityAdvisor.qualityChain(spec.resolution),
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
                        )
                    )
                    .build()
                val video = VideoCapture.withOutput(recorder)
                val camera = cameraProvider.bindToLifecycle(owner, spec.selector, preview, video)
                val result = BoundSingle(camera, preview, video, spec.selector)
                synchronized(lock) {
                    provider = cameraProvider
                    bound = result
                }
                Logger.i("Camera", "Single session bound")
                result
            } catch (e: Exception) {
                Logger.e("Camera", "Single bind failed", e)
                runCatching { cameraProvider.unbindAll() }
                throw DualCameraSession.CameraSessionException(mapError(e), e)
            }
        }

    fun attachPreviewView(view: PreviewView) {
        val b = synchronized(lock) { bound } ?: return
        previewView = view
        b.preview.setSurfaceProvider(view.surfaceProvider)
    }

    fun detachPreview() {
        previewView = null
        synchronized(lock) { bound }?.preview?.setSurfaceProvider(null)
    }

    fun updateTargetRotation(rotation: Int) {
        val b = synchronized(lock) { bound } ?: return
        b.preview.targetRotation = rotation
        b.video.targetRotation = rotation
    }

    fun close() {
        val p: ProcessCameraProvider?
        synchronized(lock) {
            p = provider
            provider = null
            bound = null
            previewView = null
        }
        runCatching { p?.unbindAll() }
    }

    fun setZoom(camera: Camera, ratio: Float) {
        val state = camera.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        runCatching { camera.cameraControl.setZoomRatio(clamped) }
    }

    fun setTorch(camera: Camera, enabled: Boolean) {
        if (!camera.cameraInfo.hasFlashUnit()) return
        runCatching { camera.cameraControl.enableTorch(enabled) }
    }

    fun setExposure(camera: Camera, index: Int) {
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        runCatching {
            camera.cameraControl.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper))
        }
    }

    fun focusAt(camera: Camera, view: PreviewView, x: Float, y: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat())
        val action = FocusMeteringAction.Builder(
            factory.createPoint(x, y),
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        runCatching { camera.cameraControl.startFocusAndMetering(action) }
    }

    private fun mapError(e: Exception): AppError = when (e) {
        is androidx.camera.core.CameraInfoUnavailableException -> AppError.CameraMissing()
        is SecurityException -> AppError.PermissionRequired("Camera")
        is IllegalStateException -> AppError.CameraBusy()
        else -> AppError.RecordingFailed(
            title = "Camera Unavailable",
            message = "The camera could not be started (${e.javaClass.simpleName}).",
        )
    }
}
