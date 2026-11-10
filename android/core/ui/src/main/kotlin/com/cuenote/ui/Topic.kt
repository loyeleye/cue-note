package com.cuenote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The topics a singing teacher works on, in the vocal coach's list. A card
 * may touch two at once (vowel shaping is often how a register break gets
 * fixed), so these are labels, never scores.
 *
 * Cards carry a free-text category today; [Topic.of] reads it into this list
 * until the dashboard lets the teacher pick from it directly.
 */
enum class Topic(val label: String, private val words: List<String>) {
    BREATH("Breath and support", listOf("breath", "support", "appoggio", "air", "rib", "diaphragm", "inhal", "exhal")),
    ONSET("Onset and phonation", listOf("onset", "phonation", "glottal", "attack", "fold", "breathy", "pressed")),
    REGISTERS("Registers", listOf("register", "registr", "passaggio", "break", "chest", "head voice", "mix", "falsetto", "flip")),
    RESONANCE("Resonance", listOf("resonance", "resonat", "placement", "mask", "forward", "palate", "space", "twang", "ring")),
    VOWELS("Vowels", listOf("vowel", "jaw", "tongue", "lips", "modif")),
    DICTION("Diction and text", listOf("diction", "consonant", "text", "word", "language", "italian", "pronunc")),
    POSTURE("Posture and alignment", listOf("posture", "alignment", "shoulder", "neck", "chin", "stance", "feet", "head position")),
    MUSICALITY("Musicality and style", listOf("musical", "phras", "legato", "dynamic", "style", "line", "tempo", "rhythm", "pitch", "intonation", "interpret")),
    ;

    companion object {
        /** The topic a free-text category belongs to, or null for chat and anything unknown. */
        fun of(category: String?): Topic? {
            val c = category?.trim()?.lowercase().orEmpty()
            if (c.isEmpty() || c == "chat") return null
            entries.firstOrNull { it.name.equals(c, ignoreCase = true) || it.label.equals(c, ignoreCase = true) }?.let { return it }
            return entries.firstOrNull { t -> t.words.any { c.contains(it) } }
        }
    }
}

/** A topic's tint and its text colour. Four brand tints, shared two topics each. */
data class TopicColors(val container: Color, val content: Color)

@Composable
fun Topic?.colors(): TopicColors {
    val x = Cue.extras
    val m = MaterialTheme.colorScheme
    return when (this) {
        Topic.BREATH, Topic.POSTURE -> TopicColors(x.slate, x.onSlate)
        Topic.REGISTERS, Topic.MUSICALITY -> TopicColors(m.tertiaryContainer, m.onTertiaryContainer)
        Topic.RESONANCE, Topic.ONSET -> TopicColors(m.primaryContainer, m.onPrimaryContainer)
        Topic.VOWELS, Topic.DICTION -> TopicColors(x.warm, x.onWarm)
        null -> TopicColors(m.surfaceVariant, m.onSurfaceVariant)
    }
}
