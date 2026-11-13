package com.cuenote.record

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cuenote.posture.PoseEngine
import com.cuenote.posture.PoseFrames
import com.cuenote.posture.PostureSetup
import com.cuenote.posture.Speaker
import com.cuenote.posture.withCameraProvider
import com.cuenote.signal.posture.FramingAdvice
import com.cuenote.signal.posture.FramingCheck
import com.cuenote.signal.posture.PostureView
import com.cuenote.signal.posture.StudentPicker
import com.cuenote.signal.posture.Voice
import com.cuenote.signal.posture.VoiceLine
import com.cuenote.signal.posture.rotationFromGravity
import kotlinx.coroutines.delay

private sealed interface Framing {
    data object Idle : Framing
    data class Countdown(val seconds: Int) : Framing
    data class Checking(val advice: FramingAdvice) : Framing
    data class Done(val setup: PostureSetup) : Framing
    data object TimedOut : Framing
}

/**
 * Before a posture lesson: makes sure the camera can see the student where
 * they'll actually sing. Someone taps Start, the student walks to their spot
 * during a five-second countdown, and the phone talks them into place ("step
 * to your left"). Two seconds in frame and it says "You're all set" and
 * starts the lesson by itself; after thirty seconds it gives up and asks.
 * How the student stands here is the baseline the posture flags compare to.
 */
@Composable
fun FramingCheckScreen(onReady: (PostureSetup) -> Unit, onSkip: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current
    var state by remember { mutableStateOf<Framing>(Framing.Idle) }
    var front by remember { mutableStateOf(true) }
    var stance by remember { mutableStateOf(PostureView.FRONT) }
    var attempt by remember { mutableIntStateOf(0) }
    // Which way up the phone is, from gravity, so a phone mounted sideways
    // still gives the pose model upright pictures, even with auto-rotate off.
    val rotationNow = remember { java.util.concurrent.atomic.AtomicInteger(view.display?.rotation ?: 0) }
    var rotationKey by remember { mutableIntStateOf(rotationNow.get()) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                rotationFromGravity(e.values[0], e.values[1], e.values[2])?.let {
                    if (it != rotationNow.get()) { rotationNow.set(it); rotationKey = it }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }

    val tts = remember { Speaker(context) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; tts.close() }
    }

    val engine = remember { runCatching { PoseEngine(context) }.getOrNull() }
    DisposableEffect(engine) { onDispose { engine?.close() } }
    if (engine == null) {
        Column(Modifier.padding(16.dp)) {
            Text("Posture can't start on this phone: the pose model didn't load.")
            Button(onClick = onSkip) { Text("Record without posture") }
        }
        return
    }

    // The check itself runs on the camera's frame thread; results come back to the screen here.
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // A fresh check for each attempt; the frame thread reads whichever is current.
    val run = remember { java.util.concurrent.atomic.AtomicReference(FramingCheck() to StudentPicker(null, null)) }
    LaunchedEffect(attempt) { run.set(FramingCheck(stance) to StudentPicker(null, null)) }
    val checking = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val frames = remember {
        PoseFrames(fps = 5) { bmp, ms ->
            try {
                if (!checking.get()) return@PoseFrames
                val (checker, picker) = run.get()
                val pose = picker.pick(engine.detect(bmp, ms), ms)
                val step = checker.onFrame(ms, pose)
                mainHandler.post {
                    if (!checking.get()) return@post
                    when (step) {
                        is FramingCheck.Step.Speak -> tts.say(step.advice.line)
                        is FramingCheck.Step.Done -> {
                            checking.set(false)
                            tts.say(VoiceLine.ALL_SET)
                            state = Framing.Done(PostureSetup(step.baseline, step.centerX, step.centerY, rotationNow.get(), front))
                            return@post
                        }
                        FramingCheck.Step.TimedOut -> {
                            checking.set(false)
                            tts.say(VoiceLine.NOT_FOUND)
                            state = Framing.TimedOut
                            return@post
                        }
                        null -> Unit
                    }
                    state = Framing.Checking(checker.advice)
                }
            } finally {
                bmp.recycle()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { checking.set(false); frames.close() } }

    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(front, rotationKey) {
        var bound: ProcessCameraProvider? = null
        var gone = false
        withCameraProvider(context) { provider ->
            if (gone) return@withCameraProvider
            provider.unbindAll()
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            runCatching { provider.bindToLifecycle(lifecycle, PoseFrames.selector(front), p, frames.useCase(rotationNow.get())) }
            bound = provider
        }
        onDispose { gone = true; bound?.unbindAll() }
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is Framing.Countdown -> {
                if (s.seconds == COUNTDOWN) { tts.stopAll(); tts.say(VoiceLine.COUNTDOWN_INTRO) }
                delay(1_000)
                state = if (s.seconds > 1) Framing.Countdown(s.seconds - 1) else Framing.Checking(if (stance == PostureView.SIDE) FramingAdvice.SIDE_NOT_SEEN else FramingAdvice.NOT_SEEN)
                if (state is Framing.Checking) checking.set(true)
            }
            is Framing.Done -> { delay(1_500); onReady(s.setup) }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Can the camera see you?", style = MaterialTheme.typography.headlineSmall)
        Text("Put the phone where it will stay for the lesson, pointing at the student's singing spot.")
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView({ preview }, Modifier.fillMaxSize())
        }
        val big = when (val s = state) {
            Framing.Idle -> null
            is Framing.Countdown -> "Go to your spot… ${s.seconds}"
            is Framing.Checking -> s.advice.spoken
            is Framing.Done -> "You're all set."
            Framing.TimedOut -> "I couldn't find the student."
        }
        big?.let { Text(it, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        when (state) {
            Framing.Idle, Framing.TimedOut -> {
                // Side-on is best for a forward head; facing the phone shows a head tilt.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Student stands", Modifier.weight(1f))
                    PostureView.entries.forEach { v ->
                        FilterChip(selected = stance == v, onClick = { stance = v }, label = { Text(v.label) })
                    }
                }
                Text(
                    if (stance == PostureView.SIDE) "Side-on: watches chin forward, raised shoulders, and the chin lifting or dropping."
                    else "Facing the phone: watches chin forward, raised shoulders, and head tilt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // The voice that talks the student into place, and through recalibration later.
                var voice by remember { mutableStateOf(Speaker.voice(context)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice", Modifier.weight(1f))
                    Voice.entries.forEach { v ->
                        FilterChip(
                            selected = voice == v,
                            onClick = {
                                voice = v
                                Speaker.setVoice(context, v)
                                tts.stopAll()
                                tts.say(VoiceLine.VOICE_PICKED)
                            },
                            label = { Text(v.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Front camera", Modifier.weight(1f))
                    Switch(checked = front, onCheckedChange = { front = it })
                }
                Button(
                    onClick = { attempt++; state = Framing.Countdown(COUNTDOWN) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state == Framing.Idle) "Start the check" else "Try again") }
                TextButton(onClick = onSkip) { Text("Record without posture") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            else -> TextButton(onClick = { checking.set(false); state = Framing.Idle }) { Text("Stop the check") }
        }
        Spacer(Modifier.padding(2.dp))
    }
}

private const val COUNTDOWN = 5
