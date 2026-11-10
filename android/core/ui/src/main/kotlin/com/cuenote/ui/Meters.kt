package com.cuenote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuenote.signal.LevelFollower
import com.cuenote.signal.VoiceGate

/** What a mic's row says about it. */
enum class VoiceState { QUIET, HEARING, SHOULD_BE_QUIET }

/**
 * One labelled level bar: "Student ▮▮▮▮▯▯ Hearing you". The bar is drawn
 * from [db] every frame (smoothed: quick to rise, slow to fall) without
 * recomposing the row; the words change only when the steady [VoiceGate]
 * does, and are what TalkBack reads.
 *
 * [expectQuiet] marks the person who should be silent in this step: if
 * their mic picks someone up, the row says so.
 */
@Composable
fun LevelRow(
    label: String,
    db: () -> Double?,
    modifier: Modifier = Modifier,
    expectQuiet: Boolean = false,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    fill: Color = MaterialTheme.colorScheme.primary,
    text: Color = MaterialTheme.colorScheme.onSurface,
    muted: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accent: Color = MaterialTheme.colorScheme.primary,
    warn: Color = MaterialTheme.colorScheme.error,
    barHeight: Dp = 16.dp,
) {
    val reduced = rememberReducedMotion()
    val read by rememberUpdatedState(db)
    var level by remember { mutableFloatStateOf(0f) }
    var hearing by remember { mutableStateOf(false) }
    LaunchedEffect(reduced) {
        val follower = LevelFollower()
        val gate = VoiceGate()
        var last = withFrameMillis { it }
        var lastDraw = 0L
        while (true) {
            val now = withFrameMillis { it }
            val d = read()
            hearing = gate.update(d, now)
            val target = LevelFollower.fraction(d)
            if (reduced) {
                // No smoothing, and only ten updates a second.
                if (now - lastDraw >= 100) { level = target.toFloat(); lastDraw = now }
            } else {
                level = follower.step(target, (now - last).toDouble()).toFloat()
            }
            last = now
        }
    }
    val state = when {
        hearing && expectQuiet -> VoiceState.SHOULD_BE_QUIET
        hearing -> VoiceState.HEARING
        else -> VoiceState.QUIET
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
        Box(
            Modifier.weight(1f).height(barHeight).drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val w = (size.width * level).coerceAtLeast(size.height * 0.6f)
                drawRoundRect(if (state == VoiceState.QUIET) muted.copy(alpha = 0.5f) else fill, size = Size(w, size.height), cornerRadius = r)
            },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            when (state) {
                VoiceState.QUIET -> "Quiet"
                VoiceState.HEARING -> "Hearing you"
                VoiceState.SHOULD_BE_QUIET -> "Should be quiet"
            },
            color = when (state) {
                VoiceState.QUIET -> muted
                VoiceState.HEARING -> accent
                VoiceState.SHOULD_BE_QUIET -> warn
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(92.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * A rubber stamp: a word in a tilted box. When [landing] it lands once
 * (scale 1.15 to 1, 120 ms, no bounce); with reduced motion it just appears.
 */
@Composable
fun Stamp(text: String, color: Color, modifier: Modifier = Modifier, landing: Boolean = false, dashed: Boolean = false) {
    val reduced = rememberReducedMotion()
    val scale = remember { Animatable(if (landing && !reduced) 1.15f else 1f) }
    val alpha = remember { Animatable(if (landing && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (landing && !reduced) {
            coroutineScope {
                launch { scale.animateTo(1f, tween(120)) }
                launch { alpha.animateTo(1f, tween(120)) }
            }
        }
    }
    Text(
        text,
        modifier = modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
            .rotate(if (dashed) 0f else -10f)
            .border(2.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
    )
}
