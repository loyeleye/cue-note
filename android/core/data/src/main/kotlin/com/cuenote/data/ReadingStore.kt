package com.cuenote.data

import android.content.Context
import com.cuenote.pipeline.reading.ReadingLadder
import com.cuenote.pipeline.reading.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.TimeZone

/**
 * The reading and pitch track's progress: the voice check, matched notes
 * and the Name it deck. It stays on this phone; nothing is shared.
 */
class ReadingStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow(read())
    val progress: StateFlow<ReadingProgress> = _progress.asStateFlow()

    @Synchronized
    fun update(change: (ReadingProgress) -> ReadingProgress) {
        val next = change(_progress.value)
        prefs.edit().putString(KEY, json.encodeToString(ReadingProgress.serializer(), next)).apply()
        _progress.value = next
    }

    /** Today, in the phone's time zone, for counting days of practice. */
    fun today(now: Long = System.currentTimeMillis()): Long = ReadingLadder.day(now, TimeZone.getDefault().getOffset(now))

    private fun read(): ReadingProgress = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(ReadingProgress.serializer(), it) }
    }.getOrNull() ?: ReadingProgress()

    companion object {
        private const val KEY = "progress"
        @Volatile private var instance: ReadingStore? = null

        fun get(context: Context): ReadingStore = instance ?: synchronized(this) {
            instance ?: ReadingStore(context.applicationContext).also { instance = it }
        }
    }
}
