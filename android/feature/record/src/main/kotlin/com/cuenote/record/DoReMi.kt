package com.cuenote.record

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuenote.ui.Fraunces
import com.cuenote.ui.rememberReducedMotion

/**
 * "Do · Re · Mi", big enough to read from the piano bench. Each syllable
 * fills with the singer's colour left to right the moment it's heard, and a
 * short bar drops onto the baseline under it, so a lit syllable is marked by
 * more than colour. A light haptic tick goes with each one: no sound, which
 * the mics would pick up. Reduced motion: the fill and bar just appear.
 */
@Composable
internal fun DoReMi(heard: Int, color: Color, ink: Color, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val ticked = remember { intArrayOf(heard) }
    LaunchedEffect(heard) {
        if (heard > ticked[0]) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        ticked[0] = heard
    }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = when (heard) {
                0 -> "Do, Re, Mi"
                Solfege.SYLLABLES.size -> "Heard Do, Re, Mi"
                else -> "Heard " + Solfege.SYLLABLES.take(heard).joinToString()
            }
        },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Solfege.SYLLABLES.forEachIndexed { i, syllable ->
            if (i > 0) Text("·", color = ink.copy(alpha = 0.35f), fontFamily = Fraunces, fontSize = 40.sp)
            Syllable(syllable, lit = i < heard, color, ink)
        }
    }
}

@Composable
private fun Syllable(text: String, lit: Boolean, color: Color, ink: Color) {
    val reduced = rememberReducedMotion()
    val fill = remember { Animatable(if (lit) 1f else 0f) }
    val drop = remember { Animatable(if (lit) 0f else 1f) }
    LaunchedEffect(lit, reduced) {
        val target = if (lit) 1f else 0f
        if (reduced || !lit) {
            fill.snapTo(target); drop.snapTo(1f - target)
        } else {
            // The wipe and the landing start together, on the onset.
            coroutineScope {
                launch { fill.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
                launch { drop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }
    val lift = with(LocalDensity.current) { 14.dp.toPx() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Text(text, color = ink.copy(alpha = 0.35f), fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 60.sp, lineHeight = 64.sp)
            Text(
                text,
                Modifier.drawWithContent {
                    clipRect(right = size.width * fill.value) { this@drawWithContent.drawContent() }
                },
                color = color,
                fontFamily = Fraunces,
                fontWeight = FontWeight.SemiBold,
                fontSize = 60.sp,
                lineHeight = 64.sp,
            )
        }
        Box(
            Modifier
                .width(36.dp)
                .height(5.dp)
                .graphicsLayer {
                    translationY = -lift * drop.value
                    alpha = 1f - drop.value.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

internal object Solfege {
    val SYLLABLES = listOf("Do", "Re", "Mi")
}
