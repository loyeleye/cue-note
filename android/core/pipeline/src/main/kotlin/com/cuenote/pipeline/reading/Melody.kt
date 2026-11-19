package com.cuenote.pipeline.reading

import com.cuenote.pipeline.practice.StartingNote
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Stage 4: short melodies. A few bars to sing from just the first note, the
 * same bars to tap in time, and a song you already know for each leap.
 */

/** The keys Stage 4 uses: C, and the two with one sharp or one flat. */
enum class Key(val tonic: Int, val signature: Accidental?, val title: String, val words: String) {
    C(0, null, "C major", "No sharps or flats."),
    G(7, Accidental.SHARP, "G major", "One sharp at the start of each line: every F is F sharp."),
    F(5, Accidental.FLAT, "F major", "One flat at the start of each line: every B is B flat.");

    /** Where the key signature's sharp or flat sits on the staff in [clef] (F for the sharp, B for the flat). */
    fun signatureStep(clef: Clef): Int? {
        val letter = when (signature) { Accidental.SHARP -> 3; Accidental.FLAT -> 6; null -> return null }
        return (8 downTo 0).first { Math.floorMod(it + clef.bottomLine, 7) == letter }
    }

    /** Where [midi] sits in [clef], with the accidental left off when the key signature already says it. */
    fun place(midi: Int, clef: Clef): Placed {
        val p = Staff.place(midi, clef, flats = signature == Accidental.FLAT)
        return if (p.accidental != null && p.accidental == signature) p.copy(accidental = null) else p
    }

    /** The note for scale step [degree] (0 is do, 7 the do above, -1 the ti below) above [doMidi]. */
    fun note(doMidi: Int, degree: Int): Int = doMidi + 12 * Math.floorDiv(degree, 7) + MAJOR[Math.floorMod(degree, 7)]

    companion object {
        private val MAJOR = listOf(0, 2, 4, 5, 7, 9, 11)
    }
}

/** A short tune as written: scale steps from do, and how many beats each lasts. */
data class Tune(val id: String, val title: String, val beatsPerBar: Int, val notes: List<Pair<Int, Float>>) {
    val lowest: Int get() = notes.minOf { it.first }
    val highest: Int get() = notes.maxOf { it.first }
}

/** One written note of a placed melody: its pitch, length and where it starts, in beats. */
data class MelodyNote(val midi: Int, val beats: Float, val start: Float) {
    val end: Float get() = start + beats
}

/** A tune placed in the student's voice: a key, and real notes. */
data class Melody(val tune: Tune, val key: Key, val doMidi: Int, val notes: List<MelodyNote>) {
    val beatsPerBar: Int get() = tune.beatsPerBar
    val totalBeats: Float get() = notes.last().end
    val bars: Int get() = ceil(totalBeats / beatsPerBar).toInt()
    /** The bar each note falls in, from 0. */
    fun barOf(i: Int): Int = (notes[i].start / beatsPerBar + 1e-4f).toInt()
}

object Melodies {
    // Before TUNES, which reads them as it's built.
    private val TOKEN = Regex("([1-7])([,']*)([eqQhHw])")
    private val LENGTHS = mapOf('e' to 0.5f, 'q' to 1f, 'Q' to 1.5f, 'h' to 2f, 'H' to 3f, 'w' to 4f)

