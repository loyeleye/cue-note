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
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
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

/** The lesson's photos in a row, sketches and photos of me; each says which card it is on. */
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

/**
 * A small picture of a photo: for a sketch, the cleaned copy once it exists
 * (the original until then); a photo of me as taken, cropped toward the mouth.
 * A label on top says which it is, and whether a photo of me is shared.
 */
@Composable
fun SketchThumb(p: PhotoEntity, lessonDir: File, modifier: Modifier = Modifier, label: Boolean = true) {
    val file = if (p.isMe) File(lessonDir, "photos/${p.fileName}")
        else p.cleanFileName?.let { File(lessonDir, "photos/clean/$it") } ?: File(lessonDir, "photos/${p.fileName}")
    val bmp = rememberPhoto(file, maxSide = 400)
    val m = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .border(if (p.isMe) 2.dp else 1.dp, if (p.isMe) m.secondary else m.outlineVariant, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) { contentDescription = photoDescription(p) },
    ) {
        if (bmp != null) Image(
            bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            alignment = if (p.isMe) MouthAlignment else Alignment.Center, modifier = Modifier.fillMaxSize(),
        )
        if (label) PhotoBadge(p, Modifier.align(Alignment.TopStart).padding(4.dp))
    }
}

/** A photo of me is framed with the face a little high, so the mouth sits just below the middle. */
val MouthAlignment = BiasAlignment(0f, 0.25f)

/** "Sketch", "Me" with a lock (on this phone only) or "Me · Shared". */
@Composable
fun PhotoBadge(p: PhotoEntity, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xC7231C2B), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (p.isMe) Icon(
            if (p.shared) Icons.Filled.People else Icons.Filled.Lock, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(12.dp),
        )
        Text(
            when { !p.isMe -> "Sketch"; p.shared -> "Me · Shared"; else -> "Me" },
            color = Color.White, fontSize = 12.sp,
        )
    }
}

fun photoDescription(p: PhotoEntity): String = when {
    !p.isMe -> p.caption?.let { "Sketch: $it" } ?: "Sketch"
    p.shared -> "Photo of you, shared with your teacher"
    else -> "Photo of you, private, on this phone only"
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
    /** Who teaches this lesson, for "Share with Marigold"; null for "your teacher". */
    teacherName: String? = null,
    /** Signed in with a teacher on Cue Note, so a photo of me can be shared. */
    canShare: Boolean = true,
) {
    val photos by repo.observe(lessonId).collectAsStateWithLifecycle(emptyList())
    val p = photos.firstOrNull { it.id == photoId }
    // deleted: back to the lesson (but not while the list is still loading)
    var seen by remember { mutableStateOf(false) }
    if (p != null) seen = true
    LaunchedEffect(p == null, seen) { if (p == null && seen) onBack() }
    if (p == null) return
    if (p.isMe) {
        MePhotoScreen(p, lessonDir, feedback, repo, teacherName, canShare, onBack)
        return
    }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val clean = repo.cleaned(lessonDir, p)
    val bmp = rememberPhoto(if (showOriginal || clean == null) repo.original(lessonDir, p) else clean)
    val card = feedback.firstOrNull { it.id == p.feedbackId }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Sketch · ${clockOf(p.takenAtMs)}") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }, enabled = feedback.isNotEmpty()) { Text("Move to another card") }
                // taken with the switch on Sketch by mistake: make it private again
                OutlinedButton(onClick = { scope.launch { repo.setKind(lessonDir, p, PhotoEntity.KIND_ME) } }) { Text("Change to photo of me") }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete photo", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) DeleteDialog(onDelete = { confirmDelete = false; scope.launch { repo.delete(lessonDir, p) } }, onCancel = { confirmDelete = false })

    if (picking) CardPicker(p, feedback, "Which card is this sketch about?", onPick = { picking = false; scope.launch { repo.move(p, it) } }, onCancel = { picking = false })
}

