package com.cuenote.pipeline.cards

import com.cuenote.pipeline.Pipeline
import com.cuenote.pipeline.Speaker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * The card writer that runs on the phone, minus the model itself: what a
 * small model is asked for one feedback block, and the checks on what it
 * answers. The same prompt and checks run on the laptop when the models are
 * vetted (`vet` in Cli.kt), so what is vetted is what the phone runs.
 *
 * A small model is asked about one block at a time, not the whole lesson as
 * extract-cues does: a 3-4B model loses the thread over thirty blocks, and one
 * block fits in a short context, which keeps the phone's memory and heat down.
 * It sees the cues it already wrote in the same exercise, so it can reuse one
 * word for word when she comes back to a point.
 */

/** Something that turns a prompt into text: llama.cpp, Gemini Nano, or a server on the laptop. */
interface CardModel : AutoCloseable {
    /** A short name for the vetting report and the card's label, such as "qwen2.5-3b". */
    val name: String

    /** The model's answer. [schema] is the JSON schema the answer must follow, for engines that can enforce one. */
    fun complete(system: String, user: String, schema: String): String

    override fun close() {}
}

/** One block as the phone's card writer sees it. */
data class PhoneBlock(
    val localId: String,
    val exerciseId: String?,
    /** "a warmup", "singing the song", or null outside any exercise. */
    val exercise: String?,
    /** What the student sang on the take before, as heard; empty when none. */
    val sung: String,
    /** Every line said during the block, both speakers, in order. */
    val lines: List<Pair<Speaker, String>>,
    /** The teacher's words in the block, which her_words must come from. */
    val words: String,
    val demos: Int,
    val afterTake: Boolean,
)

/** A card the phone wrote, after the checks. */
@Serializable
data class PhoneCard(
    val localId: String,
    val cue: String,
    val herWords: String,
    val summary: String,
    val category: String?,
    val weight: Float?,
    /** Her quote wasn't her words and was replaced with her own sentence. */
    val repaired: Boolean = false,
    /** The cue was over 8 words and was cut. */
    val cueCut: Boolean = false,
    /** The first cue was over 8 words, so the model was asked again. */
    val retried: Boolean = false,
    /** The answer wasn't usable JSON: the card is her words only. */
    val unreadable: Boolean = false,
    val ms: Long = 0,
)

object PhoneCards {
    val CATEGORIES = listOf(
        "breath", "resonance", "vowel", "registration", "phrasing", "pitch", "diction",
        "posture", "warm-up", "encouragement", "chat",
    )

    /** Blocks with fewer of her words than this are a "yes" or "again": no card needed. */
    const val MIN_WORDS = 4

    val SYSTEM = """You help a singing student remember what their teacher said in a lesson. You get one feedback block: what the teacher (and sometimes the student) said after the student sang. The words come from speech recognition, so some are misheard.

Answer with JSON only:
- "cue": the teacher's main instruction as a short imperative in plain English, at most 8 words, such as "Land high notes from above, don't reach" or "Breathe low before the phrase". One action in the student's body or sound, using her idea and her images. Never add advice she didn't give. If several, keep the one she spends the most time on. If she gave no instruction (greetings, scheduling, small talk, thinking aloud), use "" and category "chat". Clear praise of the singing is "encouragement" with a cue like "Keep that open sound".
- "her_words": the one sentence of hers that best carries the cue, copied exactly from her lines. Fix nothing. Never paraphrase here.
- "summary": what she meant in one or two plain sentences, at most 40 words, to the student ("you"). Keep her images in her words, use no technical term she didn't use, never add or reverse an instruction. American spelling ("practice", never "practise"). "" for chat.
- "category": one of ${CATEGORIES.joinToString(", ")}.
- "weight": 0 to 1, how much this is a technique to drill at home. High when she explains or demonstrates at length, middle for a quick correction, near 0 for stories, music theory, choosing a key or song, and a quick "yes". 0 for chat.
If one of the earlier cues in this exercise is the same instruction, reuse it word for word."""

    const val SHORTER = "Your cue was longer than 8 words. Answer again with a cue of at most 8 words: one action, no explanation."

    /** The JSON schema of the answer, for engines that can hold a model to one (llama.cpp). */
    val SCHEMA = """{"type":"object","properties":{"cue":{"type":"string"},"her_words":{"type":"string"},"summary":{"type":"string"},""" +
        """"category":{"type":"string","enum":[${CATEGORIES.joinToString(",") { "\"$it\"" }}]},"weight":{"type":"number"}},""" +
        """"required":["cue","her_words","summary","category","weight"],"additionalProperties":false}"""

