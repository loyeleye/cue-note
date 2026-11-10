package com.cuenote.signal.posture

/**
 * posture/events.tsv in the lesson folder: one line per thing that happened
 * to posture tracking, stamped like events.tsv with the time since the
 * recording started. Body points go to posture/pose.tsv; there is never any
 * video, only a still snapshot at each flag.
 */
sealed interface PostureEvent {
    val atMs: Long

    data class Started(override val atMs: Long) : PostureEvent
    data class CameraOn(override val atMs: Long) : PostureEvent
    data class CameraOff(override val atMs: Long) : PostureEvent
    data class Paused(override val atMs: Long, val reason: String) : PostureEvent
    data class Resumed(override val atMs: Long) : PostureEvent
    data class Flagged(override val atMs: Long, val flag: PostureFlag, val snapshot: String, val detail: String) : PostureEvent
    data class FlagEnded(override val atMs: Long, val flag: PostureFlag) : PostureEvent
    data class Stopped(override val atMs: Long) : PostureEvent

    fun toLine(): String = when (this) {
        is Started -> "start\t$atMs"
        is CameraOn -> "camera_on\t$atMs"
        is CameraOff -> "camera_off\t$atMs"
        is Paused -> "pause\t$atMs\t${clean(reason)}"
        is Resumed -> "resume\t$atMs"
        is Flagged -> "flag\t$atMs\t${flag.key}\t$snapshot\t${clean(detail)}"
        is FlagEnded -> "flag_end\t$atMs\t${flag.key}"
        is Stopped -> "stop\t$atMs"
    }

    companion object {
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        private fun flag(key: String?) = PostureFlag.entries.firstOrNull { it.key == key }

        fun parse(line: String): PostureEvent? {
            val p = line.split('\t')
            val at = p.getOrNull(1)?.toLongOrNull() ?: return null
            return when (p[0]) {
                "start" -> Started(at)
                "camera_on" -> CameraOn(at)
                "camera_off" -> CameraOff(at)
                "pause" -> Paused(at, p.getOrElse(2) { "" })
                "resume" -> Resumed(at)
                "flag" -> flag(p.getOrNull(2))?.let { Flagged(at, it, p.getOrElse(3) { "" }, p.getOrElse(4) { "" }) }
                "flag_end" -> flag(p.getOrNull(2))?.let { FlagEnded(at, it) }
                "stop" -> Stopped(at)
                else -> null
            }
        }
    }
}

/** What a lesson's posture tracking came to, read back from posture/events.tsv. */
data class PostureSummary(
    val trackedMs: Long,
    val flags: List<PostureEvent.Flagged>,
    val pauses: List<PostureEvent.Paused>,
) {
    companion object {
        fun of(events: List<PostureEvent>): PostureSummary {
            var tracked = 0L
            var onAt = -1L
            var lastAt = 0L
            for (e in events) {
                lastAt = maxOf(lastAt, e.atMs)
                when (e) {
                    is PostureEvent.CameraOn -> if (onAt < 0) onAt = e.atMs
                    is PostureEvent.CameraOff, is PostureEvent.Stopped -> if (onAt >= 0) { tracked += e.atMs - onAt; onAt = -1 }
                    else -> Unit
                }
            }
            if (onAt >= 0) tracked += lastAt - onAt // the app stopped without closing the camera line
            return PostureSummary(
                trackedMs = tracked,
                flags = events.filterIsInstance<PostureEvent.Flagged>(),
                pauses = events.filterIsInstance<PostureEvent.Paused>(),
            )
        }

        fun parse(text: String): PostureSummary = of(text.lineSequence().mapNotNull(PostureEvent::parse).toList())
    }
}
