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
import com.cuenote.posture.PostureService
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
import com.cuenote.ui.LevelRow
import com.cuenote.ui.rememberReducedMotion
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * The lesson screen: dark and minimal, with mark, photo and stop. It runs no
 * analysis and the screen can turn off; recording continues in the service.
 */
@Composable
fun RecordingScreen(lessonDir: File, onFinished: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by RecordingSession.state.collectAsStateWithLifecycle()
    // Kept as a State: the bars read it every frame without recomposing the screen.
    val levels = RecordingSession.levels.collectAsStateWithLifecycle()
    var markedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(markedAt) { if (markedAt != null) { delay(2_000); markedAt = null } }
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
        else Toast.makeText(context, "Cue Note needs the camera to photograph your teacher's sketches.", Toast.LENGTH_LONG).show()
    }

    val rec = state as? RecordingState.Recording
    Column(
        Modifier
            .fillMaxSize()
            .background(StageBg)
            // A still, warm glow behind the clock: the stage light, not an animation.
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(StageBrass.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.2f),
                        radius = size.width * 0.75f,
                    ),
                )
            }
            .padding(24.dp),
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
            Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LevelRow(
                    "You", { levels.value?.student?.rmsDb },
                    track = StageRaised, fill = StageBrass, text = StageText, muted = StageMuted, accent = StageBrass, warn = StageWarn, barHeight = 10.dp,
                )
                LevelRow(
                    "Teacher", { levels.value?.teacher?.rmsDb },
                    track = StageRaised, fill = StageBrass, text = StageText, muted = StageMuted, accent = StageBrass, warn = StageWarn, barHeight = 10.dp,
                )
            }
            if ((rec?.gaps ?: 0) > 0) Text("${rec!!.gaps} audio gaps so far", color = StageWarn)
            Text(
                markedAt?.let { "Marked ${formatDuration(it)}" } ?: "${rec?.marks ?: 0} marks · ${rec?.photos ?: 0} photos",
                color = if (markedAt != null) StageBrass else StageMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            PostureLine()
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Mark is the button you reach for mid-phrase, so it is the biggest and brightest.
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    RecordingSession.mark()
                    markedAt = RecordingSession.elapsedMs()
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
            // posture's camera steps aside while the shutter has it
            onCameraOpening = { PostureService.pauseFor(context, "photo") },
            onCameraClosed = { PostureService.resumeAfter(context, "photo") },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("That's a wrap?") },
            text = { Text("Stopping ends today's recording.") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; RecordingService.stop(context) }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep going") } },
        )
    }
}

/** "Recording" with a slow brass pulse, like a breath; still when the phone asks for less motion. */
@Composable
private fun RecordingBadge(live: Boolean) {
    val calm = rememberReducedMotion()
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

// The recording screen is always dark (the phone sits on a stand all lesson),
// in the app's "stage" colours.
private val StageBg = Color(0xFF1B1620)
private val StageRaised = Color(0xFF2E2735)
private val StageText = Color(0xFFEEE8F2)
private val StageMuted = Color(0xFFACA2B5)
private val StageBrass = Color(0xFFE0A94A)
private val StageBrassInk = Color(0xFF2A1D05)
private val StageWarn = Color(0xFFF2A49B)
