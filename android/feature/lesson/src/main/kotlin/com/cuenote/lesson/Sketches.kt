package com.cuenote.lesson

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cuenote.data.FeedbackEntity
import com.cuenote.data.PhotoEntity
import com.cuenote.data.sketch.Bitmaps
import com.cuenote.data.sketch.SketchRepository
import com.cuenote.sketch.Pt
import com.cuenote.sketch.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Her sketches on the phone: a row of them on the lesson screen, one sketch
 * full size with "move to another card", and a corner editor for when the
 * page finder got the edges wrong.
 */

/** Reads a photo off the main thread, upright and small enough for the screen. */
@Composable
private fun rememberPhoto(file: File?, maxSide: Int = 1600): Bitmap? =
    produceState<Bitmap?>(null, file?.path) {
        value = file?.takeIf { it.isFile }?.let { f ->
            withContext(Dispatchers.IO) { runCatching { Bitmaps.loadUpright(f, maxSide) }.getOrNull() }
        }
    }.value

/** "2:31 · Keep the jaw loose…": how a card is named in pickers. */
fun cardLabel(f: FeedbackEntity): String =
    "${clockOf(f.spanStartMs)} · " + f.words.ifBlank { "(no words)" }.take(60).let { if (f.words.length > 60) "$it…" else it }

