package com.cuenote.signal.practice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The headphone timing check for practice. The student hears a short run of
 * clicks in their headphones and sings a short "ta" on each one. Because they
 * sing to what they *hear*, the gap between a click leaving the app and their
 * "ta" arriving back at the mic is exactly the delay a sing-along take has:
 * the headphones' output delay plus the mic's input delay. Takes recorded
 * along with a backing track are shifted back by this much so they line up.
 *
 * Everything here is pure Kotlin on 48 kHz mono, so it is tested on the JVM.
 */
object LatencyCheck {
    const val SAMPLE_RATE = 48_000

    /** Clicks in the run. The first [LISTEN_CLICKS] are just to listen to, to catch the beat. */
    const val CLICKS = 10
    const val LISTEN_CLICKS = 2
    /** 100 beats a minute: slow enough for a "ta" to finish before the next click. */
    const val INTERVAL_MS = 600
    /** Quiet before the first click, to hear the room. */
    const val LEAD_MS = 800

    /** Fewer sung clicks than this and the result isn't trusted. */
    const val MIN_HITS = 5
    /** Hits further apart than this (between the middle half) mean the timing wandered. */
    const val MAX_SPREAD_MS = 45
    /** Above this the headphones are too slow to sing along with comfortably. */
    const val TOO_SLOW_MS = 250

    /** Where each click starts, in ms from the start of [clickTrack]. */
    val clickTimesMs: List<Int> = List(CLICKS) { LEAD_MS + it * INTERVAL_MS }

    val trackMs: Int get() = LEAD_MS + CLICKS * INTERVAL_MS + 600

    /**
     * The click run as 16-bit mono: a short woodblock-like tick (a 1.6 kHz
     * tone that dies away in about 25 ms), a little louder on the listen-only
     * clicks so the count-in stands out.
     */
    fun clickTrack(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val out = ShortArray(trackMs * sampleRate / 1000)
        val len = sampleRate * 60 / 1000
        for ((i, at) in clickTimesMs.withIndex()) {
            val start = at * sampleRate / 1000
            val amp = if (i < LISTEN_CLICKS) 0.55 else 0.4
            for (k in 0 until len) {
                if (start + k >= out.size) break
                val t = k.toDouble() / sampleRate
                val v = amp * exp(-t / 0.008) * sin(2 * PI * 1600.0 * t)
                out[start + k] = (v * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    sealed interface Result {
        /** [latencyMs] is the delay to shift sing-along takes by. */
        data class Measured(val latencyMs: Int, val spreadMs: Int, val hits: Int) : Result {
            val tooSlow: Boolean get() = latencyMs > TOO_SLOW_MS
        }
        /** The mic hardly heard anything over the room. */
        data object TooQuiet : Result
        /** Not enough clicks had a "ta" near them. */
        data class TooFewHits(val hits: Int) : Result
        /** The "ta"s were found but their timing jumped around too much to trust. */
        data class Uneven(val spreadMs: Int) : Result
    }

    /**
     * Measures the delay in [recording] (48 kHz mono). [trackStartFrame] is the
     * recording frame at which the click track's first sample was handed to
     * the speaker; the clicks are then at [clickTimesMs] after it.
     */
    fun measure(recording: ShortArray, trackStartFrame: Long, sampleRate: Int = SAMPLE_RATE): Result {
        val hop = sampleRate / 400 // 2.5 ms
        val env = envelopeDb(recording, hop)
        if (env.isEmpty()) return Result.TooQuiet
        val hopMs = 1000.0 * hop / sampleRate

        // The room: the quiet start, before the first click reaches anyone.
        val leadHops = ((LEAD_MS - 100) / hopMs).toInt()
        val startHop = (trackStartFrame / hop).toInt().coerceAtLeast(0)
        val floorWindow = env.sliceSafe(startHop, startHop + leadHops)
        val floor = (if (floorWindow.size >= 20) floorWindow else env.toList()).sorted().let { it[it.size / 2] }
        val loud = env.sorted()[env.size * 98 / 100]
        if (loud - floor < 15) return Result.TooQuiet
        // Halfway (in dB) between the room and the loudest "ta", and well above the room.
        val threshold = maxOf(floor + 12, (floor + loud) / 2)

        val delays = mutableListOf<Double>()
        for (i in LISTEN_CLICKS until CLICKS) {
            val clickHop = (trackStartFrame + clickTimesMs[i].toLong() * sampleRate / 1000) / hop.toDouble()
            // A "ta" up to 150 ms early (anticipating the beat) or 450 ms late.
            val from = (clickHop - 150 / hopMs).toInt()
            val to = (clickHop + 450 / hopMs).toInt()
            val onset = firstRise(env, from, to, threshold, quietHops = (40 / hopMs).toInt()) ?: continue
            delays += (onset - clickHop) * hopMs
        }
        if (delays.size < MIN_HITS) return Result.TooFewHits(delays.size)
        val sorted = delays.sorted()
        val median = sorted[sorted.size / 2]
        // Spread: the middle half of the hits, so one sloppy "ta" doesn't fail the check.
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[(sorted.size * 3) / 4]
        val spread = (q3 - q1).toInt()
        if (spread > MAX_SPREAD_MS) return Result.Uneven(spread)
        return Result.Measured(latencyMs = median.toInt().coerceAtLeast(0), spreadMs = spread, hits = delays.size)
    }

    /** Loudness in dBFS of each [hop]-sample block. */
    internal fun envelopeDb(x: ShortArray, hop: Int): DoubleArray {
        val n = x.size / hop
        return DoubleArray(n) { b ->
            var sum = 0.0
            for (k in b * hop until (b + 1) * hop) { val v = x[k] / 32768.0; sum += v * v }
            val rms = sqrt(sum / hop)
            if (rms <= 1e-6) -120.0 else 20 * log10(rms)
        }
    }

    /**
     * The first hop in [from, to) where the envelope rises over [threshold]
     * after at least [quietHops] under it (so the tail of the last "ta" or a
     * held note doesn't count as a new one).
     */
    private fun firstRise(env: DoubleArray, from: Int, to: Int, threshold: Double, quietHops: Int): Int? {
        var quiet = 0
        // Count the quiet just before the window too.
        for (h in (from - quietHops).coerceAtLeast(0) until from.coerceIn(0, env.size)) {
            quiet = if (env[h] < threshold) quiet + 1 else 0
        }
        for (h in from.coerceAtLeast(0) until to.coerceAtMost(env.size)) {
            if (env[h] >= threshold) {
                if (quiet >= quietHops) return h
                quiet = 0
            } else quiet++
        }
        return null
    }

    private fun DoubleArray.sliceSafe(a: Int, b: Int): List<Double> =
        if (a >= size) emptyList() else toList().subList(a.coerceAtLeast(0), b.coerceAtMost(size))
}
