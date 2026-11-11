package com.cuenote.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * "Card updated" pushes from the notify edge function.
 *
 * Each push carries a notification (title, her new cue) and data: kind,
 * lesson_id, feedback_id. With the app in the background Android shows the
 * notification itself on [CHANNEL], and a tap opens MainActivity with the data
 * as intent extras. With the app open, [onMessageReceived] shows it and pulls
 * the lesson's cards again so the screen updates.
 */
class CardPushService : FirebaseMessagingService() {
    private val app get() = application as CueNoteApp

    override fun onNewToken(token: String) {
        app.scope.launch { app.deviceTokens.register(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val lessonId = message.data[EXTRA_LESSON]?.takeIf { it.isNotBlank() }
        if (lessonId != null) app.scope.launch { runCatching { app.lessons.refreshCards(lessonId) } }
        val note = message.notification ?: return
        show(this, note.title ?: "Card updated", note.body.orEmpty(), lessonId)
    }

    companion object {
        const val CHANNEL = "card_updates"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LESSON = "lesson_id"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Card updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When your teacher changes a note from a lesson"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun show(context: Context, title: String, body: String, lessonId: String?) {
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_KIND, "card_updated")
                lessonId?.let { putExtra(EXTRA_LESSON, it) }
            }
            val tap = PendingIntent.getActivity(
                context, lessonId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            // Without the notification permission this quietly does nothing; the cards still refresh.
            runCatching { NotificationManagerCompat.from(context).notify(lessonId.hashCode(), n) }
        }

        /** The lesson a tapped "card updated" notification points at, if this intent is one. */
        fun lessonFrom(intent: Intent?): String? =
            intent?.takeIf { it.getStringExtra(EXTRA_KIND) == "card_updated" }
                ?.getStringExtra(EXTRA_LESSON)?.takeIf { it.isNotBlank() }
    }
}
