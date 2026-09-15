package com.creatorcam.app.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.creatorcam.app.MainActivity
import com.creatorcam.app.ui.components.ScreenHeader

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var asked by remember { mutableStateOf(false) }

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var micGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
        micGranted = result[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO)
        asked = true
    }

    fun permanentlyDenied(permission: String): Boolean {
        if (granted(permission)) return false
        if (!asked) return false
        val activityRef = activity ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activityRef, permission)
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(
                title = "Permissions",
                subtitle = "Two permissions, both essential — nothing else is ever asked.",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            PermissionCard(
                icon = Icons.Default.Videocam,
                title = "Camera access",
                body = "CreatorCam needs both cameras to record your face and " +
                    "the scene in front of you at the same time.",
                granted = cameraGranted,
            )
            PermissionCard(
                icon = Icons.Default.Mic,
                title = "Microphone access",
                body = "Your microphone records synchronized audio with your video. " +
                    "Without it, videos are silent.",
                granted = micGranted,
            )
            Spacer(Modifier.height(8.dp))
            if (cameraGranted && micGranted) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            } else {
                Button(
                    onClick = {
                        launcher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant permissions") }
                if (permanentlyDenied(Manifest.permission.CAMERA) ||
                    permanentlyDenied(Manifest.permission.RECORD_AUDIO)
                ) {
                    Text(
                        "A permission was set to “Don’t ask again”. Enable it in Android settings:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open Android settings") }
                }
                // Re-check when returning from settings.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    cameraGranted = granted(Manifest.permission.CAMERA)
                    micGranted = granted(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    granted: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (granted) "✓ Granted" else "○ Not granted",
                style = MaterialTheme.typography.labelLarge,
                color = if (granted) com.creatorcam.app.ui.theme.CreatorColors.Ready
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
