package com.cuenote.app.car

import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.cuenote.app.CueNoteApp
import com.cuenote.app.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cue Note in the car (Android Auto) and in the phone's media controls: a
 * media library to browse, and one player that runs today's practice as a
 * playlist, hands-free. As the playlist moves past a step, the step is saved
 * to the sitting just as Next or Skip would on the phone, so the teacher's
 * log and "Pick up at 4 of 6" stay right. A Cool-down button skips straight
 * to the cool-down, the car's answer to "Still feel easy?".
 */
class CarPlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var sounds: CarSounds
    private lateinit var library: CarLibrary
    private var session: MediaLibrarySession? = null

    /** The sitting playing now, if any: its id, the step it's on, and the steps whose singing part started. */
    private class Sitting(val planId: String, var step: Int, val started: MutableSet<Int> = mutableSetOf())
    private var sitting: Sitting? = null
    private val saving = Mutex()

    override fun onCreate() {
        super.onCreate()
        val app = application as CueNoteApp
        sounds = CarSounds(this)
        library = CarLibrary(app, sounds)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { item?.let(::reached) }
        })
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setCustomLayout(ImmutableList.of(coolDownButton))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away on the phone stops practice unless it's playing (in the car, say).
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player.release()
        sounds.close()
        scope.cancel()
        super.onDestroy()
    }

    /** The playlist reached [item]: save every step it moved past. */
    private fun reached(item: MediaItem) {
        val extras = item.mediaMetadata.extras ?: return
        val planId = extras.getString(CarLibrary.EXTRA_PLAN) ?: return
        val s = sitting?.takeIf { it.planId == planId } ?: return
        val step = extras.getInt(CarLibrary.EXTRA_STEP)
        if (step > s.step) {
            val from = s.step
            val skipped = (from until step).filter { it !in s.started }
            s.step = step
            scope.launch { save(planId, from, step, skipped) }
        }
        if (extras.getBoolean(CarLibrary.EXTRA_MAIN)) s.started += step
    }

    /** Steps [from] up to (not including) [to] are behind the student now. One with nothing sung counts as skipped. */
    private suspend fun save(planId: String, from: Int, to: Int, skipped: List<Int>) = saving.withLock {
        val plans = (application as CueNoteApp).plans
        withContext(Dispatchers.IO) {
            for (i in from until to) runCatching { plans.advance(planId, i, skip = i in skipped) }
                .onFailure { Log.w(TAG, "Couldn't save step $i", it) }
        }
    }

    /** Cool-down now: the step playing is done, the rest is dropped, and the cool-down plays. */
    private suspend fun coolDown() {
        val s = sitting ?: return
        val plans = (application as CueNoteApp).plans
        val p = saving.withLock {
            withContext(Dispatchers.IO) {
                val now = plans.get(s.planId) ?: return@withContext null
                val steps = plans.steps(now)
                if (now.endedAt != null || steps.getOrNull(now.stepIndex)?.kind == com.cuenote.pipeline.practice.StepKind.COOL_DOWN) return@withContext null
                plans.advance(s.planId, now.stepIndex, skip = now.stepIndex !in s.started)
                plans.easyChecked(s.planId, toCoolDown = true)
            }
        } ?: return
        val items = library.sitting(p)
        sitting = Sitting(p.id, p.stepIndex)
        player.setMediaItems(items)
        player.prepare()
        player.play()
    }

    private val coolDownCommand = SessionCommand(ACTION_COOL_DOWN, Bundle.EMPTY)
    private val coolDownButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
        .setDisplayName("Cool-down")
        .setCustomIconResId(R.drawable.ic_car_cool_down)
        .setSessionCommand(coolDownCommand)
        .build()

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(coolDownCommand).build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> = scope.future {
            if (customCommand.customAction == ACTION_COOL_DOWN) coolDown()
            SessionResult(SessionResult.RESULT_SUCCESS)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future { LibraryResult.ofItem(library.root(), params) }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = runCatching { library.children(parentId) }.onFailure { Log.w(TAG, "Couldn't list $parentId", it) }.getOrDefault(emptyList())
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val parent = parentOf(mediaId)
            val found = runCatching { library.children(parent) }.getOrDefault(emptyList()).firstOrNull { it.mediaId == mediaId }
            if (found != null) LibraryResult.ofItem(found, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /**
         * The car picked something: a sitting, a note, a lesson or a track.
         * It sends one item; it becomes the whole queue.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val picked = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            val queue = picked?.let { runCatching { library.queueFor(it.mediaId) }.onFailure { e -> Log.w(TAG, "Couldn't play ${it.mediaId}", e) }.getOrNull() }
            if (queue == null || queue.items.isEmpty()) {
                // Already-built items (the phone's own controls resuming a queue) play as they are.
                return@future MediaSession.MediaItemsWithStartPosition(mediaItems.filter { it.localConfiguration != null }, 0, C.TIME_UNSET)
            }
            sitting = queue.planId?.let { id -> Sitting(id, step = firstStep(queue.items)) }
            MediaSession.MediaItemsWithStartPosition(queue.items, 0, C.TIME_UNSET)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { m ->
                if (m.localConfiguration != null) listOf(m)
                else runCatching { library.queueFor(m.mediaId)?.items }.getOrNull().orEmpty()
            }.toMutableList()
        }
    }

    private fun firstStep(items: List<MediaItem>) = items.firstOrNull()?.mediaMetadata?.extras?.getInt(CarLibrary.EXTRA_STEP) ?: 0

    /** The folder a browse item lives in, for looking it up again. */
    private fun parentOf(mediaId: String): String = when {
        mediaId.startsWith(CarLibrary.START) || mediaId.startsWith(CarLibrary.RESUME) -> CarLibrary.TODAY
        mediaId.startsWith(CarLibrary.NOTE) -> CarLibrary.NOTES
        mediaId.startsWith(CarLibrary.LESSON) -> CarLibrary.LESSONS
        mediaId.startsWith(CarLibrary.TRACK) -> CarLibrary.BACKING_OF + mediaId.removePrefix(CarLibrary.TRACK).substringBefore(':')
        mediaId.startsWith(CarLibrary.BACKING_OF) -> CarLibrary.BACKING
        else -> CarLibrary.ROOT
    }

    private companion object {
        const val TAG = "CarPlayback"
        const val ACTION_COOL_DOWN = "com.cuenote.car.COOL_DOWN"
    }
}
