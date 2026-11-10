package com.cuenote.record

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File

/**
 * The in-app shutter: a viewfinder over the lesson screen with one big
 * button. The recording carries on underneath; the photo is stamped with the
 * moment the button was pressed, so it lines up with what she was saying.
 * After a shot it says "Saved" and closes by itself, back to the lesson.
 *
 * [onCameraOpening] and [onCameraClosed] let another camera user (posture)
 * step aside while this one has the camera.
 */
@Composable
fun ShutterOverlay(
    photosDir: File,
    nowMs: () -> Long,
    onTaken: (atMs: Long, fileName: String) -> Unit,
    onClose: () -> Unit,
    onCameraOpening: () -> Unit = {},
    onCameraClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onCameraOpening()
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get().also { provider = it }
                val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture)
                ready = true
            }.onFailure { status = "The camera didn't open: ${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { provider?.unbindAll() }
            onCameraClosed()
        }
    }
    LaunchedEffect(closing) {
        if (closing) { delay(700); onClose() }
    }

    fun shoot() {
        if (!ready || busy) return
        busy = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val at = nowMs()
        val name = "photo_%08d.jpg".format(at)
        photosDir.mkdirs()
        val file = File(photosDir, name)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onTaken(at, name)
                    status = "Saved"
                    closing = true
                }

                override fun onError(e: ImageCaptureException) {
                    busy = false
                    status = "That photo didn't save. Try again."
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back to the lesson", color = Color.White, fontSize = 16.sp) }
            Text("Still recording", color = Color(0xFFE0A94A), fontSize = 14.sp)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                status ?: "Fit the whole page in the frame",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.background(Color(0x99000000), CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
            )
            // One big round shutter, easy to hit without looking.
            Box(
                Modifier.size(88.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(if (ready && !busy) Color.White else Color(0x66FFFFFF), CircleShape)
                    .clickable(enabled = ready && !busy) { shoot() }
                    .semantics { contentDescription = "Take photo"; role = Role.Button },
            )
        }
    }
}
