package com.cuenote.record

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.audio.RecordingService
import com.cuenote.audio.RecordingSession
import com.cuenote.audio.RecordingState
import kotlinx.coroutines.delay
import java.io.File

/**
 * The lesson screen: dark and minimal, with mark, photo and stop. It runs no
 * analysis and the screen can turn off; recording continues in the service.
 */
@Composable
fun RecordingScreen(lessonDir: File, onFinished: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by RecordingSession.state.collectAsStateWithLifecycle()
    val levels by RecordingSession.levels.collectAsStateWithLifecycle()
    var elapsed by remember { mutableLongStateOf(0L) }
    var confirmStop by remember { mutableStateOf(false) }
    var pendingPhoto by rememberSaveable { mutableStateOf<Pair<Long, String>?>(null) }

    LaunchedEffect(Unit) {
        while (true) { elapsed = RecordingSession.elapsedMs(); delay(500) }
    }
    // Leave once the recording ends. The app saves the lesson and resets the
    // session to Idle right away, so remember that we saw it recording.
    var sawRecording by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is RecordingState.Recording) sawRecording = true
        else if (sawRecording || state is RecordingState.Failed || state is RecordingState.Finished) onFinished()
    }
    LaunchedEffect(Unit) {
        delay(5_000)
        if (!sawRecording) onFinished() // the service could not start the mic
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pendingPhoto
        if (ok && p != null) RecordingSession.photo(p.first, p.second)
        pendingPhoto = null
    }

    val rec = state as? RecordingState.Recording
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatDuration(elapsed), color = Color.White, fontSize = 48.sp)
            Text(rec?.micName ?: "Starting…", color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LiveDot("You", levels?.student?.rmsDb)
                LiveDot("Teacher", levels?.teacher?.rmsDb)
            }
            if ((rec?.gaps ?: 0) > 0) Text("${rec!!.gaps} audio gaps so far", color = Color(0xFFFFB4AB))
            Text("${rec?.marks ?: 0} marks · ${rec?.photos ?: 0} photos", color = Color.Gray)
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Mark", Color(0xFF3A3A3A)) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                RecordingSession.mark()
            }
            BigButton("Photo", Color(0xFF3A3A3A)) {
                val at = RecordingSession.elapsedMs()
                val dir = File(lessonDir, "photos").apply { mkdirs() }
                val name = "photo_%06d.jpg".format(at / 1000)
                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, name))
                pendingPhoto = at to name
                camera.launch(uri)
            }
            BigButton("Stop", Color(0xFF8C1D18)) { confirmStop = true }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop recording?") },
            text = { Text("This ends the lesson recording.") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; RecordingService.stop(context) }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep recording") } },
        )
    }
}

@Composable
private fun LiveDot(label: String, db: Double?) {
    val alive = db != null && db > -55
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(if (alive) Color(0xFF6DD58C) else Color(0xFF555555), CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(label, color = Color.Gray)
    }
}

@Composable
private fun BigButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(72.dp),
    ) { Text(text, fontSize = 22.sp) }
}
