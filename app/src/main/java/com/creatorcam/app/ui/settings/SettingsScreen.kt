package com.creatorcam.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.settings.AppSettings
import com.creatorcam.app.settings.AppTheme
import com.creatorcam.app.settings.CameraMode
import com.creatorcam.app.settings.FrameRate
import com.creatorcam.app.settings.SettingsRepository
import com.creatorcam.app.settings.StabilizationMode
import com.creatorcam.app.settings.VideoResolution
import com.creatorcam.app.ui.camera.SELECTABLE_LAYOUTS
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val settings by repo.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Camera")
            Label("Default layout")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SELECTABLE_LAYOUTS.forEach { layout ->
                    FilterChip(
                        selected = settings.defaultLayout == layout,
                        onClick = { scope.launch { repo.setLayout(layout) } },
                        label = { Text(layout.shortLabel) },
                    )
                }
            }
            Label("Resolution")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoResolution.entries.forEach { res ->
                    FilterChip(
                        selected = settings.resolution == res,
                        onClick = { scope.launch { repo.setResolution(res) } },
                        label = { Text(res.label) },
                    )
                }
            }
            Label("Frame rate")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrameRate.entries.forEach { fps ->
                    FilterChip(
                        selected = settings.frameRate == fps,
                        onClick = { scope.launch { repo.setFrameRate(fps) } },
                        label = { Text(fps.label) },
                    )
                }
            }
            Text(
                "Auto picks the best quality both cameras sustain together. " +
                    "Impossible combinations are clamped to real hardware limits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                label = "Mirror front camera",
                checked = settings.mirrorFront,
                onChange = { scope.launch { repo.setMirrorFront(it) } },
            )
            Label("Stabilization")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StabilizationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.stabilization == mode,
                        onClick = { scope.launch { repo.setStabilization(mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            Label("Default camera mode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.defaultMode == mode,
                        onClick = { scope.launch { repo.setDefaultMode(mode) } },
                        label = {
                            Text(
                                when (mode) {
                                    CameraMode.DUAL -> "Dual"
                                    CameraMode.SINGLE_REAR -> "Rear"
                                    CameraMode.SINGLE_FRONT -> "Front"
                                }
                            )
                        },
                    )
                }
            }

            Section("Audio")
            SwitchRow(
                label = "Microphone",
                checked = settings.micEnabled,
                onChange = { scope.launch { repo.setMicEnabled(it) } },
            )
            Text(
                "USB-C and Bluetooth microphones are used automatically when " +
                    "connected — Android routes them to the recording.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Recording")
            Label("Countdown")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 3, 5, 10).forEach { seconds ->
                    FilterChip(
                        selected = settings.countdownSeconds == seconds,
                        onClick = { scope.launch { repo.setCountdown(seconds) } },
                        label = { Text(if (seconds == 0) "Off" else "${seconds}s") },
                    )
                }
            }
            SwitchRow(
                label = "Keep source files",
                checked = settings.keepSourceFiles,
                onChange = { scope.launch { repo.setKeepSources(it) } },
            )
            Text(
                "Saves each camera's original file to your gallery alongside the " +
                    "final video. Needed for the editor's Change-layout feature; " +
                    "uses roughly 2× storage per take.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Videos are always saved to Movies/CreatorCam in your gallery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Overlay")
            SwitchRow(
                label = "Watermark",
                checked = settings.watermarkEnabled,
                onChange = {
                    scope.launch { repo.setWatermark(it, settings.watermarkText) }
                },
            )
            var watermarkDraft by remember(settings.watermarkText) {
                mutableStateOf(settings.watermarkText)
            }
            OutlinedTextField(
                value = watermarkDraft,
                onValueChange = {
                    watermarkDraft = it.take(32)
                    scope.launch {
                        repo.setWatermark(settings.watermarkEnabled, watermarkDraft)
                    }
                },
                label = { Text("Watermark text") },
                singleLine = true,
                enabled = settings.watermarkEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                label = "Date / time overlay",
                checked = settings.dateTimeOverlay,
                onChange = { scope.launch { repo.setDateOverlay(it) } },
            )

            Section("App")
            Label("Theme")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick = { scope.launch { repo.setTheme(theme) } },
                        label = { Text(theme.label) },
                    )
                }
            }
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Device diagnostics")
            }

            Section("Privacy")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Text(
                    "CreatorCam works fully on-device.\n\n" +
                        "• Only Camera and Microphone permissions are requested.\n" +
                        "• Recordings stay on your phone; nothing is uploaded anywhere.\n" +
                        "• No account, no login, no analytics, no ads.\n" +
                        "• Deleting a video in the app removes it from your gallery.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Section("About")
            Text(
                "CreatorCam 1.0.0 — dual-camera studio for vloggers.\n" +
                    "Front + rear recording with synchronized audio, composited on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
