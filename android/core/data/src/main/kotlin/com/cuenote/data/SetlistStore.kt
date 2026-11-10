package com.cuenote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The student's own "On my setlist" switch on a card. A card the teacher
 * marked "practice this" starts on the setlist; the student can take it off,
 * or put any other card on. Kept on the phone only, so it never changes what
 * the teacher wrote.
 */
class SetlistStore(context: Context) {
    private val prefs = context.getSharedPreferences("setlist", Context.MODE_PRIVATE)
    private val _overrides = MutableStateFlow(read())

    /** Card id to the student's choice, for cards they switched. */
    val overrides: StateFlow<Map<String, Boolean>> = _overrides.asStateFlow()

    fun set(cardId: String, on: Boolean) {
        prefs.edit().putBoolean(cardId, on).apply()
        _overrides.value = _overrides.value + (cardId to on)
    }

    fun forget(cardIds: Collection<String>) {
        prefs.edit().apply { cardIds.forEach { remove(it) } }.apply()
        _overrides.value = _overrides.value - cardIds.toSet()
    }

    private fun read(): Map<String, Boolean> = prefs.all.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap()

    companion object {
        /** Whether a card is on the setlist: the student's switch, else the teacher's flag. */
        fun isOn(card: CardEntity, overrides: Map<String, Boolean>): Boolean =
            overrides[card.id] ?: (card.practiceFlag == "practice_this")
    }
}