    /**
     * A small library, easiest first: steps, then skips, then the leaps of a
     * fourth and a fifth, with eighth notes and dotted notes along the way.
     * Written here for the app, in a plain folk style.
     *
     * Each note is a scale step (1 is do, 5 is sol; a comma puts it an
     * octave lower, so 5, is the sol below do) and a length: e an eighth,
     * q a quarter, Q a dotted quarter, h a half, H a dotted half, w a whole.
     */
    val TUNES: List<Tune> = listOf(
        tune("walk", "Walking up", 4, "1q 2q 3q 4q | 5h 4q 3q | 2q 3q 2q 1q | 1w"),
        tune("home", "Coming home", 4, "3q 2q 1h | 2q 3q 4h | 3q 2q 3q 2q | 1w"),
        tune("rock", "Rocking", 3, "1h 2q | 3h 4q | 3q 2q 2q | 1H"),
        tune("hop", "Hop and back", 4, "1q 3q 5h | 4q 2q 4h | 3q 1q 3q 2q | 1w"),
        tune("bells", "Little bells", 4, "5q 3q 5q 3q | 4q 2q 4h | 3q 2q 1q 3q | 2h 1h"),
        tune("below", "From below", 4, "5,q 1q 2q 3q | 2h 1h | 5,q 1q 3q 2q | 1w"),
        tune("quick", "Quick steps", 4, "1e 2e 3q 3q 3q | 2e 3e 4q 4h | 3e 4e 5q 3q 1q | 2q 2q 1h"),
        tune("fifth", "Up a fifth", 4, "1q 5q 4q 3q | 2q 4q 3h | 1q 5q 6q 5q | 3q 2q 1h"),
        tune("dotted", "Long and short", 3, "1Q 2e 3q | 5h 3q | 4Q 3e 2q | 1H"),
        tune("waltz", "Slow waltz", 3, "5,h 1q | 3h 2q | 1q 7,q 2q | 1H"),
        tune("la", "Up to la", 4, "1q 3q 5q 6q | 5h 3h | 4q 6q 5q 4q | 3q 2q 1h"),
        tune("evening", "Evening", 4, "5,q 1Q 2e 3q | 4h 3q 2q | 3q 5,q 1Q 2e | 1w"),
    )

    fun byId(id: String): Tune? = TUNES.firstOrNull { it.id == id }

    /** Reads a tune written as above; bars must add up. */
    fun tune(id: String, title: String, beatsPerBar: Int, text: String): Tune {
        val notes = mutableListOf<Pair<Int, Float>>()
        for ((b, bar) in text.split("|").withIndex()) {
            var sum = 0f
            for (tok in bar.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val m = requireNotNull(TOKEN.matchEntire(tok)) { "Bad note \"$tok\" in $id" }
                val (deg, oct, len) = m.destructured
                val degree = deg.toInt() - 1 + oct.count { it == '\'' } * 7 - oct.count { it == ',' } * 7
                val beats = LENGTHS.getValue(len[0])
                notes += degree to beats
                sum += beats
            }
            require(abs(sum - beatsPerBar) < 1e-4) { "Bar ${b + 1} of $id has $sum beats" }
        }
        return Tune(id, title, beatsPerBar, notes)
    }


    /**
     * Puts [tune] where the voice is comfortable: in C, G or F, whichever
     * keeps every note nearest the student's easy range (a little room
     * below it, never above), C first when it fits.
     */
    fun place(tune: Tune, home: VoiceHome): Melody {
        val lo = home.speaking - 3
        val hi = home.speaking + 9
        val mid = (lo + hi) / 2.0
        val best = Key.entries.flatMap { key ->
            (24..84).filter { Math.floorMod(it - key.tonic, 12) == 0 }.map { key to it }
        }.minWith(compareBy<Pair<Key, Int>>(
            { (k, d) -> tune.notes.sumOf { (deg, _) -> val n = k.note(d, deg); maxOf(0, lo - n, n - hi) } },
            { (k, _) -> k.ordinal },
            { (k, d) -> abs((k.note(d, tune.lowest) + k.note(d, tune.highest)) / 2.0 - mid) },
        ))
        val (key, doMidi) = best
        var t = 0f
        val notes = tune.notes.map { (deg, beats) -> MelodyNote(key.note(doMidi, deg), beats, t).also { t += beats } }
        return Melody(tune, key, doMidi, notes)
    }
}

/**
 * Sight-singing in free time: the voice moves through the melody note by
 * note, each one counting once it's held in tune for a moment (longer notes
 * a little longer). A note the student needed played, or skipped, still
 * moves on but counts as helped.
 */
class MelodyFollow(val melody: Melody) {
    enum class Mark { CLEAN, HELPED }

    val marks: MutableList<Mark?> = MutableList(melody.notes.size) { null }
    var index: Int = 0
        private set
    private var helped = false
    private var match = matchFor(0)