    /** The blocks of a processed lesson, as the card writer reads them. */
    fun blocks(out: Pipeline.Output): List<PhoneBlock> {
        val takes = out.takes.associateBy { it.id }
        val exercises = out.exercises.associateBy { it.id }
        return out.feedback.sortedBy { it.startMs }.map { fb ->
            val take = fb.takeId?.let(takes::get)
            val sung = take?.let { t ->
                out.transcript.filter { it.startMs < t.endMs && it.endMs > t.startMs && it.text.isNotBlank() }
                    .joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").take(200)
            }.orEmpty()
            val lines = out.transcript.filter { it.startMs < fb.endMs && it.endMs > fb.startMs && it.text.isNotBlank() }
                .map { it.speaker to it.text.trim() }
            PhoneBlock(
                localId = fb.id,
                exerciseId = fb.exerciseId,
                exercise = fb.exerciseId?.let(exercises::get)?.let { if (it.kind == "song") "singing the song" else "a warmup" },
                sung = sung,
                lines = lines,
                words = CueRequest.wordsIn(fb, out.transcript),
                demos = fb.demos.size,
                afterTake = take != null,
            )
        }
    }

    /** The user turn for one block, with the cues already written in its exercise. */
    fun prompt(b: PhoneBlock, earlierCues: List<String>): String = buildString {
        b.exercise?.let { appendLine("Exercise: $it.") }
        if (b.sung.isNotBlank()) appendLine("The student sang: “${b.sung}”")
        appendLine(if (b.afterTake) "Then the teacher said${if (b.demos > 0) " (and sang ${b.demos} demo${if (b.demos > 1) "s" else ""})" else ""}:" else "Talk not after any singing:")
        if (b.lines.isEmpty()) appendLine("Teacher: ${b.words}")
        for ((who, text) in b.lines) appendLine("${if (who == Speaker.TEACHER) "Teacher" else "Student"}: $text")
        if (earlierCues.isNotEmpty()) appendLine("Earlier cues in this exercise: ${earlierCues.joinToString("; ") { "“$it”" }}")
    }.trim()

    /** Whether a block is worth a call at all: one with almost none of her words gets no card. */
    fun worthWriting(b: PhoneBlock) = b.words.split(Regex("\\s+")).count { it.isNotBlank() } >= MIN_WORDS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The model's answer, checked as extract-cues checks the cloud's: her
     * words must be hers, a cue at most 8 words, chat has no cue, summary in
     * American spelling. An answer that isn't JSON leaves her words only.
     */
    fun check(b: PhoneBlock, answer: String, ms: Long = 0): PhoneCard {
        val obj = runCatching {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            json.parseToJsonElement(answer.substring(start, end + 1)) as JsonObject
        }.getOrNull()
        if (obj == null) {
            val words = CardChecks.verbatim("", b.words).text
            return PhoneCard(b.localId, "", words, "", null, null, unreadable = true, ms = ms)
        }
        fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val category = str("category").lowercase().takeIf { it in CATEGORIES }
        val chat = category == "chat"
        val rawCue = str("cue")
        val quote = CardChecks.verbatim(str("her_words"), b.words)
        val weight = obj["weight"]?.jsonPrimitive?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f)
        return PhoneCard(
            localId = b.localId,
            cue = if (chat) "" else CardChecks.cue(rawCue),
            herWords = quote.text,
            summary = if (chat) "" else CardChecks.summary(str("summary")),
            category = category,
            weight = if (chat) 0f else weight,
            repaired = quote.repaired,
            cueCut = !chat && CardChecks.cueTooLong(rawCue),
            ms = ms,
        )
    }

    /**
     * Writes the cards for a lesson, one block at a time. Blocks with almost
     * none of her words are skipped. [isStopped] lets the phone stop between
     * blocks (unplugged, too warm); [onBlock] reports each card as it's done.
     */
    fun write(
        model: CardModel,
        blocks: List<PhoneBlock>,
        isStopped: () -> Boolean = { false },
        onBlock: (done: Int, total: Int, card: PhoneCard) -> Unit = { _, _, _ -> },
    ): List<PhoneCard> {
        val todo = blocks.filter(::worthWriting)
        val cuesBy = mutableMapOf<String?, MutableList<String>>()
        val out = mutableListOf<PhoneCard>()
        for ((i, b) in todo.withIndex()) {
            if (isStopped()) break
            val earlier = cuesBy[b.exerciseId].orEmpty().distinct().takeLast(6)
            val t0 = System.currentTimeMillis()
            val user = prompt(b, earlier)
            val answer = runCatching { model.complete(SYSTEM, user, SCHEMA) }.getOrDefault("")
            var card = check(b, answer, System.currentTimeMillis() - t0)
            if (card.cueCut) {
                // Cut at the eighth word, a cue reads "Let the jaw drop and think of a": ask once more for a shorter one.
                val again = runCatching { model.complete(SYSTEM, "$user\n\n$SHORTER", SCHEMA) }.getOrDefault("")
                val second = check(b, again, System.currentTimeMillis() - t0)
                card = if (!second.cueCut && !second.unreadable) second.copy(cueCut = true, retried = true)
                // Still too long: her words carry the card, with no cue rather than half of one.
                else card.copy(cue = "", retried = true)
            }
            if (card.cue.isNotBlank() && b.exerciseId != null) cuesBy.getOrPut(b.exerciseId) { mutableListOf() } += card.cue
            out += card
            onBlock(i + 1, todo.size, card)
        }
        return out
    }
}
