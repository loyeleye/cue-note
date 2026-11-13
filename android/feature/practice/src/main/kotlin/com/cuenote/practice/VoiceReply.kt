package com.cuenote.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cuenote.data.PracticeRepository
import com.cuenote.ui.ClipPlayer
import com.cuenote.ui.PlayPill
import kotlinx.coroutines.launch

/**
 * The teacher's reply to a take or a question: their words, and their voice
 * note when they recorded one. The voice note is fetched the first time it's
 * played, then kept on the phone.
 */
@Composable
fun TeacherReply(
    teacher: String,
    words: String?,
    clipPath: String?,
    repo: PracticeRepository,
    player: ClipPlayer,
    modifier: Modifier = Modifier,
) {
    if (words == null && clipPath == null) return
    val m = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var local by remember(clipPath) { mutableStateOf<String?>(null) }
    var fetching by remember(clipPath) { mutableStateOf(false) }
    var failed by remember(clipPath) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (words != null) Text("$teacher: $words", style = MaterialTheme.typography.bodyMedium)
        if (clipPath != null) {
            PlayPill(
                if (fetching) "Getting $teacher's reply…" else "$teacher's voice reply",
                playing = local != null && player.playing == local,
                filled = false,
                onClick = {
                    local?.let { player.toggle(it); return@PlayPill }
                    if (fetching) return@PlayPill
                    fetching = true; failed = false
                    scope.launch {
                        val f = repo.voiceNote(clipPath)
                        fetching = false
                        if (f == null) failed = true else { local = f.absolutePath; player.toggle(f.absolutePath) }
                    }
                },
            )
            if (failed) Text("Couldn't get it just now. Try again when you're online.", color = m.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
