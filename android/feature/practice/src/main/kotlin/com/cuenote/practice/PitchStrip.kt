package com.cuenote.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cuenote.pipeline.practice.NoteResult.Verdict
import com.cuenote.pipeline.practice.PitchSummary
import com.cuenote.pipeline.practice.TakePitch
import com.cuenote.ui.Brass

/**
 * A take's pitch as notes on a strip: each sung note a bar at its height,
 * the demo's note behind it as a dashed outline, notes a little off marked
 * in brass with a small arrow, and unclear notes drawn hollow. No numbers.
 */
@Composable
fun PitchStrip(p: TakePitch, modifier: Modifier = Modifier) {
    val m = MaterialTheme.colorScheme
    val summary = PitchSummary.of(p)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p.notes.isNotEmpty()) {
            val all = p.notes.flatMap { listOfNotNull(it.sung, it.target) }
            val lo = (all.minOrNull() ?: 60f) - 1.5f
            val hi = (all.maxOrNull() ?: 60f) + 1.5f
            val frames = maxOf(1, p.take.size)
            Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = summary }) {
                val h = size.height
                fun y(semi: Float) = h - (semi - lo) / (hi - lo) * h
                val bar = 5.dp.toPx()
                val r = CornerRadius(bar / 2)
                for (n in p.notes) {
                    val x0 = n.startFrame.toFloat() / frames * size.width
                    val w = maxOf(bar, (n.endFrame - n.startFrame).toFloat() / frames * size.width - 2.dp.toPx())
                    n.target?.let { t ->
                        drawRoundRect(m.outline, Offset(x0, y(t) - bar / 2), Size(w, bar), r,
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                    }
                    val color = when (n.verdict) {
                        Verdict.FLAT, Verdict.SHARP -> Brass
                        Verdict.UNCLEAR -> m.onSurfaceVariant
                        else -> m.primary
                    }
                    val top = Offset(x0, y(n.sung) - bar / 2)
                    if (n.verdict == Verdict.UNCLEAR) drawRoundRect(color, top, Size(w, bar), r, style = Stroke(1.5.dp.toPx()))
                    else drawRoundRect(color, top, Size(w, bar), r)
                    if (n.verdict == Verdict.FLAT || n.verdict == Verdict.SHARP) {
                        // a small arrow pointing the way to go
                        val cx = x0 + w / 2
                        val up = n.verdict == Verdict.FLAT
                        val tip = if (up) y(n.sung) - bar - 7.dp.toPx() else y(n.sung) + bar + 7.dp.toPx()
                        val base = if (up) tip + 5.dp.toPx() else tip - 5.dp.toPx()
                        drawLine(Brass, Offset(cx - 4.dp.toPx(), base), Offset(cx, tip), 1.5.dp.toPx())
                        drawLine(Brass, Offset(cx + 4.dp.toPx(), base), Offset(cx, tip), 1.5.dp.toPx())
                    }
                }
            }
            if (p.demoAligned != null || p.notes.any { it.verdict == Verdict.UNCLEAR }) Legend(p.demoAligned != null)
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = m.onSurfaceVariant)
    }
}

@Composable
private fun Legend(demo: Boolean) {
    val m = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Key({ drawRoundRect(m.primary, cornerRadius = CornerRadius(4f)) }, "You")
        if (demo) Key({ drawRoundRect(m.outline, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))) }, "Their demo")
        Key({ drawRoundRect(Brass, cornerRadius = CornerRadius(4f)) }, "A little off")
        Key({ drawRoundRect(m.onSurfaceVariant, style = Stroke(2f), cornerRadius = CornerRadius(4f)) }, "Unclear")
    }
}

@Composable
private fun Key(draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 6.dp), onDraw = draw)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
