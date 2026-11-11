package com.cuenote.data

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.functions.Functions
import kotlin.time.Duration.Companion.seconds
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Magic-link sign-in. The email link opens cuenote://auth-callback, which
 * MainActivity hands to [handleDeepLink] to finish signing in.
 *
 * Recording and processing work fully offline without signing in. Making
 * cards (M3) needs it: clips upload to the student's own folder and the cards
 * come back from the cloud.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _linkStatus = MutableStateFlow<String?>(null)

    /**
     * What happened to the last sign-in link: "Signing in…", an error to show,
     * or null once signed in. The library's own handler drops errors silently,
     * so we read the link ourselves.
     */
    val linkStatus: StateFlow<String?> = _linkStatus

    fun handleDeepLink(intent: Intent) {
        val c = client ?: return
        val uri = intent.data ?: return
        if (uri.scheme != "cuenote" || uri.host != "auth-callback") return
        Log.i(TAG, "Sign-in link opened: ${redact(uri)}")

        linkError(uri)?.let {
            _linkStatus.value = "Sign-in failed: $it. Send yourself a new link."
            return
        }
        val code = uri.getQueryParameter("code") ?: run {
            _linkStatus.value = "That link didn't contain a sign-in code. Send yourself a new link."
            return
        }
        _linkStatus.value = "Signing in…"
        scope.launch {
            _linkStatus.value = runCatching { c.auth.exchangeCodeForSession(code) }.fold(
                { null },
                {
                    Log.w(TAG, "Code exchange failed", it)
                    "Sign-in failed: ${it.message}. Request the link and open it on this phone, in the same app install."
                },
            )
        }
    }

    fun clearLinkStatus() { _linkStatus.value = null }

    /** Runs just before signing out, while the session still works (the app removes its push token). */
    var beforeSignOut: (suspend () -> Unit)? = null

    suspend fun signOut() {
        runCatching { beforeSignOut?.invoke() }
        client?.auth?.signOut()
    }

    companion object {
        private const val TAG = "CueNoteAuth"

        /** Supabase reports link errors in the query or, on redirects, in the #fragment. */
        internal fun linkError(uri: Uri): String? {
            val fragment = uri.encodedFragment.orEmpty().split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to Uri.decode(it.substring(i + 1).replace('+', ' '))
            }.toMap()
            fun param(name: String) = uri.getQueryParameter(name) ?: fragment[name]
            val error = param("error") ?: param("error_code") ?: return null
            return param("error_description") ?: error
        }

        private fun redact(uri: Uri) = uri.toString().replace(Regex("(code|access_token|refresh_token)=[^&#]+"), "$1=…")

        @Volatile private var instance: AuthRepository? = null

        /** One client for the app and its background workers (they share the saved session). */
        fun create(): AuthRepository = instance ?: synchronized(this) { instance ?: build().also { instance = it } }

        fun get(): AuthRepository = create()

        private fun build(): AuthRepository {
            if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
                return AuthRepository(null)
            }
            val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
                // Turning a lesson into cards can take a minute or two.
                requestTimeout = 150.seconds
                install(Auth) {
                    flowType = FlowType.PKCE
                    scheme = "cuenote"
                    host = "auth-callback"
                }
                install(Postgrest)
                install(Storage)
                install(Functions)
            }
            return AuthRepository(client)
        }
    }
}
