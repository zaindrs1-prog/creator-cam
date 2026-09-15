package com.creatorcam.app.camera

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.creatorcam.app.util.Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Performs the real device-capability probe. Two independent checks must agree
 * before dual mode is offered:
 *  1. Camera2: front + rear IDs appear together in one of
 *     [CameraManager.getConcurrentCameraIds] sets (API 30+).
 *  2. CameraX: [ProcessCameraProvider.getAvailableConcurrentCameraInfos] yields
 *     a pair whose underlying Camera2 IDs cover front + rear.
 *
 * Logical/multi-camera subtleties: we resolve facing from characteristics and
 * never assume ID "0"/"1" semantics.
 */
class CameraCapabilityChecker(private val context: Context) {

    suspend fun probe(): DeviceCapabilityReport = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val front = findFacing(manager, CameraMetadata.LENS_FACING_FRONT, Facing.FRONT)
        val rear = findFacing(manager, CameraMetadata.LENS_FACING_BACK, Facing.REAR)

        val camera2Concurrent = checkCamera2Concurrent(manager, front, rear)
        val cameraXConcurrent = checkCameraXConcurrent(front, rear)
        val concurrent = camera2Concurrent.supported && cameraXConcurrent.supported
        val detail = buildString {
            append("Camera2: ${camera2Concurrent.detail}; ")
            append("CameraX: ${cameraXConcurrent.detail}")
        }
        Logger.i(
            "Caps",
            "front=${front?.cameraId} rear=${rear?.cameraId} " +
                "concurrent=$concurrent ($detail)",
        )

        val mic = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        DeviceCapabilityReport(
            frontCamera = front,
            rearCamera = rear,
            concurrentSupported = concurrent,
            concurrentDetail = detail,
            microphonePresent = mic,
            recommendation = QualityAdvisor.recommend(front, rear),
        )
    }

    private fun findFacing(
        manager: CameraManager,
        lensFacing: Int,
        facing: Facing,
    ): CameraFacingInfo? {
        for (id in runCatching { manager.cameraIdList }.getOrDefault(emptyArray())) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != lensFacing) continue
            // Prefer non-logical cameras for direct streaming.
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toSet().orEmpty()
            val isLogical = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capabilities
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java)
                ?.filter { it.width >= 640 }
                ?.sortedByDescending { it.width * it.height }
                .orEmpty()
            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { IntRange(it.lower, it.upper) }
                .orEmpty()
            return CameraFacingInfo(
                cameraId = id,
                facing = facing,
                videoSizes = sizes,
                fpsRanges = fpsRanges,
                opticalStabilization =
                    chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) == true,
                videoStabilizationModes =
                    chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?: intArrayOf(),
                maxDigitalZoom =
                    chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
                flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            ).also {
                Logger.d("Caps", "facing=$facing id=$id logical=$isLogical sizes=${sizes.take(4)}")
            }
        }
        return null
    }

    private data class ConcurrentVerdict(val supported: Boolean, val detail: String)

    private fun checkCamera2Concurrent(
        manager: CameraManager,
        front: CameraFacingInfo?,
        rear: CameraFacingInfo?,
    ): ConcurrentVerdict {
        if (front == null || rear == null) {
            return ConcurrentVerdict(false, "missing front or rear camera")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ConcurrentVerdict(false, "concurrent streaming query requires Android 11+")
        }
        val sets = runCatching { manager.concurrentCameraIds }.getOrNull().orEmpty()
        if (sets.isEmpty()) return ConcurrentVerdict(false, "HAL reports no concurrent sets")
        val pairOk = sets.any { set -> front.cameraId in set && rear.cameraId in set }
        return if (pairOk) {
            ConcurrentVerdict(true, "front+rear share a concurrent set")
        } else {
            ConcurrentVerdict(false, "front+rear never appear in the same concurrent set")
        }
    }

    /**
     * CameraX-level confirmation. Any failure here is treated as "not
     * concurrently bindable", never silently ignored.
     */
    private fun checkCameraXConcurrent(
        front: CameraFacingInfo?,
        rear: CameraFacingInfo?,
    ): ConcurrentVerdict {
        if (front == null || rear == null) {
            return ConcurrentVerdict(false, "missing front or rear camera")
        }
        return try {
            val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
            if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ||
                !provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            ) {
                return ConcurrentVerdict(false, "CameraX cannot see both lenses")
            }
            val combos = provider.availableConcurrentCameraInfos
            if (combos.isEmpty()) {
                return ConcurrentVerdict(false, "CameraX reports no concurrent combinations")
            }
            // Each combination lists the CameraInfos that may stream together.
            // We need a combination covering both a front and a back lens.
            val ok = combos.any { combo ->
                val lensFacings = combo.mapNotNull { info ->
                    runCatching { Camera2CameraInfo.from(info).getCameraCharacteristic(
                        CameraCharacteristics.LENS_FACING
                    ) }.getOrNull()
                }.toSet()
                CameraMetadata.LENS_FACING_FRONT in lensFacings &&
                    CameraMetadata.LENS_FACING_BACK in lensFacings
            }
            if (ok) ConcurrentVerdict(true, "front+rear concurrently bindable")
            else ConcurrentVerdict(false, "no front+rear combination offered")
        } catch (e: Exception) {
            Logger.w("Caps", "CameraX concurrent query failed", e)
            ConcurrentVerdict(false, "CameraX query failed: ${e.javaClass.simpleName}")
        }
    }
}
