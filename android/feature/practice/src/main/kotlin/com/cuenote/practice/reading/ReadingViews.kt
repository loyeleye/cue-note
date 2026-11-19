package com.cuenote.practice.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cuenote.pipeline.reading.Accidental
import com.cuenote.pipeline.reading.Clef
import com.cuenote.pipeline.reading.Melody
import com.cuenote.pipeline.reading.NoteMatch
import com.cuenote.pipeline.reading.ReadingLadder
import com.cuenote.pipeline.reading.ReadingProgress
import com.cuenote.pipeline.reading.ReadingStage
import com.cuenote.pipeline.reading.Staff
import com.cuenote.practice.R
import com.cuenote.ui.Brass
import com.cuenote.ui.StartButton
import kotlin.math.abs

/** The app's music font: a few glyphs from Bravura (SIL Open Font License), renamed as the license asks. */
private val MusicFont = FontFamily(Font(R.font.cue_music))

private object Glyph {
    const val G_CLEF = ""
    const val G_CLEF_8VB = ""
    const val F_CLEF = ""
    const val WHOLE = ""
    const val HALF = ""
    const val BLACK = ""
    const val FLAT = ""
    const val SHARP = ""
}

/**
 * One note on a five-line staff with its clef, drawn large enough to read
 * from a music stand. [noteColor] lets an exercise colour the note when it's
 * matched.
 */
@Composable
fun StaffNote(
    midi: Int?,
    clef: Clef,
    modifier: Modifier = Modifier,
    noteColor: Color = MaterialTheme.colorScheme.onSurface,
    space: Dp = 14.dp,
) = StaffNotes(listOfNotNull(midi), clef, modifier, listOf(noteColor), space)

/**
 * Notes side by side on one staff, left to right, for seeing a step, skip or
 * leap. [colors] colours each note in turn (the last colour repeats); a
 * [hidden] note is left off, for "sing it before you see it".
 */
@Composable
fun StaffNotes(
    notes: List<Int>,
    clef: Clef,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(MaterialTheme.colorScheme.onSurface),
    space: Dp = 14.dp,
    hidden: Set<Int> = emptySet(),
) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val label = when {
        notes.isEmpty() -> "An empty staff"
        notes.size == 1 -> "A note ${Staff.describe(Staff.place(notes[0], clef))}"
        else -> notes.withIndex().filter { it.index !in hidden }
            .joinToString(", then ") { "a note ${Staff.describe(Staff.place(it.value, clef))}" }.replaceFirstChar { it.uppercase() }
    }
    Canvas(modifier.fillMaxWidth().height(space * 12).semantics { contentDescription = label }) {
        val sp = space.toPx()
        val stroke = 1.2.dp.toPx()
        // The staff sits in the middle, with room for three ledger lines either side.
        val bottom = size.height / 2 + 2 * sp
        fun y(step: Int) = bottom - step * sp / 2
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        for (i in 0 until 5) drawLine(lineColor, Offset(left, y(i * 2)), Offset(right, y(i * 2)), stroke)

        // SMuFL glyphs are sized so that the em is four staff spaces.
        val glyphStyle = TextStyle(fontFamily = MusicFont, fontSize = (4 * sp).toSp(), color = ink)
        fun glyph(text: String, x: Float, baseline: Float, color: Color = ink) {
            val layout = measurer.measure(text, glyphStyle.copy(color = color))
            drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
        }
        when (clef) {
            Clef.TREBLE -> glyph(Glyph.G_CLEF, left + sp * 0.4f, y(2))
            Clef.TREBLE_8VB -> glyph(Glyph.G_CLEF_8VB, left + sp * 0.4f, y(2))
            Clef.BASS -> glyph(Glyph.F_CLEF, left + sp * 0.4f, y(6))
        }
        val headW = sp * 1.7f
        // One note sits a little right of centre; more share the space after the clef.
        val xs = if (notes.size <= 1) listOf(size.width * 0.58f)
            else notes.indices.map { i -> size.width * (0.42f + 0.4f * i / (notes.size - 1)) }
        notes.forEachIndexed { i, midi ->
            if (i in hidden) return@forEachIndexed
            val color = colors.getOrElse(i) { colors.last() }
            val p = Staff.place(midi, clef)
            val cx = xs[i]
            for (l in p.ledgers) drawLine(lineColor, Offset(cx - headW * 0.85f, y(l)), Offset(cx + headW * 0.85f, y(l)), stroke * 1.3f)
            p.accidental?.let { glyph(if (it == Accidental.SHARP) Glyph.SHARP else Glyph.FLAT, cx - headW * 1.6f, y(p.step), color) }
            glyph(Glyph.WHOLE, cx - headW / 2, y(p.step), color)
        }
    }
}