    val done: Boolean get() = index >= melody.notes.size
    val current: MelodyNote? get() = melody.notes.getOrNull(index)

    /** Most of it in tune with no help: the Stage 4 goal for a melody. */
    val clean: Boolean get() = done && marks.count { it == Mark.CLEAN } >= ceil(CLEAN_SHARE * marks.size).toInt()

    fun push(f: LivePitch.Frame): NoteMatch.State? {
        if (done) return null
        val s = match.push(f)
        if (s.settled) advance(if (helped) Mark.HELPED else Mark.CLEAN)
        return s
    }

    /** The student asked to hear this note. */
    fun helpWith() { helped = true }

    fun skip() { if (!done) advance(Mark.HELPED) }

    private fun advance(mark: Mark) {
        marks[index] = mark
        index++
        helped = false
        if (!done) match = matchFor(index)
    }

    private fun matchFor(i: Int): NoteMatch {
        val n = melody.notes[i]
        return NoteMatch(n.midi, holdFrames = (n.beats * FRAMES_PER_BEAT).roundToInt().coerceIn(MIN_HOLD, MAX_HOLD))
    }

    companion object {
        const val CLEAN_SHARE = 0.75
        /** A quarter note is held about a quarter of a second to count. */
        const val FRAMES_PER_BEAT = 8
        const val MIN_HOLD = 6
        const val MAX_HOLD = 24
    }
}

/** Tapping a rhythm against a steady click, judged gently. */
object RhythmCheck {
    enum class Mark { ON_TIME, EARLY, LATE, MISSED }

    data class Result(val marks: List<Mark>, val extra: Int) {
        /** Every note tapped on time, or all but one, with no more than one stray tap. */
        val clean: Boolean get() = marks.count { it != Mark.ON_TIME } <= 1 && extra <= 1
        val words: String get() {
            val early = marks.count { it == Mark.EARLY }
            val late = marks.count { it == Mark.LATE }
            val missed = marks.count { it == Mark.MISSED }
            return when {
                marks.all { it == Mark.ON_TIME } && extra == 0 -> "Right with the click, all the way through."
                clean -> "Nearly all of it right with the click."
                missed > marks.size / 2 -> "Lots of notes went by without a tap. Try tapping along as it plays first."
                early > late -> "A few taps came early. Let the long notes last their full count."
                late > early -> "A few taps came late. Feel the beat ahead of you, and tap with it."
                else -> "Some taps drifted from the click. Count the beats out loud as you go."
            }
        }
    }

    /**
     * Matches [taps] to the notes' [onsets] (both in beats from the first
     * note). A tap within [tolerance] of its note is on time; up to [window]
     * away it's early or late; a note with no tap near it is missed.
     */
    fun judge(onsets: List<Float>, taps: List<Float>, tolerance: Float = 0.25f, window: Float = 0.5f): Result {
        val used = BooleanArray(taps.size)
        val marks = onsets.map { o ->
            val i = taps.indices.filter { !used[it] && abs(taps[it] - o) <= window }.minByOrNull { abs(taps[it] - o) }
            if (i == null) Mark.MISSED else {
                used[i] = true
                val d = taps[i] - o
                when {
                    abs(d) <= tolerance -> Mark.ON_TIME
                    d < 0 -> Mark.EARLY
                    else -> Mark.LATE
                }
            }
        }
        return Result(marks, used.count { !it })
    }
}

/** Sound for a melody: the notes in time, with an optional click, as one buffer so the timing is exact. */
object MelodySound {
    const val BPM = 72
    fun beatMs(bpm: Int = BPM): Double = 60_000.0 / bpm

