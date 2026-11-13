package com.cuenote.record

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
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
import com.cuenote.data.sketch.Bitmaps
import com.cuenote.posture.withCameraProvider
import com.cuenote.signal.LessonEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.graphics.Matrix
import kotlinx.coroutines.delay
import java.io.File

/**
 * The in-app shutter: a viewfinder over the lesson screen with one big
 * button. The recording carries on underneath; the photo is stamped with the
 * moment the button was pressed, so it lines up with what she was saying.
 * After a shot it says "Saved" and closes by itself, back to the lesson.
 *
 * A switch above the shutter picks what the photo is: a **Sketch** of the
 * teacher's (back camera) or **Me**, a photo of the student themself, say of
 * the mouth shape the teacher asked for (front camera, saved mirrored so it
 * matches a mirror; a flip button turns to the back camera for a side view
 * someone else takes). [kind] is held by the caller, so the choice lasts the
 * lesson.
 *
 * [onCameraOpening] and [onCameraClosed] let another camera user (posture)
 * step aside while this one has the camera.
 */
@Composable
fun ShutterOverlay(
    photosDir: File,
    nowMs: () -> Long,
    kind: String,
    onKind: (String) -> Unit,
    onTaken: (atMs: Long, fileName: String, kind: String) -> Unit,
    onClose: () -> Unit,
    onCameraOpening: () -> Unit = {},
    onCameraClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val me = kind == LessonEvent.Photo.ME
    // Me starts on the front camera; the flip button is for a side view someone else takes.
    var meBack by remember { mutableStateOf(false) }
    val front = me && !meBack
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onCameraOpening()
        withCameraProvider(context) { p -> provider = p }
        onDispose {
            runCatching { provider?.unbindAll() }
            onCameraClosed()
        }
    }
    // (Re)binds when the lens changes; the shutter waits until the new lens is live.
    DisposableEffect(provider, front) {
        val p = provider
        if (p != null) {
            ready = false
            runCatching {
                val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                p.unbindAll()
                val lens = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.bindToLifecycle(lifecycle, lens, pv, capture)
                ready = true
                status = null
            }.onFailure { status = "The camera didn't open: ${it.message}" }
        }
        onDispose { }
    }
    LaunchedEffect(closing) {
        if (closing) { delay(700); onClose() }
    }

    fun shoot() {
        if (!ready || busy) return
        busy = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val at = nowMs()
        val shotKind = kind
        val mirror = front
        val name = (if (shotKind == LessonEvent.Photo.ME) "me_%08d.jpg" else "photo_%08d.jpg").format(at)
        photosDir.mkdirs()
        val file = File(photosDir, name)
        // Saved as the camera sees it; a front-camera photo is then flipped in the pixels themselves.
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    scope.launch {
                        if (mirror) withContext(Dispatchers.IO) { runCatching { mirrorInPlace(file) } }
                        onTaken(at, name, shotKind)
                        status = if (shotKind == LessonEvent.Photo.ME) "Saved. Only you can see it." else "Saved"
                        closing = true
                    }
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
        if (me && front) FaceGuide(Modifier.fillMaxSize())
        // Scrims, so the words read over a bright face or a dark room alike.
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(300.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
        )
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back to the lesson", color = Color.White, fontSize = 16.sp) }
            Text("Still recording", color = Color(0xFFE0A94A), fontSize = 14.sp)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                status ?: if (me) "Only you will see this, unless you share it" else "Fit the whole page in the frame",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.background(Color(0x99000000), CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
            )
            KindSwitch(kind, enabled = !busy, onKind = { if (it != kind) { meBack = false; onKind(it) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                // balances the flip button, so the shutter stays in the middle
                Spacer(Modifier.size(56.dp))
                Spacer(Modifier.width(28.dp))
                // One big round shutter, easy to hit without looking.
                Box(
                    Modifier.size(88.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(if (ready && !busy) Color.White else Color(0x66FFFFFF), CircleShape)
                        .clickable(enabled = ready && !busy) { shoot() }
                        .semantics { contentDescription = if (me) "Take a photo of me" else "Take a photo of the sketch"; role = Role.Button },
                )
                Spacer(Modifier.width(28.dp))
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (me) IconButton(onClick = { meBack = !meBack }, enabled = !busy, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Filled.Cameraswitch,
                            contentDescription = if (meBack) "Turn to the front camera" else "Turn to the back camera, for a side view someone else takes",
                            tint = Color.White,
                        )
                    }
                }
            }
            if (me) Text(
                if (front) "Front camera, saved like a mirror" else "Back camera: hand the phone over for a side view",
                color = Color(0xFFDDDDDD), fontSize = 13.sp,
            )
        }
    }
}

/** Sketch or Me, as two 48dp segments on a solid dark track; brass marks the one chosen. */
@Composable
private fun KindSwitch(kind: String, enabled: Boolean, onKind: (String) -> Unit) {
    Row(
        Modifier.background(Color(0x99000000), CircleShape).border(1.dp, Color(0x33FFFFFF), CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((k, label) in listOf(LessonEvent.Photo.SKETCH to "Sketch", LessonEvent.Photo.ME to "Me")) {
            val on = k == kind
            Box(
                Modifier.height(48.dp).width(104.dp)
                    .background(if (on) Color(0xFFE0A94A) else Color.Transparent, CircleShape)
                    .selectable(selected = on, enabled = enabled, role = Role.Tab) { onKind(k) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color(0xFF2A1D05) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A faint oval where the face goes, a little high so the mouth sits mid-frame. */
@Composable
private fun FaceGuide(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width * 0.62f
        val h = w * 1.3f
        val top = size.height * 0.40f - h / 2
        drawOval(Color(0x66FFFFFF), topLeft = Offset((size.width - w) / 2, top), size = Size(w, h), style = Stroke(width = 3.dp.toPx()))
    }
}

/** Flips a saved photo left to right in its pixels (no EXIF flag that viewers read differently), upright. */
private fun mirrorInPlace(file: File) {
    val bmp = Bitmaps.loadUpright(file)
    val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preScale(-1f, 1f) }, true)
    Bitmaps.saveJpeg(flipped, file, quality = 92)
    if (flipped !== bmp) flipped.recycle()
    bmp.recycle()
}
