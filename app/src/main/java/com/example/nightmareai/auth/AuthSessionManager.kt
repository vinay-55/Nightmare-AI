package com.example.nightmareai.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Active authenticated session metadata.
 * [googleId] is the strict partition key for all Room queries.
 */
data class AuthSession(
    val googleId: String,
    val displayName: String,
    val email: String?,
    val isOpenAccess: Boolean
)

/**
 * Manages the active account session independently of Room persistence.
 * Logging out clears ONLY the session state here, preserving every user's rows
 * intact in Room so switching accounts never merges or deletes another account's data.
 */
class AuthSessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sessionFlow = MutableStateFlow(readPersistedSession())
    val sessionFlow: StateFlow<AuthSession?> = _sessionFlow.asStateFlow()

    fun currentSession(): AuthSession? = _sessionFlow.value

    fun isLoggedIn(): Boolean = _sessionFlow.value != null

    fun requireActiveGoogleId(): String =
        _sessionFlow.value?.googleId
            ?: throw IllegalStateException("No active session. User must sign in first.")

    /**
     * Immediate open-access login for testers and judges without Google Sign-In setup.
     */
    fun loginOpenAccess(): AuthSession {
        val session = AuthSession(
            googleId = OPEN_ACCESS_GOOGLE_ID,
            displayName = OPEN_ACCESS_DISPLAY_NAME,
            email = null,
            isOpenAccess = true
        )
        persistSession(session)
        return session
    }

    /**
     * Plug-and-play hook for Google Sign-In completion.
     * Switches the active workspace strictly to [googleId] without merging prior data.
     */
    fun onGoogleSignInSuccess(
        googleId: String,
        displayName: String,
        email: String?
    ): AuthSession {
        val trimmedId = googleId.trim()
        require(trimmedId.isNotEmpty()) { "googleId must not be blank" }
        val session = AuthSession(
            googleId = trimmedId,
            displayName = displayName.ifBlank { email ?: trimmedId },
            email = email?.takeIf { it.isNotBlank() },
            isOpenAccess = false
        )
        persistSession(session)
        return session
    }

    /**
     * Convenience overload to bridge a [GoogleSignInAccount] directly.
     */
    @Suppress("DEPRECATION")
    fun onGoogleSignInAccount(account: GoogleSignInAccount): AuthSession {
        val id = account.id ?: account.email ?: throw IllegalArgumentException("GoogleSignInAccount missing id")
        return onGoogleSignInSuccess(
            googleId = id,
            displayName = account.displayName ?: account.givenName ?: "Judge",
            email = account.email
        )
    }

    /**
     * Builds a ready-to-use [GoogleSignInClient] for future real Google Sign-In wiring.
     */
    @Suppress("DEPRECATION")
    fun createGoogleSignInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestId()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    /**
     * Clears only the active session in SharedPreferences.
     * Room rows for this googleId remain untouched for future re-login.
     */
    fun logout() {
        prefs.edit().clear().apply()
        _sessionFlow.value = null
    }

    private fun persistSession(session: AuthSession) {
        prefs.edit()
            .putString(KEY_GOOGLE_ID, session.googleId)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putString(KEY_EMAIL, session.email)
            .putBoolean(KEY_IS_OPEN_ACCESS, session.isOpenAccess)
            .apply()
        _sessionFlow.value = session
    }

    private fun readPersistedSession(): AuthSession? {
        val googleId = prefs.getString(KEY_GOOGLE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: OPEN_ACCESS_DISPLAY_NAME
        val email = prefs.getString(KEY_EMAIL, null)
        val isOpenAccess = prefs.getBoolean(KEY_IS_OPEN_ACCESS, googleId == OPEN_ACCESS_GOOGLE_ID)
        return AuthSession(
            googleId = googleId,
            displayName = displayName,
            email = email,
            isOpenAccess = isOpenAccess
        )
    }

    companion object {
        const val OPEN_ACCESS_GOOGLE_ID = "open_access_user"
        private const val OPEN_ACCESS_DISPLAY_NAME = "Open Access Judge"
        private const val PREFS_NAME = "nightmare_auth_prefs"
        private const val KEY_GOOGLE_ID = "active_google_id"
        private const val KEY_DISPLAY_NAME = "active_display_name"
        private const val KEY_EMAIL = "active_email"
        private const val KEY_IS_OPEN_ACCESS = "active_is_open_access"
    }
}
