package com.cuenote.data

import android.content.Context
import java.time.LocalDate

/**
 * Cue Note is for people 13 and older. The app asks for a date of birth once,
 * before anything else, and keeps only the answer: "old enough" or "blocked".
 * The date itself is never saved or sent anywhere.
 *
 * A blocked answer sticks, so going back and trying an older date doesn't get
 * past it (the usual expectation for an age screen under COPPA).
 */
class AgeGate(context: Context) {
    private val prefs = context.getSharedPreferences("age_gate", Context.MODE_PRIVATE)

    enum class Status { UNKNOWN, OLD_ENOUGH, BLOCKED }

    fun status(): Status = runCatching { Status.valueOf(prefs.getString(KEY, null) ?: "") }.getOrDefault(Status.UNKNOWN)

    /** Saves the outcome for [birth] and returns it. */
    fun answer(birth: LocalDate, today: LocalDate = LocalDate.now()): Status {
        if (status() == Status.BLOCKED) return Status.BLOCKED
        val outcome = if (isOldEnough(birth, today)) Status.OLD_ENOUGH else Status.BLOCKED
        prefs.edit().putString(KEY, outcome.name).apply()
        return outcome
    }

    companion object {
        const val MIN_AGE = 13L
        private const val KEY = "status"

        /** True once the person has had their 13th birthday. A date in the future is never old enough. */
        fun isOldEnough(birth: LocalDate, today: LocalDate): Boolean =
            !birth.isAfter(today) && !birth.plusYears(MIN_AGE).isAfter(today)
    }
}
