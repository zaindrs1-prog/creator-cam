package com.creatorcam.app.ui.teleprompter

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Standalone creator tool: paste a script, set size/speed, read hands-free.
 * Deliberately separate from the camera pipeline so it can never interfere
 * with a recording.
 */
@Composable
fun TeleprompterScreen(onBack: () -> Unit) {
    var script by rememberSaveable { mutableStateOf("") }
    var fontSize by rememberSaveable { mutableFloatStateOf(28f) }
    var speed by rememberSaveable { mutableFloatStateOf(60f) } // px per second
    var dim by rememberSaveable { mutableFloatStateOf(0.92f) }
    var reading by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text("Teleprompter", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        if (!reading) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("Paste your script") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Text("Font size ${fontSize.toInt()}sp")
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 18f..64f,
                )
                Text("Scroll speed ${speed.toInt()} px/s")
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 20f..300f,
                )
                Text("Background dim ${(dim * 100).toInt()}%")
                Slider(
                    value = dim,
                    onValueChange = { dim = it },
                    valueRange = 0.3f..1f,
                )
                Button(
                    onClick = { reading = script.isNotBlank() },
                    enabled = script.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start reading") }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            ReaderView(
                script = script,
                fontSize = fontSize,
                speed = speed,
                dim = dim,
                modifier = Modifier.padding(padding),
                onEdit = { reading = false },
            )
        }
    }
}

@Composable
private fun ReaderView(
    script: String,
    fontSize: Float,
    speed: Float,
    dim: Float,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var playing by rememberSaveable { mutableStateOf(true) }
    val scroll = rememberScrollState()
    LaunchedEffect(playing, speed) {
        while (isActive && playing) {
            delay(50)
            val next = (scroll.value + speed * 0.05f).toInt()
            if (next >= scroll.maxValue) {
                playing = false
            } else {
                scroll.scrollTo(next)
            }
        }
    }
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)),
    ) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 120.dp),
        ) {
            Text(
                script,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.5f).sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(400.dp))
        }
        // Center reading guide.
        Box(
            Modifier.align(Alignment.Center)
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { playing = !playing }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
            }
            OutlinedButton(onClick = onEdit) { Text("Edit script") }
        }
    }
}
