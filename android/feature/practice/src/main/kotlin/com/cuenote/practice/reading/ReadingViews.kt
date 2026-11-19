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
) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val label = midi?.let { "A note ${Staff.describe(Staff.place(it, clef))}" } ?: "An empty staff"
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
        if (midi == null) return@Canvas
        val p = Staff.place(midi, clef)
        val cx = size.width * 0.58f
        val headW = sp * 1.7f
        for (l in p.ledgers) drawLine(lineColor, Offset(cx - headW * 0.85f, y(l)), Offset(cx + headW * 0.85f, y(l)), stroke * 1.3f)
        p.accidental?.let { glyph(if (it == Accidental.SHARP) Glyph.SHARP else Glyph.FLAT, cx - headW * 1.6f, y(p.step), noteColor) }
        glyph(Glyph.WHOLE, cx - headW / 2, y(p.step), noteColor)
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
