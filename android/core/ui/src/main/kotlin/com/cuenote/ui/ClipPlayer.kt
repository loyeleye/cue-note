package com.cuenote.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Plays one replay clip at a time from the phone's own copy, so replay works
 * offline. Starting a clip stops the one before; tapping the playing clip
 * stops it.
 */
class ClipPlayer {
    /** The clip file playing now, or null. */
    var playing by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(file: String) {
        if (playing == file) { stop(); return }
        stop()
        error = null
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(file)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            playing = file
        } catch (e: Exception) {
            p.release()
            error = "Couldn't play that clip: ${e.message}"
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
fun rememberClipPlayer(): ClipPlayer {
    val player = remember { ClipPlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}
