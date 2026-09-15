package com.creatorcam.app.ui.camera

import android.app.Activity
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.creatorcam.app.compose.LayoutSpec
import com.creatorcam.app.compose.RegionShape
import com.creatorcam.app.recording.RecordingState
import com.creatorcam.app.settings.FrameRate
import com.creatorcam.app.settings.StabilizationMode
import com.creatorcam.app.settings.VideoResolution
import com.creatorcam.app.ui.components.ErrorBanner
import com.creatorcam.app.ui.components.LoadingRow
import com.creatorcam.app.ui.theme.CreatorColors
import com.creatorcam.app.util.AppError
import com.creatorcam.app.util.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    startMode: String,
    onBack: () -> Unit,
    onRecordingDone: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
    vm: CameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ui by vm.ui.collectAsStateWithLifecycle()

    val portraitNow = context.resources.configuration.orientation ==
        Configuration.ORIENTATION_PORTRAIT
    LaunchedEffect(startMode) {
        val mode = when (startMode) {
            "single" -> ScreenMode.SINGLE_REAR
            "single_front" -> ScreenMode.SINGLE_FRONT
            else -> ScreenMode.DUAL
        }
        vm.start(mode, owner, portraitNow)
    }
    DisposableEffect(Unit) {
        onDispose { vm.detachViews() }
    }

    // Display rotation → capture orientation + portrait flag (no recreation).
    DisposableEffect(Unit) {
        val dm = context.getSystemService(DisplayManager::class.java)
        fun push() {
            val rotation = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: android.view.Surface.ROTATION_0
            vm.onDisplayRotation(rotation)
            vm.setPortrait(
                context.resources.configuration.orientation ==
                    Configuration.ORIENTATION_PORTRAIT
            )
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = push()
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        push()
        onDispose { dm.unregisterDisplayListener(listener) }
    }

    // Lock orientation for the whole take (spec: reliability over rotation).
    DisposableEffect(ui.isBusy) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation
        if (ui.isBusy) {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        onDispose {
            if (ui.isBusy) activity?.requestedOrientation =
                previous ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var confirmExit by remember { mutableStateOf(false) }
    var showLayouts by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showZoomPanel by remember { mutableStateOf(false) }

    BackHandler(enabled = ui.isRecording) { confirmExit = true }

    // Terminal states: navigate once, then reset.
    val recording = ui.recording
    LaunchedEffect(recording) {
        if (recording is RecordingState.Done) {
            onRecordingDone(recording.uri)
            vm.consumeTerminalState()
        }
    }
    if (recording is RecordingState.Failed) {
        AlertDialog(
            onDismissRequest = { vm.consumeTerminalState() },
            title = { Text(recording.error.title) },
            text = { Text(recording.error.message) },
            confirmButton = {
                TextButton(onClick = { vm.consumeTerminalState() }) { Text("OK") }
            },
        )
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Stop recording?") },
            text = { Text("The take will be finalized and saved before leaving.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    vm.stopRecording()
                }) { Text("Stop & save") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Keep recording") }
            },
        )
    }

    Scaffold(
        topBar = {
            CameraTopBar(
                title = when (ui.mode) {
                    ScreenMode.DUAL -> "Dual Camera"
                    ScreenMode.SINGLE_REAR -> "Rear Camera"
                    ScreenMode.SINGLE_FRONT -> "Front Camera"
                },
                qualityLabel = ui.qualityLabel,
                canGoBack = !ui.isRecording,
                controlsEnabled = !ui.isBusy,
                onBack = onBack,
                onQuality = { showQuality = true },
                onSettings = onOpenSettings,
            )
        },
        bottomBar = {
            if (ui.binding is BindingState.Ready) {
                ControlBar(
                    ui = ui,
                    zoomPanelOpen = showZoomPanel,
                    onLayout = { showLayouts = true },
                    onZoomPanel = { showZoomPanel = !showZoomPanel },
                    onFlash = { vm.toggleTorchRear() },
                    onMirror = { vm.toggleMirror() },
                    onMic = { vm.toggleMic() },
                    onRecord = { vm.startRecording() },
                    onStop = { vm.stopRecording() },
                    onFlip = {
                        vm.switchMode(
                            if (ui.mode == ScreenMode.SINGLE_REAR) ScreenMode.SINGLE_FRONT
                            else ScreenMode.SINGLE_REAR
                        )
                    },
                    onZoomTarget = { vm.setZoomTargetRear(it) },
                    onZoom = { vm.setZoom(it) },
                    onExposure = { vm.setExposure(it) },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).background(Color.Black)
        ) {
            when (val binding = ui.binding) {
                BindingState.Checking, BindingState.Binding ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingRow(
                            if (binding is BindingState.Checking) "Checking device…"
                            else "Starting cameras…"
                        )
                    }
                is BindingState.Error -> BindingErrorPanel(
                    error = binding.error,
                    mode = ui.mode,
                    onRetry = { vm.retry() },
                    onSingleRear = { vm.switchMode(ScreenMode.SINGLE_REAR) },
                    onSingleFront = { vm.switchMode(ScreenMode.SINGLE_FRONT) },
                    onDual = { vm.switchMode(ScreenMode.DUAL) },
                )
                BindingState.Ready -> {
                    if (ui.mode == ScreenMode.DUAL) {
                        DualPreviewPane(
                            ui = ui,
                            onAttachRear = { vm.attachRear(it) },
                            onAttachFront = { vm.attachFront(it) },
                            onFocusRear = { v, x, y -> vm.focus(true, v, x, y) },
                            onFocusFront = { v, x, y -> vm.focus(false, v, x, y) },
                        )
                    } else {
                        SinglePreviewPane(
                            ui = ui,
                            onAttach = { vm.attachSingle(it) },
                            onFocus = { v, x, y -> vm.focus(true, v, x, y) },
                        )
                    }
                    RecordingOverlays(ui = ui)
                    ui.banner?.let { banner ->
                        ErrorBanner(
                            error = banner,
                            onDismiss = { vm.dismissBanner() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showLayouts) {
        ModalBottomSheet(onDismissRequest = { showLayouts = false }) {
            LayoutPickerSheet(
                selected = ui.layout,
                enabled = !ui.isBusy,
                onSelect = {
                    vm.selectLayout(it)
                    showLayouts = false
                },
            )
        }
    }
    if (showQuality) {
        ModalBottomSheet(onDismissRequest = { showQuality = false }) {
            QualitySheet(
                ui = ui,
                enabled = !ui.isBusy,
                onApply = { res, fps, stab -> vm.setQuality(res, fps, stab) },
                onCountdown = { vm.setCountdown(it) },
            )
        }
    }
}

@Composable
private fun CameraTopBar(
    title: String,
    qualityLabel: String,
    canGoBack: Boolean,
    controlsEnabled: Boolean,
    onBack: () -> Unit,
    onQuality: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = false,
            enabled = controlsEnabled,
            onClick = onQuality,
            label = { Text(qualityLabel.ifBlank { "Quality" }) },
            leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) },
        )
        IconButton(onClick = onSettings, enabled = controlsEnabled) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun BindingErrorPanel(
    error: AppError,
    mode: ScreenMode,
    onRetry: () -> Unit,
    onSingleRear: () -> Unit,
    onSingleFront: () -> Unit,
    onDual: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ErrorBanner(error = error)
        Spacer(Modifier.height(16.dp))
        if (error is AppError.DualCameraUnavailable && mode == ScreenMode.DUAL) {
            Text("You may still use:")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSingleRear, modifier = Modifier.weight(1f)) {
                    Text("Rear camera")
                }
                Button(onClick = onSingleFront, modifier = Modifier.weight(1f)) {
                    Text("Front camera")
                }
            }
        } else {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            if (mode != ScreenMode.DUAL) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDual, modifier = Modifier.fillMaxWidth()) {
                    Text("Try dual camera")
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewView(
    onAttach: (PreviewView) -> Unit,
    onTap: (PreviewView, Float, Float) -> Unit,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf<PreviewView?>(null) }
    Box(
        modifier
            .pointerInput(view) {
                detectTapGestures { offset ->
                    view?.let { onTap(it, offset.x, offset.y) }
                }
            }
            .graphicsLayer { scaleX = if (mirror) 1f else -1f },
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    view = this
                    onAttach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DualPreviewPane(
    ui: CameraUiState,
    onAttachRear: (PreviewView) -> Unit,
    onAttachFront: (PreviewView) -> Unit,
    onFocusRear: (PreviewView, Float, Float) -> Unit,
    onFocusFront: (PreviewView, Float, Float) -> Unit,
) {
    val (gw, gh) = if (ui.portrait) 9 to 16 else 16 to 9
    val rects = remember(ui.layout, ui.portrait) { LayoutSpec.rects(ui.layout, gw, gh) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val maxW = maxWidth
        val maxH = maxHeight
        // Largest region first so PiP windows sit above the fullscreen layer,
        // whichever camera owns it — same rule as the composer.
        val rearArea = rects.rear.w * rects.rear.h
        val frontArea = rects.front?.let { it.w * it.h } ?: 0f
        @Composable
        fun RearPane() {
            RegionBox(
                x = rects.rear.x, y = rects.rear.y,
                w = rects.rear.w, h = rects.rear.h,
                shape = rects.rear.shape, radius = rects.rear.cornerRadius,
                maxW = maxW, maxH = maxH,
            ) {
                CameraPreviewView(
                    onAttach = onAttachRear,
                    onTap = onFocusRear,
                    mirror = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        @Composable
        fun FrontPane() {
            rects.front?.let { front ->
                RegionBox(
                    x = front.x, y = front.y, w = front.w, h = front.h,
                    shape = front.shape, radius = front.cornerRadius,
                    maxW = maxW, maxH = maxH,
                ) {
                    CameraPreviewView(
                        onAttach = onAttachFront,
                        onTap = onFocusFront,
                        mirror = ui.mirrorFront,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (frontArea > rearArea) {
            FrontPane()
            RearPane()
        } else {
            RearPane()
            FrontPane()
        }
    }
}

@Composable
private fun RegionBox(
    x: Float, y: Float, w: Float, h: Float,
    shape: RegionShape, radius: Float,
    maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val clip = when (shape) {
        RegionShape.CIRCLE -> CircleShape
        RegionShape.ROUNDED_RECT ->
            RoundedCornerShape(percent = (radius * 100).toInt().coerceIn(2, 40))
        RegionShape.RECT -> RoundedCornerShape(0.dp)
    }
    Box(
        Modifier
            .offset(x = maxW * x, y = maxH * y)
            .size(width = maxW * w, height = maxH * h)
            .clip(clip)
            .background(Color.Black),
    ) { content() }
}

@Composable
private fun SinglePreviewPane(
    ui: CameraUiState,
    onAttach: (PreviewView) -> Unit,
    onFocus: (PreviewView, Float, Float) -> Unit,
) {
    val mirror = ui.mode == ScreenMode.SINGLE_FRONT && ui.mirrorFront
    CameraPreviewView(
        onAttach = onAttach,
        onTap = onFocus,
        mirror = mirror || ui.mode == ScreenMode.SINGLE_REAR,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun RecordingOverlays(ui: CameraUiState) {
    val recording = ui.recording
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    if (recording is RecordingState.Recording) {
        LaunchedEffect(Unit) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(250)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recording is RecordingState.Recording) {
                RecBadge(
                    text = "REC " + TimeFormat.recordingClock(now - recording.startedElapsedMs)
                )
            }
            Spacer(Modifier.weight(1f))
            if (ui.storageWarn) RecBadge(text = "LOW STORAGE", warn = true)
            if (ui.thermalWarn) RecBadge(text = "HOT", warn = true)
            if (ui.batteryPct in 1..14) RecBadge(text = "BAT ${ui.batteryPct}%", warn = true)
        }
        Spacer(Modifier.weight(1f))
        if (!ui.isBusy) {
            Text(
                ui.storageLine,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
            if (ui.recommendation.isNotBlank()) {
                Text(
                    "${ui.recommendation}  •  front ≤ ${ui.frontCeiling}, rear ≤ ${ui.rearCeiling}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
    when (recording) {
        is RecordingState.Countdown -> CountdownOverlay(secondsLeft = recording.secondsLeft)
        is RecordingState.Starting -> BusyOverlay(text = recording.step)
        is RecordingState.Stopping -> BusyOverlay(text = recording.step)
        is RecordingState.Composing -> ComposeOverlay(progress = recording.progress)
        else -> Unit
    }
}

@Composable
private fun RecBadge(text: String, warn: Boolean = false) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warn) CreatorColors.Warn.copy(alpha = 0.9f)
            else Color.Black.copy(alpha = 0.6f)
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!warn) {
                Box(Modifier.size(10.dp).background(CreatorColors.Record, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                color = if (warn) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CountdownOverlay(secondsLeft: Int) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$secondsLeft",
            fontSize = 96.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator()
            }
        }
    }
}

@Composable
private fun ComposeOverlay(progress: Float) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(
                Modifier.padding(20.dp).width(240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Creating your video…", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text("${(progress * 100).toInt()}%")
            }
        }
    }
}

@Composable
private fun ControlBar(
    ui: CameraUiState,
    zoomPanelOpen: Boolean,
    onLayout: () -> Unit,
    onZoomPanel: () -> Unit,
    onFlash: () -> Unit,
    onMirror: () -> Unit,
    onMic: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onFlip: () -> Unit,
    onZoomTarget: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (zoomPanelOpen && !ui.isBusy) {
            ZoomPanel(ui = ui, onZoomTarget = onZoomTarget, onZoom = onZoom, onExposure = onExposure)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (ui.mode == ScreenMode.DUAL) {
                ControlButton(label = "Layout", enabled = !ui.isBusy, onClick = onLayout)
            } else {
                IconButton(onClick = onFlip, enabled = !ui.isBusy) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip camera")
                }
            }
            ControlButton(label = "Zoom", enabled = !ui.isBusy, onClick = onZoomPanel)
            val torchOn = if (ui.zoomOnRear) ui.torchRear else ui.torchFront
            val flashAvailable = if (ui.zoomOnRear) ui.flashRear else ui.flashFront
            IconButton(onClick = onFlash, enabled = flashAvailable) {
                Icon(
                    if (torchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                    contentDescription = "Flash",
                    tint = if (torchOn) CreatorColors.Warn
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            // Record / stop.
            if (ui.isRecording) {
                Button(
                    onClick = onStop,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CreatorColors.Record),
                ) {
                    Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(4.dp)))
                }
            } else {
                Button(
                    onClick = onRecord,
                    shape = CircleShape,
                    enabled = !ui.isBusy,
                    modifier = Modifier.size(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CreatorColors.Record),
                ) {
                    Box(Modifier.size(26.dp).background(Color.White, CircleShape))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMic, enabled = !ui.isBusy) {
                    Icon(
                        if (ui.micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Microphone",
                        tint = if (ui.micEnabled) CreatorColors.Ready
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.micEnabled && !ui.isBusy) {
                    LinearProgressIndicator(
                        progress = { ui.micLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.width(40.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
            ControlButton(
                label = if (ui.mirrorFront) "Mirror✓" else "Mirror",
                enabled = !ui.isBusy,
                onClick = onMirror,
            )
        }
    }
}

@Composable
private fun ControlButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ZoomPanel(
    ui: CameraUiState,
    onZoomTarget: (Boolean) -> Unit,
    onZoom: (Float) -> Unit,
    onExposure: (Int) -> Unit,
) {
    val range = if (ui.zoomOnRear) ui.zoomRangeRear else ui.zoomRangeFront
    val value = if (ui.zoomOnRear) ui.zoomRear else ui.zoomFront
    val expRange = if (ui.zoomOnRear) ui.exposureRangeRear else ui.exposureRangeFront
    val expValue = if (ui.zoomOnRear) ui.exposureRear else ui.exposureFront
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        if (ui.mode == ScreenMode.DUAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.zoomOnRear,
                    onClick = { onZoomTarget(true) },
                    label = { Text("Rear") },
                )
                FilterChip(
                    selected = !ui.zoomOnRear,
                    onClick = { onZoomTarget(false) },
                    label = { Text("Front") },
                )
            }
        }
        Text("Zoom ${String.format("%.1fx", value)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onZoom,
            valueRange = range.start..range.endInclusive.coerceAtLeast(range.start),
            enabled = range.endInclusive > range.start + 0.01f,
        )
        if (expRange.last > expRange.first) {
            Text("Exposure $expValue", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = expValue.toFloat(),
                onValueChange = { onExposure(it.toInt()) },
                valueRange = expRange.first.toFloat()..expRange.last.toFloat(),
                steps = (expRange.last - expRange.first - 1).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun QualitySheet(
    ui: CameraUiState,
    enabled: Boolean,
    onApply: (VideoResolution, FrameRate, StabilizationMode) -> Unit,
    onCountdown: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Quality", style = MaterialTheme.typography.titleLarge)
        Text(ui.recommendation, style = MaterialTheme.typography.bodyMedium)
        Text("Resolution", fontWeight = FontWeight.Bold)
        VideoResolution.entries.forEach { option ->
            val note = when (option) {
                VideoResolution.AUTO -> "Recommended for this device"
                else -> buildString {
                    append(if (supports(ui.rearCeiling, option)) "rear ✓" else "rear ✗")
                    append("  •  ")
                    append(if (supports(ui.frontCeiling, option)) "front ✓" else "front ✗")
                }
            }
            FilterChip(
                selected = ui.qualityRes == option,
                enabled = enabled,
                onClick = { onApply(option, ui.qualityFps, ui.qualityStab) },
                label = { Text("${option.label}  ($note)") },
                leadingIcon = if (ui.qualityRes == option) {
                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("Frame rate", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameRate.entries.forEach { option ->
                val allowed = option == FrameRate.AUTO || option.fps <= ui.ceilingFps
                FilterChip(
                    selected = ui.qualityFps == option,
                    enabled = enabled && allowed,
                    onClick = { onApply(ui.qualityRes, option, ui.qualityStab) },
                    label = { Text(option.label) },
                )
            }
        }
        Text("Stabilization", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ui.qualityStab == StabilizationMode.OFF,
                enabled = enabled,
                onClick = { onApply(ui.qualityRes, ui.qualityFps, StabilizationMode.OFF) },
                label = { Text("Off") },
            )
            FilterChip(
                selected = ui.qualityStab == StabilizationMode.STANDARD,
                enabled = enabled && ui.stabilizationAvailable,
                onClick = { onApply(ui.qualityRes, ui.qualityFps, StabilizationMode.STANDARD) },
                label = {
                    Text(if (ui.stabilizationAvailable) "Standard" else "Standard (unsupported)")
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Countdown", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            listOf(0, 3, 5, 10).forEach { seconds ->
                FilterChip(
                    selected = ui.countdown == seconds,
                    enabled = enabled,
                    onClick = { onCountdown(seconds) },
                    label = { Text(if (seconds == 0) "Off" else "${seconds}s") },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        Text(
            "Each camera falls back gracefully if it can't reach the selected quality. " +
                "Selections that exceed hardware limits are clamped — never faked.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun supports(ceilingLabel: String, res: VideoResolution): Boolean {
    val ceiling = when (ceilingLabel) {
        "4K" -> 3840
        "1080p" -> 1920
        "720p" -> 1280
        "540p" -> 960
        else -> ceilingLabel.split("×", "x").mapNotNull { it.trim().toIntOrNull() }
            .maxOrNull() ?: 1280
    }
    return ceiling >= res.longEdge
}