internal fun clockOf(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** The lesson's sketches in a row; each says which card it is on. */
@Composable
fun SketchStrip(
    photos: List<PhotoEntity>,
    lessonDir: File,
    feedback: List<FeedbackEntity>,
    onOpen: (PhotoEntity) -> Unit,
) {
    val byId = feedback.associateBy { it.id }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (p in photos) {
            Column(Modifier.width(132.dp).clickable { onOpen(p) }) {
                SketchThumb(p, lessonDir, Modifier.size(132.dp, 100.dp))
                Text(
                    p.feedbackId?.let(byId::get)?.let { "On the card at ${clockOf(it.spanStartMs)}" } ?: "Not on a card",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (p.linkSource != PhotoEntity.LINK_AUTO) {
                    Text(if (p.linkSource == PhotoEntity.LINK_TEACHER) "Moved by your teacher" else "Moved by you",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** A small picture of a sketch: the cleaned copy once it exists, the original until then. */
@Composable
fun SketchThumb(p: PhotoEntity, lessonDir: File, modifier: Modifier = Modifier) {
    val file = p.cleanFileName?.let { File(lessonDir, "photos/clean/$it") } ?: File(lessonDir, "photos/${p.fileName}")
    val bmp = rememberPhoto(file, maxSide = 400)
    Box(modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))) {
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = p.caption ?: "Sketch", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** One sketch, full size: the clean copy or the original, its caption, and its card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SketchScreen(
    photoId: String,
    lessonDir: File,
    feedback: List<FeedbackEntity>,
    repo: SketchRepository,
    lessonId: String,
    onFixCorners: () -> Unit,
    onBack: () -> Unit,
) {
    val photos by repo.observe(lessonId).collectAsStateWithLifecycle(emptyList())
    val p = photos.firstOrNull { it.id == photoId } ?: return
    val scope = rememberCoroutineScope()
    var showOriginal by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val clean = repo.cleaned(lessonDir, p)
    val bmp = rememberPhoto(if (showOriginal || clean == null) repo.original(lessonDir, p) else clean)
    val card = feedback.firstOrNull { it.id == p.feedbackId }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Sketch at ${clockOf(p.takenAtMs)}") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = p.caption ?: "Sketch", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Text(
                when {
                    clean == null -> "Cleaning up this photo…"
                    showOriginal -> "The photo as taken."
                    p.corners == null -> "No page edges found, so it is only brightened. Fix the corners to straighten it."
                    else -> "Straightened and brightened. The original is kept."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (clean != null) OutlinedButton(onClick = { showOriginal = !showOriginal }) {
                    Text(if (showOriginal) "Show cleaned" else "Show original")
                }
                OutlinedButton(onClick = onFixCorners) { Text("Fix the corners") }
            }
            p.caption?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
                Text("Caption written by AI from the sketch and what your teacher said.", style = MaterialTheme.typography.labelSmall)
            }
            Text("Card", style = MaterialTheme.typography.titleMedium)
            Text(card?.let(::cardLabel) ?: "Not on a card yet.", style = MaterialTheme.typography.bodyMedium)
            Text(
                when (p.linkSource) {
                    PhotoEntity.LINK_TEACHER -> "Your teacher put it here."
                    PhotoEntity.LINK_STUDENT -> "You put it here."
                    else -> "Placed by time: it was taken during or just after this feedback."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { picking = true }, enabled = feedback.isNotEmpty()) { Text("Move to another card") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Which card is this sketch about?") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(feedback, key = { it.id }) { f ->
                        Text(
                            (if (f.id == p.feedbackId) "✓ " else "") + cardLabel(f),
                            modifier = Modifier.fillMaxWidth().clickable {
                                picking = false
                                scope.launch { repo.move(p, f.id) }
                            }.padding(vertical = 10.dp),
                        )
                    }
                    item {
                        Text("Not on any card", modifier = Modifier.fillMaxWidth().clickable {
                            picking = false
                            scope.launch { repo.move(p, null) }
                        }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Drag the four corners onto the page's corners, then clean it again. Starts
 * from the corners the page finder chose, or a box just inside the photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornerEditorScreen(
    photoId: String,
    lessonId: String,
    lessonDir: File,
    repo: SketchRepository,
    onDone: () -> Unit,
) {
    val photos by repo.observe(lessonId).collectAsStateWithLifecycle(emptyList())
    val p = photos.firstOrNull { it.id == photoId } ?: return
    val bmp = rememberPhoto(repo.original(lessonDir, p), maxSide = 1600) ?: return
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    // corners as fractions of the photo: TL, TR, BR, BL
    val corners = remember(p.id) {
        val n = SketchRepository.cornersOf(p) ?: listOf(0.08, 0.08, 0.92, 0.08, 0.92, 0.92, 0.08, 0.92)
        mutableStateListOf(*List(4) { Offset(n[it * 2].toFloat(), n[it * 2 + 1].toFloat()) }.toTypedArray())
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Fix the corners") }, navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Drag each dot onto a corner of the page.", style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val aspect = bmp.width.toFloat() / bmp.height
                val boxW = maxWidth; val boxH = maxHeight
                val (w, h) = if (boxW / boxH > aspect) (boxH * aspect) to boxH else boxW to (boxW / aspect)
                val density = LocalDensity.current
                val wPx = with(density) { w.toPx() }; val hPx = with(density) { h.toPx() }
                Box(Modifier.size(w, h)) {
                    Image(bmp.asImageBitmap(), contentDescription = "The photo", modifier = Modifier.fillMaxSize())
                    val brass = MaterialTheme.colorScheme.secondary
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(wPx, hPx) {
                            var active = -1
                            detectDragGestures(
                                onDragStart = { start ->
                                    active = corners.indices.minBy { (corners[it].x * wPx - start.x).let { dx -> dx * dx } +
                                        (corners[it].y * hPx - start.y).let { dy -> dy * dy } }
                                },
                                onDragEnd = { active = -1 },
                            ) { change, drag ->
                                change.consume()
                                if (active >= 0) {
                                    val c = corners[active]
                                    corners[active] = Offset((c.x + drag.x / wPx).coerceIn(0f, 1f), (c.y + drag.y / hPx).coerceIn(0f, 1f))
                                }
                            }
                        },
                    ) {
                        val pts = corners.map { Offset(it.x * size.width, it.y * size.height) }
                        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                        drawPath(path, Color.White, style = Stroke(width = 6f))
                        drawPath(path, brass, style = Stroke(width = 3f))
                        for (pt in pts) {
                            drawCircle(Color.White, radius = 26f, center = pt)
                            drawCircle(brass, radius = 20f, center = pt)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    working = true
                    scope.launch {
                        // the editor shows a shrunk copy; corners are fractions, so any size will do
                        val q = Quad.ordered(corners.map { Pt(it.x.toDouble() * bmp.width, it.y.toDouble() * bmp.height) })
                        runCatching { repo.reclean(lessonDir, p, q, bmp.width, bmp.height) }
                        working = false
                        onDone()
                    }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (working) "Cleaning…" else "Clean it again") }
        }
    }
}
