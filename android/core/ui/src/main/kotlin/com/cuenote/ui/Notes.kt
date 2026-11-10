package com.cuenote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The pieces of the "Setlist" look: a teacher's note on a topic tint with a
 * folded corner, the play pills, staff lines under a headline, and the small
 * all-caps labels. Quotes are set in Fraunces italic and always say who said
 * them; handwriting is kept for the teacher's own sketches.
 */

/** Fraunces italic for a teacher's quoted words. */
@Composable
fun quoteStyle() = MaterialTheme.typography.titleMedium.copy(
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 22.sp,
)

/** A small all-caps label, like "REGISTERS" or "NOW · STUDENT". */
@Composable
fun Eyebrow(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
    )
}

/** A card shape with its top-right corner folded over, like a page of notes. */
private fun foldedShape(fold: Float, radius: Float) = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(radius, 0f)
    lineTo(w - fold, 0f)
    lineTo(w, fold)
    lineTo(w, h - radius)
    quadraticTo(w, h, w - radius, h)
    lineTo(radius, h)
    quadraticTo(0f, h, 0f, h - radius)
    lineTo(0f, radius)
    quadraticTo(0f, 0f, radius, 0f)
    close()
}

/**
 * A teacher's note: the topic, where it was, the quote, who said it, and
 * whatever the caller puts under it (play pills, a switch, a sketch).
 */
@Composable
fun NoteCard(
    topic: Topic?,
    topicLabel: String,
    where: String?,
    quote: String,
    teacherName: String,
    checkedByTeacher: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = topic.colors()
    val bg = MaterialTheme.colorScheme.background
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fold = with(density) { 16.dp.toPx() }
    val radius = with(density) { 16.dp.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(foldedShape(fold, radius))
            .background(c.container)
            .drawWithContent {
                drawContent()
                // the folded-over corner: a small triangle in the page colour with a soft shadow edge
                val p = Path().apply {
                    moveTo(size.width - fold, 0f); lineTo(size.width - fold, fold); lineTo(size.width, fold); close()
                }
                drawPath(p, Color.Black.copy(alpha = 0.10f))
                val p2 = Path().apply { moveTo(size.width - fold, 0f); lineTo(size.width, 0f); lineTo(size.width, fold); close() }
                drawPath(p2, bg)
            }
            .semantics(mergeDescendants = false) {}
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(topicLabel, c.content)
            if (where != null) {
                Text(" · $where", color = c.content.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("“$quote”", style = quoteStyle(), color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$teacherName said", color = c.content, style = MaterialTheme.typography.labelLarge)
            if (checkedByTeacher) {
                Spacer(Modifier.width(8.dp))
                Text("✓ checked", color = c.content.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            }
        }
        content()
    }
}

/**
 * A rounded play pill. [filled] is the main one (the teacher's words);
 * the other has an outlined circle. While [playing] it reads "Stop".
 */
@Composable
fun PlayPill(label: String, playing: Boolean, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val m = MaterialTheme.colorScheme
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = if (playing) "Stop" else "Play") { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(m.surfaceContainerLowest).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).then(
                    if (filled || playing) Modifier.background(PlumAction, CircleShape)
                    else Modifier.border(1.5.dp, m.onSurfaceVariant, CircleShape),
                ),
                contentAlignment = Alignment.Center,
            ) {
                val tint = if (filled || playing) Color.White else m.onSurfaceVariant
                if (playing) Box(Modifier.size(9.dp).background(tint, RoundedCornerShape(2.dp)))
                else PlayGlyph(tint, Modifier.size(10.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (playing) "Stop" else label, style = MaterialTheme.typography.labelLarge, color = m.onSurface)
        }
    }
}

@Composable
fun PlayGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }
        drawPath(p, color)
    }
}

/**
 * Five staff lines across the full width, with three note heads near the
 * right end: the decoration under a big headline. Drawn below the letters,
 * never through them.
 */
@Composable
fun StaffLines(modifier: Modifier = Modifier, notes: Boolean = true) {
    val line = Cue.extras.staff
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = "" }) {
        val gap = size.height / 5
        for (i in 0 until 5) drawLine(line, Offset(0f, gap * i + 1), Offset(size.width, gap * i + 1), strokeWidth = 1.dp.toPx())
        if (!notes) return@Canvas
        val head = Size(9.dp.toPx(), 6.5.dp.toPx())
        fun note(x: Float, y: Float, c: Color) = rotate(-20f, Offset(x + head.width / 2, y + head.height / 2)) {
            drawOval(c, Offset(x, y), head)
        }
        note(size.width - 30.dp.toPx(), gap * 0.6f, Brass)
        note(size.width - 48.dp.toPx(), gap * 2.6f, accent)
        note(size.width - 66.dp.toPx(), gap * 1.6f, accent)
    }
}

/** A headline in Fraunces with one word in plum italic, over staff lines. */
@Composable
fun Masthead(before: String, accent: String, after: String = "", staff: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(before)
                pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.primary))
                append(accent)
                pop()
                append(after)
            },
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 34.sp),
        )
        if (staff) {
            Spacer(Modifier.height(6.dp))
            StaffLines()
        }
    }
}

/** A small rounded tag in a topic's tint. */
@Composable
fun TopicChip(topic: Topic?, label: String, modifier: Modifier = Modifier) {
    val c = topic.colors()
    Text(
        label,
        modifier = modifier.clip(RoundedCornerShape(50)).background(c.container).padding(horizontal = 10.dp, vertical = 4.dp),
        color = c.content,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** The plum Start button with the brass record dot, the same in light and dark. */
@Composable
fun StartButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) PlumAction else PlumAction.copy(alpha = 0.45f))
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(12.dp).background(Brass, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** A plain row on a white page with an optional icon, used for steps and settings-like rows. */
@Composable
fun TicketRow(
    modifier: Modifier = Modifier,
    faded: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val m = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(m.surfaceContainerLowest)
            .border(1.dp, m.outlineVariant, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (faded) Modifier.drawWithContent { drawContent(); drawRect(m.background.copy(alpha = 0.4f)) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
