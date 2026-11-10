package com.cuenote.record

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.audio.RecordingService
import com.cuenote.audio.RecordingSession
import com.cuenote.audio.RecordingState
import java.io.File
import kotlinx.coroutines.delay

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

    // The in-app shutter needs the camera permission the first time.
    var shutterOpen by rememberSaveable { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shutterOpen = true
        else Toast.makeText(context, "Cue Note needs the camera to photograph her sketches.", Toast.LENGTH_LONG).show()
    }

    val rec = state as? RecordingState.Recording
    Column(
        Modifier.fillMaxSize().background(StageBg).padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RecordingBadge(live = rec != null)
            Text(
                formatDuration(elapsed),
                color = StageText,
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text(rec?.micName ?: "Starting…", color = StageMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LiveDot("You", levels?.student?.rmsDb)
                LiveDot("Teacher", levels?.teacher?.rmsDb)
            }
            if ((rec?.gaps ?: 0) > 0) Text("${rec!!.gaps} audio gaps so far", color = StageWarn)
            Text("${rec?.marks ?: 0} marks · ${rec?.photos ?: 0} photos", color = StageMuted)
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Mark is the button you reach for mid-phrase, so it is the biggest and brightest.
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    RecordingSession.mark()
                },
                colors = ButtonDefaults.buttonColors(containerColor = StageBrass, contentColor = StageBrassInk),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) { Text("Mark this moment", fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        shutterOpen = true
                    } else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StageRaised, contentColor = StageText),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) { Text("Photo", fontSize = 20.sp) }
            // Stop is quieter and asks first, so a stray tap can't end the lesson.
            OutlinedButton(
                onClick = { confirmStop = true },
                border = BorderStroke(1.5.dp, StageWarn),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StageWarn),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) { Text("Stop", fontSize = 18.sp) }
        }
    }

    if (shutterOpen && rec != null) {
        ShutterOverlay(
            photosDir = File(lessonDir, "photos"),
            nowMs = RecordingSession::elapsedMs,
            onTaken = { at, name -> RecordingSession.photo(at, name) },
            onClose = { shutterOpen = false },
        )
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

/** "Recording" with a slow brass pulse, like a breath; still when the phone asks for less motion. */
@Composable
private fun RecordingBadge(live: Boolean) {
    val context = LocalContext.current
    val calm = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val pulse by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp)
                .alpha(if (live && !calm) pulse else 1f)
                .background(if (live) StageBrass else StageMuted, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (live) "Recording" else "Getting ready", color = StageMuted, fontSize = 14.sp)
    }
}

/** A voice's mic: a filled dot and "hearing" when it picks up sound, a ring and "quiet" when not. */
@Composable
private fun LiveDot(label: String, db: Double?) {
    val alive = db != null && db > -55
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp)
                .border(2.dp, if (alive) StageBrass else StageMuted, CircleShape)
                .background(if (alive) StageBrass else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(label, color = StageText)
        Spacer(Modifier.size(4.dp))
        Text(if (alive) "hearing" else "quiet", color = StageMuted, fontSize = 13.sp)
    }
}

// The recording screen is always dark (the phone sits on a stand all lesson),
// in the app's "stage" colours.
private val StageBg = Color(0xFF1B1620)
private val StageRaised = Color(0xFF2E2735)
private val StageText = Color(0xFFEEE8F2)
private val StageMuted = Color(0xFFACA2B5)
private val StageBrass = Color(0xFFE0A94A)
private val StageBrassInk = Color(0xFF2A1D05)
private val StageWarn = Color(0xFFF2A49B)
