package com.cuenote.record

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuenote.ui.Eyebrow
import com.cuenote.ui.Fraunces
import com.cuenote.ui.Topic
import com.cuenote.ui.colors
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * One tip from the student's teacher's tip card. [tip] is the teacher's own
 * wording, as printed on the card. [what] and [tryIt] are Cue Note's notes to
 * help remember it: not the teacher's words, and shown as such. [diagrams]
 * are ids from the diagram library (tools/diagrams/gen.py).
 */
internal data class TeacherTip(
    val tip: String,
    val topic: Topic,
    val what: String,
    val tryIt: String,
    val diagrams: List<String>,
)

/** The ten tips printed around the edge of the teacher's card, in the card's order. */
internal val TEACHER_TIPS = listOf(
    TeacherTip(
        "Jaw back", Topic.VOWELS,
        "Let the jaw drop down and back from its hinge, just in front of your ears, instead of pushing the chin forward. " +
            "A jaw that juts forward tends to tighten the tongue and throat; one that swings back leaves room for the sound.",
        "Rest a finger on each hinge and say \"ah\". You should feel a small gap open there, not the chin sliding out.",
        listOf("vowels-jaw"),
    ),
    TeacherTip(
        "High palate & low larynx", Topic.RESONANCE,
        "Two halves of one open space. The soft palate, at the back of the roof of your mouth, lifts the way it does at the start of a yawn, " +
            "and the voice box sits comfortably low instead of riding up with the pitch. Together they make the throat taller, " +
            "which usually gives a rounder, fuller tone.",
        "Begin a yawn, stop before it takes over, and sing from there. Don't press the larynx down; let it settle.",
        listOf("resonance-soft-palate", "larynx-height"),
    ),
    TeacherTip(
        "Right next door: U-O-A-E-I", Topic.VOWELS,
        "In this order, each vowel is only a small move from its neighbor: lips round for U, opening through O to A, " +
            "then the tongue rising a little for E and I. Moving between neighbors keeps the space in your throat the same, " +
            "so the tone stays even from vowel to vowel.",
        "Sing U-O-A-E-I slowly on one note, changing as little as you can between them.",
        listOf("vowels-next-door", "vowels-tongue"),
    ),
    TeacherTip(
        "More-less-least (low-middle-high)", Topic.REGISTERS,
        "More down low, less in the middle, least up high. A common reading is weight: a fuller, heavier sound at the bottom, " +
            "getting lighter as you climb so the top isn't forced. It can also mean breath pressure or volume, " +
            "so it's worth asking your teacher which they mean for you.",
        "Sing a slow five-note scale and let the sound get lighter on the way up.",
        listOf("registers-more-less-least", "larynx-thick-thin"),
    ),
    TeacherTip(
        "Stable sternum", Topic.POSTURE,
        "Keep the breastbone comfortably high and still, so the chest doesn't sink as the air goes out. " +
            "The breathing work happens lower down, in the belly, sides and back, while the chest stays steady. " +
            "Settled, not puffed up or rigid.",
        "Lay a hand flat on your breastbone and hiss out slowly. The hand should barely move.",
        listOf("posture-shoulders", "breath-support"),
    ),
    TeacherTip(
        "Long & steady inhale", Topic.BREATH,
        "Breathe in slowly and evenly rather than in a quick gasp. A slow in-breath gives the diaphragm time to lower " +
            "and the ribs time to widen without the shoulders lifting, and it usually leads to a calmer, steadier out-breath.",
        "Breathe in for a slow count of four through a relaxed, open mouth, then sing one long note.",
        listOf("breath-diaphragm", "breath-low-high"),
    ),
    TeacherTip(
        "Attack from above the pitch", Topic.ONSET,
        "Aim each note from slightly above, so it lands on the pitch instead of scooping up to it from below. " +
            "\"Attack\" here means the start of the note, not a hard hit. Thinking down onto the note helps the start stay clean and in tune, " +
            "especially on higher notes.",
        "Sing a note, then start it again, imagining it arriving from just over the top.",
        listOf("larynx-onset-above", "larynx-onset"),
    ),
    TeacherTip(
        "Send the breath down & out", Topic.BREATH,
        "While you sing, keep a feeling of the breath going down and out: the low belly, sides and back stay wide " +
            "instead of caving in. That gentle outward feeling lets the air leave slowly and evenly.",
        "Put your hands on your lower ribs and sing a long note. Keep them from caving in until near the end.",
        listOf("breath-support", "breath-low-high"),
    ),
    TeacherTip(
        "Inhale the vowel to be sung", Topic.BREATH,
        "Shape your mouth for the coming vowel while you breathe in, so the air passes through the shape the note will use. " +
            "The note then starts already in place, without a last-second shift of the jaw or tongue.",
        "Before a phrase that starts on \"oh\", breathe in through an \"oh\" shape, then sing.",
        listOf("breath-inhale-vowel"),
    ),
    TeacherTip(
        "Round mouth", Topic.VOWELS,
        "Keep the lips gently rounded and a little forward, rather than spread wide in a smile. " +
            "Rounding keeps the sound warm and connected, and stops the bright vowels, E and I, from going thin.",
        "Sing \"ee\" with your lips spread, then with them rounded a little toward \"oo\". Listen for the warmer one.",
        listOf("vowels-lips"),
    ),
)