/**
 * Where the voice is against the note, as it sings: the note is the band in
 * the middle, the dot is the voice, and the trail behind it shows the last
 * couple of seconds. Off the top or bottom, the dot waits at the edge with an
 * arrow. No numbers.
 */
@Composable
fun LiveMeter(state: NoteMatch.State?, trail: List<Double?>, modifier: Modifier = Modifier) {
    val m = MaterialTheme.colorScheme
    val on = state?.hint == NoteMatch.Hint.ON || state?.settled == true
    val words = when {
        state == null -> "Listening…"
        state.settled -> "Got it"
        else -> NoteMatch.words(state.hint)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(m.surfaceVariant.copy(alpha = 0.5f))
            .semantics { contentDescription = words }) {
            val mid = size.height / 2
            val perCent = size.height / 2 / RANGE_CENTS
            fun y(c: Double) = (mid - c.coerceIn(-RANGE_CENTS, RANGE_CENTS) * perCent).toFloat()
            // The note: a band as wide as "on".
            val band = (NoteMatch.BEGINNER_CENTS * perCent).toFloat()
            drawRoundRect(m.primary.copy(alpha = if (on) 0.28f else 0.14f), Offset(0f, mid - band), Size(size.width, band * 2), CornerRadius(band))
            drawLine(m.primary, Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            // Half steps, faintly, so "a bit low" has a place.
            for (k in listOf(-300, -200, -100, 100, 200, 300)) {
                drawLine(m.outlineVariant.copy(alpha = 0.5f), Offset(0f, y(k.toDouble())), Offset(size.width, y(k.toDouble())), 1.dp.toPx())
            }
            // The trail, oldest at the left.
            val cx = size.width * 0.72f
            val stepX = cx / TRAIL.coerceAtLeast(1)
            trail.takeLast(TRAIL).forEachIndexed { i, c ->
                if (c != null) drawCircle(m.onSurfaceVariant.copy(alpha = 0.15f + 0.45f * i / TRAIL), 3.dp.toPx(),
                    Offset(cx - (trail.takeLast(TRAIL).size - 1 - i) * stepX, y(c)))
            }
            val c = state?.cents
            if (c != null) {
                val dot = if (on) m.primary else Brass
                drawCircle(dot, 11.dp.toPx(), Offset(cx, y(c)))
                if (abs(c) > RANGE_CENTS) arrow(cx, y(c), up = c < 0, color = dot)
                // The hold fills a ring around the dot.
                val held = state.held
                if (held > 0f) drawArc(m.primary, -90f, 360f * held, false, Offset(cx - 17.dp.toPx(), y(c) - 17.dp.toPx()),
                    Size(34.dp.toPx(), 34.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
        Text(words, style = MaterialTheme.typography.titleLarge, color = if (state?.settled == true) m.primary else m.onSurface)
        if (state != null && state.settled.not() && state.cents != null && state.octave != 0) {
            Text(
                if (state.octave < 0) "You're an octave below the piano. That's fine, it's the same note." else "You're an octave above the piano. That's fine, it's the same note.",
                style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.arrow(x: Float, y: Float, up: Boolean, color: Color) {
    // The voice is past the edge: point the way it needs to go.
    val dir = if (up) -1f else 1f
    val tip = Offset(x, y + dir * 28.dp.toPx())
    drawLine(color, Offset(x - 8.dp.toPx(), y + dir * 18.dp.toPx()), tip, 3.dp.toPx())
    drawLine(color, Offset(x + 8.dp.toPx(), y + dir * 18.dp.toPx()), tip, 3.dp.toPx())
}

private const val RANGE_CENTS = 350.0
private const val TRAIL = 60

/** The reading and pitch card on the Practice tab. */
@Composable
fun ReadingCard(progress: ReadingProgress, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val m = MaterialTheme.colorScheme
    val stage = ReadingLadder.stage(progress)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(m.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("READING AND PITCH", style = MaterialTheme.typography.labelMedium, color = m.primary, fontWeight = FontWeight.SemiBold)
        Text("Stage ${stage.ordinal + 1} of ${ReadingStage.entries.size}: ${stage.title}", style = MaterialTheme.typography.titleLarge)
        StageDots(stage)
        Text(ReadingLadder.progressWords(progress), color = m.onSurfaceVariant)
        StartButton("Practice reading and pitch", onOpen, Modifier.fillMaxWidth())
    }
}

/** The five stages as dots, the current one filled. */
@Composable
fun StageDots(stage: ReadingStage, modifier: Modifier = Modifier) {
    val m = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ReadingStage.entries.forEach { s ->
            val c = when {
                s.ordinal < stage.ordinal -> m.primary
                s == stage -> Brass
                else -> m.outlineVariant
            }
            Canvas(Modifier.size(if (s == stage) 12.dp else 9.dp)) { drawCircle(c) }
        }
    }
}

/** A filled circle for a done item. */
@Composable
fun DoneDot(done: Boolean, modifier: Modifier = Modifier) {
    val m = MaterialTheme.colorScheme
    val c = if (done) m.primary else m.outlineVariant
    Canvas(modifier.size(10.dp).clip(CircleShape)) { drawCircle(c) }
}

/**
 * A short melody as it's printed: clef, key signature, time signature,
 * notes with their stems, beams and dots, and bar lines, two bars to a
 * line so it reads on a phone. [color] colours each note (for sung, helped
 * and still to come); [current] is shaded behind, so the eye knows where
 * the voice is.
 */
@Composable
fun MelodyStaff(
    melody: Melody,
    clef: Clef,
    modifier: Modifier = Modifier,
    color: (Int) -> Color = { Color.Unspecified },
    current: Int? = null,
    space: Dp = 10.dp,
    barsPerLine: Int = 2,
) {
    val m = MaterialTheme.colorScheme
    val lineColor = m.onSurfaceVariant
    val ink = m.onSurface
    val shade = m.primary.copy(alpha = 0.14f)
    val measurer = rememberTextMeasurer()
    val lines = (melody.bars + barsPerLine - 1) / barsPerLine
    val label = "A melody in ${melody.key.title}, ${melody.beatsPerBar} beats to a bar, starting on ${Staff.letter(melody.notes[0].midi)}" +
        (current?.let { ". Now on note ${it + 1} of ${melody.notes.size}" } ?: "")
    Canvas(modifier.fillMaxWidth().height(space * 11 * lines).semantics { contentDescription = label }) {
        val sp = space.toPx()
        val stroke = 1.1.dp.toPx()
        val glyphStyle = TextStyle(fontFamily = MusicFont, fontSize = (4 * sp).toSp(), color = ink)
        val digitStyle = TextStyle(fontSize = (2.7f * sp).toSp(), fontWeight = FontWeight.Black, color = ink)
        fun glyph(text: String, x: Float, baseline: Float, c: Color = ink) {
            val layout = measurer.measure(text, glyphStyle.copy(color = c))
            drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
        }
        val left = 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val lineH = size.height / lines
        for (line in 0 until lines) {
            val bottom = lineH * line + lineH / 2 + 2 * sp
            fun y(step: Int) = bottom - step * sp / 2
            for (i in 0 until 5) drawLine(lineColor, Offset(left, y(i * 2)), Offset(right, y(i * 2)), stroke)
            var x = left + sp * 0.3f
            when (clef) {
                Clef.TREBLE -> glyph(Glyph.G_CLEF, x, y(2))
                Clef.TREBLE_8VB -> glyph(Glyph.G_CLEF_8VB, x, y(2))
                Clef.BASS -> glyph(Glyph.F_CLEF, x, y(6))
            }
            x += sp * 3.2f
            melody.key.signatureStep(clef)?.let { st ->
                glyph(if (melody.key.signature == Accidental.SHARP) Glyph.SHARP else Glyph.FLAT, x, y(st))
                x += sp * 1.4f
            }
            if (line == 0) {
                for ((digit, st) in listOf(melody.beatsPerBar.toString() to 6, "4" to 2)) {
                    val l = measurer.measure(digit, digitStyle)
                    drawText(l, topLeft = Offset(x, y(st) - l.size.height / 2f))
                }
                x += sp * 2.2f
            }
            val firstBar = line * barsPerLine
            val barsHere = minOf(barsPerLine, melody.bars - firstBar)
            val barW = (right - x) / barsPerLine
            for (b in 0 until barsHere) {
                val bx = x + (b + 1) * barW
                val last = firstBar + b == melody.bars - 1
                if (last) {
                    drawLine(ink, Offset(bx - sp * 0.7f, y(0)), Offset(bx - sp * 0.7f, y(8)), stroke)
                    drawLine(ink, Offset(bx - sp * 0.25f, y(0)), Offset(bx - sp * 0.25f, y(8)), sp * 0.45f)
                } else drawLine(ink, Offset(bx, y(0)), Offset(bx, y(8)), stroke)
            }
            // Notes in this line, spaced by where they fall in the bar.
            val idx = melody.notes.indices.filter { melody.barOf(it) / barsPerLine == line }
            val headW = sp * 1.18f
            fun noteX(i: Int): Float {
                val n = melody.notes[i]
                val bar = melody.barOf(i) - firstBar
                val inBar = n.start - melody.barOf(i) * melody.beatsPerBar
                return x + bar * barW + sp * 1.0f + inBar / melody.beatsPerBar * (barW - sp * 2.4f)
            }
            val stemLen = sp * 3.4f
            var skipBeam = -1
            for (i in idx) {
                val n = melody.notes[i]
                val p = melody.key.place(n.midi, clef)
                val nx = noteX(i)
                val c = color(i).takeOrElse { ink }
                if (i == current) drawRoundRect(shade, Offset(nx - sp * 0.5f, y(10)), Size(headW + sp, y(-2) - y(10)), CornerRadius(sp * 0.6f))
                val w = if (n.beats >= 4f) sp * 1.69f else headW
                for (l in p.ledgers) drawLine(lineColor, Offset(nx - sp * 0.35f, y(l)), Offset(nx + w + sp * 0.35f, y(l)), stroke * 1.3f)
                p.accidental?.let { glyph(if (it == Accidental.SHARP) Glyph.SHARP else Glyph.FLAT, nx - sp * 1.3f, y(p.step), c) }
                glyph(when { n.beats >= 4f -> Glyph.WHOLE; n.beats >= 2f -> Glyph.HALF; else -> Glyph.BLACK }, nx, y(p.step), c)
                if (n.beats == 1.5f || n.beats == 3f) {
                    val dy = if (p.onLine) y(p.step + 1) else y(p.step)
                    drawCircle(c, sp * 0.2f, Offset(nx + w + sp * 0.45f, dy))
                }
                if (n.beats >= 4f) continue
                // Two eighths in one beat share a beam; a lone eighth gets a flag.
                val next = melody.notes.getOrNull(i + 1)
                val pair = n.beats == 0.5f && next?.beats == 0.5f && i + 1 in idx && kotlin.math.floor(n.start) == kotlin.math.floor(next.start) && skipBeam != i
                if (i == skipBeam) continue
                if (pair) {
                    val q = melody.key.place(next!!.midi, clef)
                    val up = (p.step + q.step) / 2f < 4f
                    val x1 = if (up) nx + headW - stroke / 2 else nx + stroke / 2
                    val x2 = noteX(i + 1).let { if (up) it + headW - stroke / 2 else it + stroke / 2 }
                    val beamY = if (up) minOf(y(p.step), y(q.step)) - stemLen else maxOf(y(p.step), y(q.step)) + stemLen
                    drawLine(c, Offset(x1, y(p.step)), Offset(x1, beamY), stroke)
                    drawLine(color(i + 1).takeOrElse { ink }, Offset(x2, y(q.step)), Offset(x2, beamY), stroke)
                    drawLine(ink, Offset(x1, beamY + (if (up) sp * 0.25f else -sp * 0.25f)), Offset(x2, beamY + (if (up) sp * 0.25f else -sp * 0.25f)), sp * 0.5f)
                    skipBeam = i + 1
                    continue
                }
                val up = p.step < 4
                val sx = if (up) nx + headW - stroke / 2 else nx + stroke / 2
                val end = if (up) y(p.step) - stemLen else y(p.step) + stemLen
                drawLine(c, Offset(sx, y(p.step)), Offset(sx, end), stroke)
                if (n.beats == 0.5f) {
                    val d = if (up) 1f else -1f
                    val flag = Path().apply {
                        moveTo(sx, end)
                        quadraticTo(sx + sp * 0.2f, end + d * sp * 1.2f, sx + sp * 1.0f, end + d * sp * 2.2f)
                    }
                    drawPath(flag, c, style = Stroke(sp * 0.3f))
                }
            }
        }
    }
}
