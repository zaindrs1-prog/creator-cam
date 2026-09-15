package com.creatorcam.app.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.creatorcam.app.compose.ComposeInput
import com.creatorcam.app.compose.ComposeRequest
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.compose.ExportPreset
import com.creatorcam.app.compose.LayoutSpec
import com.creatorcam.app.compose.OverlayFactory
import com.creatorcam.app.compose.VideoComposer
import com.creatorcam.app.media.MediaStoreSaver
import com.creatorcam.app.media.VideoMetadata
import com.creatorcam.app.media.VideoTrimmer
import com.creatorcam.app.recording.DualRecordingController
import com.creatorcam.app.ui.camera.SELECTABLE_LAYOUTS
import com.creatorcam.app.ui.components.LoadingRow
import com.creatorcam.app.util.FileUtil
import com.creatorcam.app.util.Logger
import com.creatorcam.app.util.TimeFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val loading: Boolean = true,
    val durationMs: Long = 0,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val srcRotation: Int = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val mute: Boolean = false,
    val textOverlay: String = "",
    val preset: ExportPreset = ExportPreset.ORIGINAL,
    val relayoutAvailable: Boolean = false,
    val relayout: DualLayout? = null,
    val relayoutMirror: Boolean = true,
    val working: Float? = null,
    val error: String? = null,
) {
    val hasChanges: Boolean
        get() = startMs > 0 || (endMs in 1 until durationMs) || mute ||
            textOverlay.isNotBlank() || preset != ExportPreset.ORIGINAL ||
            relayout != null
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val saver = MediaStoreSaver(app)
    private val composer = VideoComposer()
    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui

    private var sourceUri: Uri? = null
    private var sourceName: String = ""
    private var siblingRear: Uri? = null
    private var siblingFront: Uri? = null

    fun load(uri: Uri) {
        if (sourceUri == uri) return
        sourceUri = uri
        viewModelScope.launch(Dispatchers.IO) {
            val meta = VideoMetadata.of(getApplication(), uri)
            val recordings = saver.listRecordings()
            val self = recordings.firstOrNull { it.uri == uri }
            sourceName = self?.displayName ?: "video.mp4"
            val siblings = self?.let {
                DualRecordingController.siblingSources(recordings, it.displayName)
            }
            siblingRear = siblings?.first?.uri
            siblingFront = siblings?.second?.uri
            _ui.value = EditorUiState(
                loading = false,
                durationMs = meta.durationMs,
                srcW = meta.width,
                srcH = meta.height,
                srcRotation = meta.rotation,
                endMs = meta.durationMs,
                relayoutAvailable = siblings != null,
            )
        }
    }

    fun setStart(ms: Long) {
        val s = _ui.value
        _ui.value = s.copy(startMs = ms.coerceIn(0, (s.endMs - 500).coerceAtLeast(0)))
    }

    fun setEnd(ms: Long) {
        val s = _ui.value
        _ui.value = s.copy(endMs = ms.coerceIn(s.startMs + 500, s.durationMs))
    }

    fun setMute(mute: Boolean) {
        _ui.value = _ui.value.copy(mute = mute)
    }

    fun setText(text: String) {
        _ui.value = _ui.value.copy(textOverlay = text.take(64))
    }

    fun setPreset(preset: ExportPreset) {
        _ui.value = _ui.value.copy(preset = preset)
    }

    fun setRelayout(layout: DualLayout?) {
        _ui.value = _ui.value.copy(relayout = layout)
    }

    fun setRelayoutMirror(mirror: Boolean) {
        _ui.value = _ui.value.copy(relayoutMirror = mirror)
    }

    fun export(onExported: (Uri) -> Unit) {
        val s = _ui.value
        val uri = sourceUri ?: return
        if (s.working != null || !s.hasChanges) return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = s.copy(working = 0.02f, error = null)
            try {
                val outUri = if (s.relayout != null && siblingRear != null && siblingFront != null) {
                    exportRelayout(s)
                } else {
                    exportSingle(s, uri)
                }
                _ui.value = _ui.value.copy(working = null)
                withContext(Dispatchers.Main) { onExported(outUri) }
            } catch (e: Exception) {
                Logger.e("Editor", "Export failed", e)
                _ui.value = _ui.value.copy(
                    working = null,
                    error = e.message ?: "Export failed",
                )
            }
        }
    }

    private suspend fun exportSingle(s: EditorUiState, uri: Uri): Uri {
        val app = getApplication<Application>()
        val needsTrim = s.startMs > 0 || s.endMs < s.durationMs || s.mute
        var staged: File? = null
        if (needsTrim) {
            val trimOut = FileUtil.newStagingFile(app, "trim")
            val result = VideoTrimmer.trim(app, uri, s.startMs, s.endMs, trimOut, s.mute)
            if (!result.success) throw result.error ?: IllegalStateException("Trim failed")
            staged = trimOut
            _ui.value = _ui.value.copy(working = 0.35f)
        }
        val needsCompose = s.textOverlay.isNotBlank() || s.preset != ExportPreset.ORIGINAL
        val finalFile = if (needsCompose) {
            val input = staged ?: copyUriToStaging(uri)
            val (dispW, dispH) = displaySize(s)
            val longEdge = minOf(maxOf(dispW, dispH), 2160).coerceAtLeast(640)
            val (outW, outH) = s.preset.outputSize(longEdge, dispW, dispH)
            val overlays = if (s.textOverlay.isBlank()) emptyList()
            else listOf(OverlayFactory.textCard(s.textOverlay, outW, outH))
            val request = ComposeRequest(
                inputs = listOf(
                    ComposeInput(
                        file = input,
                        region = LayoutSpec.rects(DualLayout.FULLSCREEN_SINGLE, outW, outH).rear,
                        mirror = false,
                        timestampOffsetUs = 0L,
                        providesAudio = !s.mute,
                    )
                ),
                audioInputIndex = if (s.mute) null else 0,
                outputFile = FileUtil.newStagingFile(app, "export"),
                outputWidth = outW,
                outputHeight = outH,
                fps = 30,
                overlays = overlays,
            )
            val result = composer.compose(request) { p ->
                _ui.value = _ui.value.copy(working = 0.35f + p * 0.6f)
            }
            if (!result.success) throw result.error ?: IllegalStateException("Export failed")
            FileUtil.deleteQuietly(input)
            request.outputFile
        } else {
            staged ?: throw IllegalStateException("Nothing to export")
        }
        _ui.value = _ui.value.copy(working = 0.97f)
        val name = sourceName.removeSuffix(".mp4") + "_edit.mp4"
        val outUri = saver.saveVideo(finalFile, name)
        FileUtil.deleteQuietly(finalFile)
        return outUri
    }

    private suspend fun exportRelayout(s: EditorUiState): Uri {
        val app = getApplication<Application>()
        val rearUri = siblingRear!!
        val frontUri = siblingFront!!
        val needsTrim = s.startMs > 0 || s.endMs < s.durationMs
        val layout = s.relayout ?: DualLayout.SPLIT_50_50
        val (dispW, dispH) = displaySize(s)
        val longEdge = minOf(maxOf(dispW, dispH), 2160).coerceAtLeast(640)
        val (outW, outH) = s.preset.outputSize(longEdge, dispW, dispH)
        val rects = LayoutSpec.rects(layout, outW, outH)

        suspend fun prepare(uri: Uri, tag: String): File {
            if (!needsTrim) return copyUriToStaging(uri)
            val trimOut = FileUtil.newStagingFile(app, "trim_$tag")
            val result = VideoTrimmer.trim(app, uri, s.startMs, s.endMs, trimOut, mute = false)
            if (!result.success) throw result.error ?: IllegalStateException("Trim failed")
            return trimOut
        }
        val rearFile = prepare(rearUri, "rear")
        _ui.value = _ui.value.copy(working = 0.15f)
        val frontFile = prepare(frontUri, "front")
        _ui.value = _ui.value.copy(working = 0.3f)

        val overlays = if (s.textOverlay.isBlank()) emptyList()
        else listOf(OverlayFactory.textCard(s.textOverlay, outW, outH))
        val request = ComposeRequest(
            inputs = listOf(
                ComposeInput(
                    file = rearFile, region = rects.rear, mirror = false,
                    timestampOffsetUs = 0L, providesAudio = !s.mute,
                ),
                ComposeInput(
                    file = frontFile, region = rects.front ?: rects.rear,
                    mirror = s.relayoutMirror, timestampOffsetUs = 0L,
                    providesAudio = false,
                ),
            ),
            audioInputIndex = if (s.mute) null else 0,
            outputFile = FileUtil.newStagingFile(app, "relayout"),
            outputWidth = outW,
            outputHeight = outH,
            fps = 30,
            overlays = overlays,
        )
        val result = composer.compose(request) { p ->
            _ui.value = _ui.value.copy(working = 0.3f + p * 0.65f)
        }
        FileUtil.deleteQuietly(rearFile)
        FileUtil.deleteQuietly(frontFile)
        if (!result.success) throw result.error ?: IllegalStateException("Re-layout failed")
        val name = sourceName.removeSuffix(".mp4") + "_${layout.shortLabel.lowercase()}.mp4"
        val outUri = saver.saveVideo(request.outputFile, name)
        FileUtil.deleteQuietly(request.outputFile)
        return outUri
    }

    private fun displaySize(s: EditorUiState): Pair<Int, Int> {
        // Displayed dims (rotation-aware) so export aspects match playback.
        val w = s.srcW.coerceAtLeast(2)
        val h = s.srcH.coerceAtLeast(2)
        return if (s.srcRotation == 90 || s.srcRotation == 270) h to w else w to h
    }

    private fun copyUriToStaging(uri: Uri): File {
        val app = getApplication<Application>()
        val out = FileUtil.newStagingFile(app, "src")
        app.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read source video" }
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    uri: Uri,
    onBack: () -> Unit,
    onExported: (Uri) -> Unit,
    vm: EditorViewModel = viewModel(),
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(uri) { vm.load(uri) }

    var thumb by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        thumb = MediaStoreSaver(context.applicationContext).loadThumbnail(uri)
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
                Text("Edit", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.export(onExported) },
                    enabled = ui.hasChanges && ui.working == null && !ui.loading,
                ) { Text("Export") }
            }
        },
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingRow("Loading video…")
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            thumb?.let {
                Image(
                    it.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
            Text(
                "${TimeFormat.mediaDuration(ui.durationMs)} • ${ui.srcW}×${ui.srcH}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Trim", fontWeight = FontWeight.Bold)
            Text("Start ${TimeFormat.mediaDuration(ui.startMs)}")
            Slider(
                value = ui.startMs.toFloat(),
                onValueChange = { vm.setStart(it.toLong()) },
                valueRange = 0f..ui.durationMs.toFloat().coerceAtLeast(1f),
                enabled = ui.working == null,
            )
            Text("End ${TimeFormat.mediaDuration(ui.endMs)}")
            Slider(
                value = ui.endMs.toFloat(),
                onValueChange = { vm.setEnd(it.toLong()) },
                valueRange = 0f..ui.durationMs.toFloat().coerceAtLeast(1f),
                enabled = ui.working == null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mute audio", modifier = Modifier.weight(1f))
                Switch(
                    checked = ui.mute,
                    onCheckedChange = { vm.setMute(it) },
                    enabled = ui.working == null,
                )
            }

            Text("Text overlay", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = ui.textOverlay,
                onValueChange = { vm.setText(it) },
                placeholder = { Text("Optional caption burned into the video") },
                singleLine = true,
                enabled = ui.working == null,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Export format", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportPreset.SOCIAL.forEach { preset ->
                    FilterChip(
                        selected = ui.preset == preset,
                        enabled = ui.working == null,
                        onClick = { vm.setPreset(preset) },
                        label = { Text("${preset.title} (${preset.hint})") },
                        leadingIcon = if (ui.preset == preset) {
                            { Icon(Icons.Default.Check, null) }
                        } else null,
                    )
                }
            }

            Text("Change layout", fontWeight = FontWeight.Bold)
            if (ui.relayoutAvailable) {
                Text(
                    "Original camera files found — re-compose this take any way you like.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ui.relayout == null,
                        enabled = ui.working == null,
                        onClick = { vm.setRelayout(null) },
                        label = { Text("Keep") },
                    )
                    SELECTABLE_LAYOUTS.forEach { layout ->
                        FilterChip(
                            selected = ui.relayout == layout,
                            enabled = ui.working == null,
                            onClick = { vm.setRelayout(layout) },
                            label = { Text(layout.title) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mirror front camera", modifier = Modifier.weight(1f))
                    Switch(
                        checked = ui.relayoutMirror,
                        onCheckedChange = { vm.setRelayoutMirror(it) },
                        enabled = ui.working == null && ui.relayout != null,
                    )
                }
            } else {
                Text(
                    "Re-layout needs the original camera files. Enable “Keep source files” " +
                        "in Settings before recording to unlock it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ui.working?.let { progress ->
                Card {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Exporting… ${(progress * 100).toInt()}%")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            ui.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}