/** Picks the card a photo belongs on, or none. */
@Composable
private fun CardPicker(p: PhotoEntity, feedback: List<FeedbackEntity>, title: String, onPick: (String?) -> Unit, onCancel: () -> Unit) {
    run {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(feedback, key = { it.id }) { f ->
                        Text(
                            (if (f.id == p.feedbackId) "✓ " else "") + cardLabel(f),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(f.id) }.padding(vertical = 10.dp),
                        )
                    }
                    item {
                        Text("Not on any card", modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeleteDialog(onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete this photo?") },
        text = { Text("It's removed from this phone, and from your teacher's dashboard if they have it.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
    )
}

/**
 * One photo of me, full size and as taken. It stays on this phone until the
 * student shares it; the share switch is this screen's main control. They
 * can write what to notice, move it to another card, or change it to a
 * sketch (which cleans it up, captions it and sends it to the teacher, so
 * that asks first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MePhotoScreen(
    p: PhotoEntity,
    lessonDir: File,
    feedback: List<FeedbackEntity>,
    repo: SketchRepository,
    teacherName: String?,
    canShare: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bmp = rememberPhoto(repo.original(lessonDir, p))
    val card = feedback.firstOrNull { it.id == p.feedbackId }
    val teacher = teacherName ?: "your teacher"
    var picking by remember { mutableStateOf(false) }
    var confirmSketch by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember(p.id) { mutableStateOf(p.note.orEmpty()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(topBar = {
        TopAppBar(title = { Text("Photo of you · ${clockOf(p.takenAtMs)}") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = photoDescription(p),
                    modifier = Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop, alignment = MouthAlignment,
                )
            }
            Text("Kept as you took it. No cleanup, no AI caption.", style = MaterialTheme.typography.bodySmall, color = muted)

            // The main decision here: share it or keep it to yourself.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .toggleable(value = p.shared, enabled = canShare, role = Role.Switch) { on -> scope.launch { repo.setShared(p, on) } }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Share with $teacher", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            !canShare -> "Only you can see it. Sign in with your teacher on Cue Note to share it."
                            p.shared -> "${teacher.replaceFirstChar { it.uppercase() }} can see it on their dashboard. Turn this off to take it down."
                            else -> "Only you can see it. It stays on this phone."
                        },
                        style = MaterialTheme.typography.bodySmall, color = muted,
                    )
                }
                Switch(checked = p.shared, onCheckedChange = null, enabled = canShare)
            }

            Text("What to notice", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                placeholder = { Text("For example: jaw dropped, lips not spread") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { scope.launch { repo.setNote(p, note) } }),
                singleLine = false,
                maxLines = 3,
            )
            if (note.trim() != p.note.orEmpty()) {
                TextButton(onClick = { scope.launch { repo.setNote(p, note) } }) { Text("Save") }
            }

            Text("Card", style = MaterialTheme.typography.titleMedium)
            Text(card?.let(::cardLabel) ?: "Not on a card yet.", style = MaterialTheme.typography.bodyMedium)
            Text(
                when (p.linkSource) {
                    PhotoEntity.LINK_TEACHER -> "Your teacher put it here."
                    PhotoEntity.LINK_STUDENT -> "You put it here."
                    else -> "Placed by time: it was taken during or just after this feedback."
                },
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }, enabled = feedback.isNotEmpty()) { Text("Move to another card") }
                OutlinedButton(onClick = { confirmSketch = true }) { Text("Change to sketch") }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete photo", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) CardPicker(p, feedback, "Which card is this photo about?", onPick = { picking = false; scope.launch { repo.move(p, it) } }, onCancel = { picking = false })
    if (confirmSketch) AlertDialog(
        onDismissRequest = { confirmSketch = false },
        title = { Text("Change to a sketch?") },
        text = { Text("Sketches are cleaned up, captioned by AI and sent to $teacher.") },
        confirmButton = { TextButton(onClick = { confirmSketch = false; scope.launch { repo.setKind(lessonDir, p, PhotoEntity.KIND_SKETCH) } }) { Text("Change to sketch") } },
        dismissButton = { TextButton(onClick = { confirmSketch = false }) { Text("Keep it as me") } },
    )
    if (confirmDelete) DeleteDialog(onDelete = { confirmDelete = false; scope.launch { repo.delete(lessonDir, p) } }, onCancel = { confirmDelete = false })
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
