package com.creatorcam.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.creatorcam.app.ui.components.HeroAction
import com.creatorcam.app.ui.components.ScreenHeader

@Composable
fun HomeScreen(
    onDualCamera: () -> Unit,
    onSingleCamera: () -> Unit,
    onRecordings: () -> Unit,
    onSettings: () -> Unit,
    onDeviceCheck: () -> Unit,
    onTeleprompter: () -> Unit,
    onPermissionsNeeded: () -> Unit,
) {
    val context = LocalContext.current
    fun needsPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        return cam != PackageManager.PERMISSION_GRANTED ||
            mic != PackageManager.PERMISSION_GRANTED
    }
    fun guarded(action: () -> Unit) {
        if (needsPermissions()) onPermissionsNeeded() else action()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            ScreenHeader(
                title = "CreatorCam",
                subtitle = "Your face and your world — in one take.",
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeroAction(
                    title = "Dual Camera",
                    subtitle = "Front + rear together",
                    icon = Icons.Default.Videocam,
                    accent = true,
                    onClick = { guarded(onDualCamera) },
                )
                HeroAction(
                    title = "Single Camera",
                    subtitle = "Front or rear, full quality",
                    icon = Icons.Default.PhotoCamera,
                    onClick = { guarded(onSingleCamera) },
                )
                HeroAction(
                    title = "Recordings",
                    subtitle = "Watch, edit and share",
                    icon = Icons.Default.Folder,
                    onClick = onRecordings,
                )
                HeroAction(
                    title = "Settings",
                    subtitle = "Quality, audio, overlays",
                    icon = Icons.Default.Settings,
                    onClick = onSettings,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Creator tools",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                HeroAction(
                    title = "Teleprompter",
                    subtitle = "Scroll your script hands-free",
                    icon = Icons.Default.TextFields,
                    onClick = onTeleprompter,
                )
                HeroAction(
                    title = "Device Check",
                    subtitle = "Verify dual-camera support",
                    icon = Icons.Default.Info,
                    onClick = onDeviceCheck,
                )
                TextButton(
                    onClick = onDeviceCheck,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Works fully offline — no account needed") }
            }
        }
    }
}