    /**
     * [countIn] beats of click first, then the melody (when [notes]) and,
     * when [click], a click on every beat through to the end.
     */
    fun render(melody: Melody, notes: Boolean, click: Boolean, countIn: Int, bpm: Int = BPM, sampleRate: Int = 48_000): ShortArray {
        val beat = beatMs(bpm) * sampleRate / 1000
        val total = ((countIn + melody.totalBeats) * beat + sampleRate * 0.6).toInt()
        val mix = FloatArray(total)
        if (click) {
            val beats = countIn + ceil(melody.totalBeats).toInt()
            for (b in 0 until beats) {
                val accent = (b - countIn).let { if (it < 0) it == -countIn else Math.floorMod(it, melody.beatsPerBar) == 0 }
                addClick(mix, (b * beat).toInt(), if (accent) 2_093.0 else 1_568.0, sampleRate)
            }
        }
        if (notes) for (n in melody.notes) {
            val ms = (n.beats * beatMs(bpm) * 0.95).toInt().coerceAtLeast(120)
            val tone = StartingNote.tone(StartingNote.Note(n.midi), ms, sampleRate)
            val at = ((countIn + n.start) * beat).toInt()
            for (i in tone.indices) if (at + i < total) mix[at + i] += tone[i].toFloat()
        }
        return ShortArray(total) { mix[it].roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun addClick(mix: FloatArray, at: Int, hz: Double, sampleRate: Int) {
        val n = sampleRate * 30 / 1000
        for (i in 0 until n) {
            if (at + i >= mix.size) return
            val t = i.toDouble() / sampleRate
            mix[at + i] += (0.3 * Short.MAX_VALUE * exp(-t * 120) * sin(2 * PI * hz * t)).toFloat()
        }
    }
}

/**
 * A leap you already know: the opening of a familiar song that moves by
 * that interval, so a written leap can be heard in the head before it's
 * sung. The words are the syllables on the two notes.
 */
@Serializable
enum class Anchor(val semis: Int, val interval: String, val song: String, val words: String) {
    THIRD_UP(4, "A skip up (a third)", "When the Saints Go Marching In", "\"Oh, when\""),
    FOURTH_UP(5, "A fourth up", "Shenandoah", "\"Oh, Shen-\""),
    FIFTH_UP(7, "A fifth up", "Twinkle, Twinkle, Little Star", "\"Twinkle, twin-\""),
    SIXTH_UP(9, "A sixth up", "My Bonnie Lies Over the Ocean", "\"My Bon-\""),
    OCTAVE_UP(12, "An octave up", "Over the Rainbow", "\"Some-where\""),
    THIRD_DOWN(-4, "A skip down (a third)", "Swing Low, Sweet Chariot", "\"Swing low\"");

    /** The two notes, starting from [low] for a leap up (or landing on it for a leap down). */
    fun notes(low: Int): Pair<Int, Int> = if (semis > 0) low to low + semis else low - semis to low
}

/** Stage 4 progress: melodies sung mostly in tune, rhythms tapped on time, and anchors heard right. */
@Serializable
data class MelodyProgress(
    /** Times each tune (by id) was sung with most notes in tune and no help. */
    val sung: Map<String, Int> = emptyMap(),
    /** Times each tune's rhythm was tapped on time. */
    val tapped: Map<String, Int> = emptyMap(),
    /** Right answers in the leap anchors quiz. */
    val anchors: Map<Anchor, Int> = emptyMap(),
) {
    fun sangClean(id: String) = copy(sung = sung + (id to (sung[id] ?: 0) + 1))
    fun tappedClean(id: String) = copy(tapped = tapped + (id to (tapped[id] ?: 0) + 1))
    fun anchorRight(a: Anchor) = copy(anchors = anchors + (a to (anchors[a] ?: 0) + 1))

    val tunesSung: Int get() = sung.count { it.value > 0 }
    val tunesTapped: Int get() = tapped.count { it.value > 0 }
    val anchorsKnown: Boolean get() = Anchor.entries.all { (anchors[it] ?: 0) >= ANCHOR_RIGHT }

    /** Enough different melodies sung and tapped, and every anchor told apart. */
    val done: Boolean get() = tunesSung >= SUNG_TUNES && tunesTapped >= TAPPED_TUNES && anchorsKnown

    companion object {
        const val SUNG_TUNES = 8
        const val TAPPED_TUNES = 6
        const val ANCHOR_RIGHT = 3
    }
}