/** A different tip each day, so the warm-up has one thing to keep in mind. */
internal fun tipFor(day: Calendar): Int = day.get(Calendar.DAY_OF_YEAR) % TEACHER_TIPS.size

/** The diagram library's titles and captions (bundled from tools/diagrams). */
private object TipDiagrams {
    @Volatile private var captions: Map<String, Pair<String, String>>? = null

    fun caption(context: Context, id: String): Pair<String, String>? {
        val all = captions ?: runCatching {
            context.assets.open("diagrams/index.json").bufferedReader().use { r ->
                val a = JSONArray(r.readText())
                (0 until a.length()).associate { i -> a.getJSONObject(i).run { getString("id") to (getString("title") to getString("shows")) } }
            }
        }.getOrDefault(emptyMap()).also { captions = it }
        return all[id]
    }

    // The PNGs ship in :feature:lesson's assets, which are merged into the app.
    fun image(context: Context, id: String): ImageBitmap? = runCatching {
        context.assets.open("diagrams/$id.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()
}

/**
 * One tip as a row that opens to show Cue Note's note on it and a diagram or
 * two. Today's tip (the one on the warm-up card) starts open.
 */
@Composable
internal fun TeacherTipRow(index: Int, today: Boolean) {
    val tip = TEACHER_TIPS[index]
    val c = tip.topic.colors()
    val m = MaterialTheme.colorScheme
    var open by rememberSaveable(index) { mutableStateOf(today) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (open) c.content.copy(alpha = 0.5f) else m.outlineVariant, RoundedCornerShape(16.dp))
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide the note" else "Read about this tip") { open = !open }
                .semantics { stateDescription = if (open) "Open" else "Closed" }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c.container), contentAlignment = Alignment.Center) {
                Text("${index + 1}", color = c.content, style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(tip.tip, fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, color = m.onSurface)
                Text(
                    if (today) "${tip.topic.label} · today's tip" else tip.topic.label,
                    color = if (today) c.content else m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = m.onSurfaceVariant)
        }
        if (open) TipBody(tip)
    }
}

@Composable
private fun TipBody(tip: TeacherTip) {
    val m = MaterialTheme.colorScheme
    val c = tip.topic.colors()
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("A note on this tip, by Cue Note", c.content)
        Text(tip.what, style = MaterialTheme.typography.bodyMedium, color = m.onSurface)
        Text(
            "Try it: ${tip.tryIt}",
            style = MaterialTheme.typography.bodyMedium, color = m.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.container).padding(10.dp).fillMaxWidth(),
        )
        tip.diagrams.forEach { TipDiagram(it) }
    }
}

@Composable
private fun TipDiagram(id: String) {
    val context = LocalContext.current
    val caption = remember(id) { TipDiagrams.caption(context, id) }
    val image by produceState<ImageBitmap?>(null, id) { value = withContext(Dispatchers.IO) { TipDiagrams.image(context, id) } }
    val bitmap = image ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Image(
            bitmap,
            contentDescription = caption?.let { "${it.first}: ${it.second}" },
            modifier = Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)).background(Color.White),
        )
        caption?.let { Text(it.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Under the list: whose tips these are, and whose notes. */
@Composable
internal fun TeacherTipsFooter() {
    Text(
        "The tips are your teacher's, from their card. The notes under them are Cue Note's, to help you remember. " +
            "If a note and your teacher disagree, go with your teacher.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
    )
}
