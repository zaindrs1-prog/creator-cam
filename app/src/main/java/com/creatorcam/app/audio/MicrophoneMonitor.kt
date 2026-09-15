package com.creatorcam.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.creatorcam.app.util.Logger
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pre-recording microphone verification + live input level meter.
 *
 * The meter runs only while idle/previewing: once recording starts, the
 * CameraX Recorder owns the mic and this monitor is stopped to avoid
 * fighting for the audio device.
 */
class MicrophoneMonitor(private val context: Context) {

    private val _level = MutableStateFlow(0f)
    /** 0f..1f smoothed input level. */
    val level: StateFlow<Float> = _level

    private var job: Job? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the mic briefly to prove it is actually available. */
    fun checkAvailable(): Boolean {
        if (!hasPermission()) return false
        var record: AudioRecord? = null
        return try {
            record = createRecorder() ?: return false
            if (record.state != AudioRecord.STATE_INITIALIZED) return false
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
            val probe = ShortArray(256)
            record.read(probe, 0, probe.size) >= 0
        } catch (e: Exception) {
            Logger.w("Mic", "Mic probe failed", e)
            false
        } finally {
            runCatching {
                record?.stop()
                record?.release()
            }
        }
    }

    /** Starts the level meter; safe to call repeatedly. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        if (!hasPermission()) return
        job = scope.launch(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                record = createRecorder()
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    Logger.w("Mic", "Meter recorder init failed")
                    return@launch
                }
                val buffer = ShortArray(1024)
                record.startRecording()
                var smoothed = 0f
                while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) {
                            val s = buffer[i] / 32768.0
                            sum += s * s
                        }
                        val rms = sqrt(sum / n).toFloat()
                        smoothed = smoothed * 0.7f + rms.coerceIn(0f, 1f) * 0.3f
                        _level.value = (smoothed * 3f).coerceIn(0f, 1f)
                    } else {
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                Logger.w("Mic", "Meter stopped", e)
            } finally {
                runCatching {
                    record?.stop()
                    record?.release()
                }
                _level.value = 0f
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _level.value = 0f
    }

    private fun createRecorder(): AudioRecord? {
        val sampleRate = 16000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) return null
        return try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channel)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            Logger.w("Mic", "AudioRecord build failed", e)
            null
        }
    }
}
