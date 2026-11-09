package com.cuenote.data

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Magic-link sign-in. The email link opens cuenote://auth-callback, which
 * MainActivity hands to [handleDeepLink] to finish signing in.
 *
 * Signing in is optional in M1: recording works fully offline. It becomes
 * required in M3, when clips start uploading.
 */
class AuthRepository private constructor(internal val client: SupabaseClient?) {

    val isConfigured: Boolean get() = client != null

    /** The signed-in email, or null. */
    val signedInEmail: Flow<String?> = client?.auth?.sessionStatus?.map {
        (it as? SessionStatus.Authenticated)?.session?.user?.email
    } ?: flowOf(null)

    suspend fun sendMagicLink(email: String) {
        val c = requireNotNull(client) { "Supabase is not configured in local.properties" }
        c.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
        }
    }

    fun handleDeepLink(intent: Intent) {
        client?.handleDeeplinks(intent)
    }

    suspend fun signOut() {
        client?.auth?.signOut()
    }

    companion object {
        fun create(): AuthRepository {
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
                return AuthRepository(null)
            }
            val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
                install(Auth) {
                    flowType = FlowType.PKCE
                    scheme = "cuenote"
                    host = "auth-callback"
                }
                install(Postgrest)
            }
            return AuthRepository(client)
        }
    }
}
