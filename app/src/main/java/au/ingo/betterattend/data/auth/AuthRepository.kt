package au.ingo.betterattend.data.auth

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import au.ingo.betterattend.BuildConfig
import au.ingo.betterattend.data.api.ApiException
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.store.SecureBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

sealed interface AuthState {
    data object Loading : AuthState
    data class SignedOut(val message: String? = null) : AuthState
    data class SignedIn(val user: User) : AuthState
}

/** Persists the session token (encrypted) and the in-flight PKCE verifier. */
class SecureTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    @Volatile private var cached: String? = prefs.getString("token", null)?.let { SecureBox.decryptString(it) }

    override val token: String? get() = cached

    override fun update(token: String?, expiresAt: String?) {
        cached = token
        prefs.edit {
            if (token == null) { remove("token"); remove("expires_at") }
            else { putString("token", SecureBox.encryptString(token)); putString("expires_at", expiresAt) }
        }
    }

    var user: User?
        get() = prefs.getString("user", null)?.let { SecureBox.decryptString(it) }
            ?.let { runCatching { AttendJson.decodeFromString(User.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit {
            if (value == null) remove("user") else putString("user", SecureBox.encryptString(AttendJson.encodeToString(User.serializer(), value)))
        }

    var pkceVerifier: String?
        get() = prefs.getString("pkce_verifier", null)
        set(value) = prefs.edit { if (value == null) remove("pkce_verifier") else putString("pkce_verifier", value) }

    var oauthState: String?
        get() = prefs.getString("oauth_state", null)
        set(value) = prefs.edit { if (value == null) remove("oauth_state") else putString("oauth_state", value) }
}

/**
 * Hack Club Auth sign-in using the same OAuth client and redirect as the official app:
 * Authorization Code + PKCE in an Auth Tab / Custom Tab, then the code is exchanged by the
 * Attend backend (which holds the client secret) for a 14-day mobile token.
 */
class AuthRepository(
    private val store: SecureTokenStore,
    private val apiProvider: () -> AttendApi,
) {
    private val _state = MutableStateFlow<AuthState>(
        store.token?.let { t -> store.user?.let { AuthState.SignedIn(it) } } ?: AuthState.SignedOut()
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val currentUser: User? get() = (state.value as? AuthState.SignedIn)?.user

    fun buildAuthorizeUri(): Uri {
        val verifier = randomUrlSafe(64)
        val stateParam = randomUrlSafe(16)
        store.pkceVerifier = verifier
        store.oauthState = stateParam
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.OAUTH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "email")
            .appendQueryParameter("state", stateParam)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    fun isOAuthCallback(uri: Uri?): Boolean =
        uri != null && uri.scheme == "attend" && uri.host == "oauth" && uri.path?.startsWith("/callback") == true

    /** Completes sign-in from the redirect URI. Returns null on success or an error message. */
    suspend fun handleCallback(uri: Uri): String? {
        val verifier = store.pkceVerifier
        uri.getQueryParameter("error")?.let { err ->
            val desc = uri.getQueryParameter("error_description")
            return fail(if (err == "access_denied") null else desc ?: "Sign-in failed ($err)")
        }
        val code = uri.getQueryParameter("code") ?: return fail("Sign-in didn't return a code. Please try again.")
        val returnedState = uri.getQueryParameter("state")
        if (store.oauthState != null && returnedState != null && returnedState != store.oauthState) {
            return fail("Sign-in response didn't match this request. Please try again.")
        }
        if (verifier == null) return fail("Sign-in session expired. Please try again.")
        _state.value = AuthState.Loading
        return try {
            val session = apiProvider().createSession(code, BuildConfig.OAUTH_REDIRECT_URI, verifier, deviceName())
            store.update(session.token, session.expiresAt)
            store.user = session.user
            store.pkceVerifier = null
            store.oauthState = null
            _state.value = AuthState.SignedIn(session.user)
            null
        } catch (e: ApiException) {
            fail(
                if (e.status == 401) "We couldn't find an Attend account for that Hack Club login. " +
                    "Ask your event organiser to add you, then try again."
                else e.friendlyMessage
            )
        } catch (e: Exception) {
            fail(e.friendlyMessage)
        }
    }

    /**
     * The browser flow ended without a result. Ignored while a code exchange is in flight: with the
     * Custom Tab fallback the redirect arrives via onNewIntent and the tab then reports "cancelled".
     */
    fun cancelled(message: String? = null) {
        val s = _state.value
        if (s is AuthState.SignedIn || s is AuthState.Loading) return
        _state.value = AuthState.SignedOut(message)
    }

    private fun fail(message: String?): String? {
        _state.value = AuthState.SignedOut(message)
        return message
    }

    /** Re-validates the stored session in the background; keeps working offline. */
    suspend fun refreshUser() {
        if (store.token == null) return
        try {
            val user = apiProvider().me()
            store.user = user
            _state.value = AuthState.SignedIn(user)
        } catch (e: ApiException) {
            if (e.isUnauthorized) sessionExpired()
        } catch (_: Exception) {
            // Offline: keep the cached session.
        }
    }

    fun sessionExpired() {
        if (store.token == null && _state.value is AuthState.SignedOut) return
        store.update(null, null)
        store.user = null
        _state.value = AuthState.SignedOut("Your session expired. Please sign in again.")
    }

    suspend fun signOut() {
        runCatching { apiProvider().deleteSession() }
        store.update(null, null)
        store.user = null
        _state.value = AuthState.SignedOut()
    }

    private fun deviceName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Better Attend)"

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val AUTHORIZE_URL = "https://auth.hackclub.com/oauth/authorize"
    }
}
