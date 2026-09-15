package com.creatorcam.app.ui.diagnostics

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.creatorcam.app.camera.CameraCapabilityChecker
import com.creatorcam.app.camera.CameraFacingInfo
import com.creatorcam.app.camera.DeviceCapabilityReport
import com.creatorcam.app.recording.BatteryThermalMonitor
import com.creatorcam.app.ui.components.LoadingRow
import com.creatorcam.app.ui.components.StatusRow
import com.creatorcam.app.util.FileUtil
import com.creatorcam.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Troubleshooting screen: everything support (or a curious creator) needs to
 * understand what this specific device can do. Shareable as plain text.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<DeviceCapabilityReport?>(null) }
    var battery by remember { mutableStateOf(-1) }
    var thermal by remember { mutableStateOf(-1) }
    var storage by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val app = context.applicationContext
        val monitor = BatteryThermalMonitor(app)
        battery = monitor.batteryPercent()
        thermal = monitor.thermalStatus()
        storage = withContext(Dispatchers.IO) { FileUtil.stagingDir(app).usableSpace }
        report = withContext(Dispatchers.IO) { CameraCapabilityChecker(app).probe() }
    }

    fun shareText() {
        val r = report
        val monitor = BatteryThermalMonitor(context.applicationContext)
        val text = buildString {
            appendLine("CreatorCam diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Front: ${r?.frontCamera.describe()}")
            appendLine("Rear: ${r?.rearCamera.describe()}")
            appendLine("Concurrent: ${r?.concurrentSupported} (${r?.concurrentDetail})")
            appendLine("Recommendation: ${r?.recommendation?.label}")
            appendLine("Storage free: ${TimeFormat.bytes(storage)}")
            appendLine("Battery: $battery%")
            appendLine("Thermal: ${monitor.thermalLabel(thermal)}")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = ::shareText, enabled = report != null) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }
        },
    ) { padding ->
        val r = report
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingRow("Probing device…")
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagCard("Device") {
                DiagLine("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                DiagLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                DiagLine("Storage free", TimeFormat.bytes(storage))
                DiagLine("Battery", if (battery >= 0) "$battery%" else "Unknown")
                DiagLine(
                    "Thermal",
                    BatteryThermalMonitor(context.applicationContext).thermalLabel(thermal),
                )
            }
            DiagCard("Cameras") {
                StatusRow("Front camera", r.frontCamera?.cameraId ?: "Missing", r.frontSupported)
                StatusRow("Rear camera", r.rearCamera?.cameraId ?: "Missing", r.rearSupported)
                StatusRow(
                    "Concurrent front+rear",
                    if (r.concurrentSupported) "Yes" else "No",
                    r.concurrentSupported,
                )
                Text(
                    r.concurrentDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            r.frontCamera?.let { FacingCard("Front", it) }
            r.rearCamera?.let { FacingCard("Rear", it) }
            DiagCard("Recommendation") {
                DiagLine("Output", r.recommendation.label)
                DiagLine("Front ceiling", r.recommendation.frontCeiling)
                DiagLine("Rear ceiling", r.recommendation.rearCeiling)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun CameraFacingInfo?.describe(): String {
    if (this == null) return "missing"
    return "id=$cameraId max=${largestVideoSize} " +
        "fps=${fpsRanges.joinToString { "${it.first}-${it.last}" }} " +
        "ois=$opticalStabilization videoStab=$supportsVideoStabilization " +
        "zoom=${maxDigitalZoom}x flash=$flashAvailable"
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FacingCard(title: String, info: CameraFacingInfo) {
    DiagCard(title) {
        DiagLine("Camera ID", info.cameraId)
        DiagLine(
            "Video sizes",
            info.videoSizes.take(6).joinToString { "${it.width}×${it.height}" }
                .ifBlank { "None reported" },
        )
        DiagLine(
            "FPS ranges",
            info.fpsRanges.joinToString { "${it.first}–${it.last}" }.ifBlank { "Unknown" },
        )
        DiagLine("Optical stabilization", if (info.opticalStabilization) "Yes" else "No")
        DiagLine(
            "Video stabilization",
            if (info.supportsVideoStabilization) "Yes" else "No",
        )
        DiagLine("Max digital zoom", "${info.maxDigitalZoom}x")
        DiagLine("Flash", if (info.flashAvailable) "Yes" else "No")
    }
}
