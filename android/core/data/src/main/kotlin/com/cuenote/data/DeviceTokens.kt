package com.cuenote.data

import android.content.Context
import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The phone's Firebase push token in public.device_token, so the notify edge
 * function can tell the student when a teacher changes a card.
 *
 * The token is saved here as well as online: a new token arrives whenever
 * Firebase rotates it, sign-in may come later, and sign-out has to remove the
 * row while the session can still do so (the table's rule is "your own rows").
 */
class DeviceTokens(context: Context, private val auth: AuthRepository) {
    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    @Serializable
    private data class Row(
        val token: String,
        @SerialName("user_id") val userId: String,
        val platform: String = "android",
        @SerialName("updated_at") val updatedAt: String,
    )

    /** Remembers [token] and, when someone is signed in, registers it for them. */
    suspend fun register(token: String) {
        prefs.edit().putString(KEY, token).apply()
        val client = auth.client ?: return
        client.auth.awaitInitialization()
        val user = client.auth.currentUserOrNull()?.id ?: return
        runCatching {
            client.from("device_token").upsert(Row(token, user, updatedAt = java.time.Instant.now().toString())) {
                onConflict = "token"
            }
        }.onFailure { Log.w(TAG, "Couldn't register this phone for notifications", it) }
    }

    /** Registers the token Firebase gave last (after a sign-in). */
    suspend fun registerSaved() {
        prefs.getString(KEY, null)?.let { register(it) }
    }

    /** Removes this phone's row before signing out, so the next person's cards don't arrive here. */
    suspend fun unregister() {
        val token = prefs.getString(KEY, null) ?: return
        val client = auth.client ?: return
        runCatching { client.from("device_token").delete { filter { eq("token", token) } } }
            .onFailure { Log.w(TAG, "Couldn't remove this phone's notification token", it) }
    }

    private companion object {
        const val TAG = "CueNotePush"
        const val KEY = "fcm_token"
    }
}
