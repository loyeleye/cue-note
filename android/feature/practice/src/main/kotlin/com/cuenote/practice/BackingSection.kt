package com.cuenote.practice

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.PracticeRepository
import com.cuenote.ui.PlayPill
import com.cuenote.ui.TicketRow

/** Plays one file at a time, optionally on a loop. */
class LoopPlayer {
    var playing by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun toggle(file: String, loop: Boolean = false) {
        if (playing == file) { stop(); return }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            p.setDataSource(file)
            p.isLooping = loop
            p.setOnCompletionListener { if (!loop) stop() }
            p.prepare()
            p.start()
            player = p
            playing = file
        } catch (e: Exception) {
            p.release()
        }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        playing = null
    }
}

@Composable
fun rememberLoopPlayer(): LoopPlayer {
    val p = remember { LoopPlayer() }
    DisposableEffect(p) { onDispose { p.stop() } }
    return p
}

/** How the student has the backing set: slower or not, looping or not. */
data class BackingSettings(val slower: Boolean = false, val loop: Boolean = false) {
    val rate: Double get() = if (slower) SLOWER else 1.0

    companion object {
        /** "Slower" plays at three quarters of the speed, at the same pitch. */
        const val SLOWER = 0.75
    }
}

/**
 * The backing for a note: the piano from the lesson to sing along with, the
 * teacher's sung demos, the starting note, and slower and loop switches.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun BackingSection(
    backing: PracticeRepository.Backing?,
    loading: Boolean,
    settings: BackingSettings,
    onSettings: (BackingSettings) -> Unit,
    isPlaying: (PracticeRepository.Track) -> Boolean,
    onPlayTrack: (PracticeRepository.Track) -> Unit,
    onPlayNote: () -> Unit,
    notePlaying: Boolean,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        loading -> Text("Getting the music from your lesson ready…", color = muted, style = MaterialTheme.typography.bodySmall)
        backing == null || (backing.tracks.isEmpty() && backing.startingNote == null) -> Text(
            "No piano or sung demo was found for this note, so it's you and their words.",
            color = muted, style = MaterialTheme.typography.bodySmall,
        )
        else -> TicketRow {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Music from your lesson", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    backing.accompaniment?.let { t ->
                        PlayPill("The piano", isPlaying(t), filled = true, onClick = { onPlayTrack(t) })
                    }
                    backing.demos.forEach { t ->
                        val label = if (backing.demos.size == 1) "Their demo" else "Their demo ${t.span.number}"
                        PlayPill(label, isPlaying(t), filled = false, onClick = { onPlayTrack(t) })
                    }
                    backing.startingNote?.let { n ->
                        PlayPill("Starting note ${n.name}", notePlaying, filled = false, onClick = onPlayNote)
                    }
                }
                if (backing.tracks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = settings.slower, onClick = { onSettings(settings.copy(slower = !settings.slower)) },
                            label = { Text("Slower") })
                        FilterChip(selected = settings.loop, onClick = { onSettings(settings.copy(loop = !settings.loop)) },
                            label = { Text("Loop") })
                    }
                }
            }
        }
    }
}
