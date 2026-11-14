package com.cuenote.signal

/**
 * Things that happen during a lesson, stamped with the time since recording
 * started. They are written one per line to events.tsv next to the chunks so
 * they survive a crash, and later join takes by timestamp.
 */
sealed interface LessonEvent {
    val atMs: Long

    data class Mark(override val atMs: Long) : LessonEvent
    /**
     * A photo taken during the lesson. [kind] is "sketch" (the teacher's
     * drawing, cleaned up and shared) or "me" (the student's own reference
     * photo, kept as taken and private until they share it). Lines written
     * before "me" existed have no kind and are sketches.
     */
    data class Photo(override val atMs: Long, val fileName: String, val kind: String = SKETCH) : LessonEvent {
        companion object {
            const val SKETCH = "sketch"
            const val ME = "me"
        }
    }
    data class Gap(override val atMs: Long, val missingMs: Long) : LessonEvent
    data class MicChanged(override val atMs: Long, val deviceName: String) : LessonEvent

    fun toLine(): String = when (this) {
        is Mark -> "mark\t$atMs"
        is Photo -> if (kind == Photo.SKETCH) "photo\t$atMs\t$fileName" else "photo\t$atMs\t$fileName\t$kind"
        is Gap -> "gap\t$atMs\t$missingMs"
        is MicChanged -> "mic\t$atMs\t${deviceName.replace('\t', ' ')}"
    }

    companion object {
        fun parse(line: String): LessonEvent? {
            val p = line.split('\t')
            val at = p.getOrNull(1)?.toLongOrNull() ?: return null
            return when (p[0]) {
                "mark" -> Mark(at)
                "photo" -> p.getOrNull(2)?.let { Photo(at, it, if (p.getOrNull(3) == Photo.ME) Photo.ME else Photo.SKETCH) }
                "gap" -> p.getOrNull(2)?.toLongOrNull()?.let { Gap(at, it) }
                "mic" -> MicChanged(at, p.getOrElse(2) { "" })
                else -> null
            }
        }
    }
}
