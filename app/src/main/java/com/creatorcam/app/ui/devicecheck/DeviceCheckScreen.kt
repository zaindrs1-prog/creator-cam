package com.creatorcam.app.ui.devicecheck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.creatorcam.app.camera.CameraCapabilityChecker
import com.creatorcam.app.camera.DeviceCapabilityReport
import com.creatorcam.app.ui.components.LoadingRow
import com.creatorcam.app.ui.components.ScreenHeader
import com.creatorcam.app.ui.components.StatusRow
import com.creatorcam.app.ui.theme.CreatorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DeviceCheckScreen(
    onBack: () -> Unit,
    onDualCamera: () -> Unit,
    onSingleCamera: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<DeviceCapabilityReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            report = withContext(Dispatchers.IO) {
                CameraCapabilityChecker(context.applicationContext).probe()
            }
        } catch (e: Exception) {
            error = e.message ?: "Probe failed"
        }
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenHeader(
                title = "Device Check",
                subtitle = "Real hardware probe — not a guess.",
            )
            Column(
                Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    error != null -> {
                        Text("The capability probe failed: $error")
                        OutlinedButton(onClick = onBack) { Text("Back") }
                    }
                    report == null -> LoadingRow("Probing cameras…")
                    else -> {
                        val r = report!!
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                StatusRow(
                                    "Front camera",
                                    if (r.frontSupported) "Supported" else "Missing",
                                    r.frontSupported,
                                )
                                StatusRow(
                                    "Rear camera",
                                    if (r.rearSupported) "Supported" else "Missing",
                                    r.rearSupported,
                                )
                                StatusRow(
                                    "Dual camera",
                                    if (r.concurrentSupported) "Supported" else "Not available",
                                    r.concurrentSupported,
                                )
                                StatusRow(
                                    "Microphone",
                                    if (r.microphonePresent) "Supported" else "Missing",
                                    r.microphonePresent,
                                )
                                StatusRow(
                                    "Video recording",
                                    if (r.frontSupported || r.rearSupported) "Supported" else "Missing",
                                    r.frontSupported || r.rearSupported,
                                )
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (r.dualReady)
                                    CreatorColors.Ready.copy(alpha = 0.14f)
                                else CreatorColors.Warn.copy(alpha = 0.14f)
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    if (r.dualReady) "Your device is ready."
                                    else "Dual camera not available",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (r.dualReady) {
                                        "Recommended quality: ${r.recommendation.label} " +
                                            "(front ≤ ${r.recommendation.frontCeiling}, " +
                                            "rear ≤ ${r.recommendation.rearCeiling})."
                                    } else {
                                        "This device cannot run front and rear cameras " +
                                            "simultaneously. Single-camera recording is fully available."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (r.dualReady) {
                            Button(onClick = onDualCamera, modifier = Modifier.fillMaxWidth()) {
                                Text("Continue to Dual Camera")
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onSingleCamera,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Rear camera") }
                                OutlinedButton(
                                    onClick = onSingleCamera,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Front camera") }
                            }
                        }
                        OutlinedButton(
                            onClick = onDiagnostics,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("View full diagnostics") }
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Back") }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
