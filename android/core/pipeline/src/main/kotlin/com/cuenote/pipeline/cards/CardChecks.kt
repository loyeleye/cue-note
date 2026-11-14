package com.cuenote.pipeline.cards

/**
 * The checks extract-cues makes on the cloud model's answer
 * (supabase/functions/extract-cues/cues.ts), for a card written on the
 * phone: her words must really be hers, a cue is a short instruction, and the
 * summary is one or two sentences in American spelling. A small model breaks
 * these rules more often than a large one, so they matter more here.
 */
object CardChecks {
    /** A cue is at most this many words. */
    const val MAX_CUE_WORDS = 8

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}']+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun sentences(text: String) = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    data class Quote(val text: String, val repaired: Boolean)

    /**
     * Her words must be hers. When the model's quote isn't in the block's
     * transcript, the sentence of hers that shares the most words with it
     * takes its place.
     */
    fun verbatim(quote: String, transcript: String): Quote {
        val q = norm(quote)
        if (transcript.isBlank()) return Quote("", quote.isNotBlank())
        if (q.isNotEmpty() && norm(transcript).contains(q)) return Quote(quote.trim(), false)
        val want = q.split(' ').filter { it.length > 2 }.toSet()
        var best = ""
        var bestScore = -1
        for (s in sentences(transcript)) {
            val score = norm(s).split(' ').count { it in want }
            if (score > bestScore) { best = s; bestScore = score }
        }
        return Quote(best.ifEmpty { transcript }.take(400).trim(), true)
    }

    /**
     * A cue of at most [MAX_CUE_WORDS] words on one line, without quotes or a
     * full stop. A longer one is cut at the word limit, and [cueTooLong]
     * says so for the vetting report.
     */
    fun cue(text: String): String {
        val words = text.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”', '\'').trim().removeSuffix(".").split(' ').filter { it.isNotEmpty() }
        return words.take(MAX_CUE_WORDS).joinToString(" ").trimEnd(',', ';', ':', '-', '—')
    }

    fun cueTooLong(text: String) = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } > MAX_CUE_WORDS

    /** One or two sentences, on one line, cut at a sentence end near 300 characters; "practise" becomes "practice". */
    fun summary(text: String): String {
        val s = text.replace(Regex("\\s+"), " ").trim().replace(Regex("\\b([Pp])ractis(e|ed|es|ing)\\b"), "$1ractic$2")
        if (s.length <= 300) return s
        val cut = s.take(300)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        return if (end > 80) cut.take(end + 1) else cut.replace(Regex("\\s+\\S*$"), "") + "…"
    }

    /** The example cues in the phone's prompt, which a small model sometimes copies onto a block that says nothing like them. */
    val PROMPT_EXAMPLES = listOf("Land high notes from above, don't reach", "Breathe low before the phrase", "Keep that open sound")

    private val GENERIC = setOf(
        "the", "a", "an", "and", "or", "to", "of", "on", "in", "at", "your", "you", "it", "that", "this", "for", "with", "from",
        "don't", "do", "not", "no", "is", "be", "like", "sing", "feel", "make", "let", "keep", "find", "try", "use", "more", "less",
        "just", "then", "before", "after", "into", "up", "down", "again", "it's", "as", "so",
    )

    private fun specific(s: String) = norm(s).split(' ').filter { it.length > 1 && it !in GENERIC }

    /**
     * Whether the cue comes from what was said: at least one of its own words
     * (not "sing", "feel" and the like) is said in the block, and a cue copied
     * from the prompt's examples needs half of them. A word counts when it
     * starts the same as a word said ("vibrate" for "vibrating"). A cue with
     * no words of its own ("Sing it again") passes.
     */
    fun grounded(cue: String, said: String): Boolean {
        val words = specific(cue)
        if (words.isEmpty()) return true
        val heard = norm(said).split(' ').filter { it.length > 1 }
        fun stem(w: String) = w.take(maxOf(4, minOf(w.length, w.length - 2)))
        val found = words.count { w -> heard.any { h -> h.startsWith(stem(w)) || w.startsWith(stem(h)) && h.length >= 4 } }
        val example = PROMPT_EXAMPLES.any { norm(it) == norm(cue) }
        return if (example) found * 2 >= words.size else found >= 1
    }
}
