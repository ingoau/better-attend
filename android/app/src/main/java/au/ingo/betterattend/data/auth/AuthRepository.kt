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
import au.ingo.betterattend.data.model.SessionResponse
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.store.SecureBox
import kotlinx.coroutines.CancellationException
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

/** A separate sign-in run that mints an extra mobile token for the clipboard; the app's own session is untouched. */
sealed interface TokenIssueState {
    data object Idle : TokenIssueState
    data object Exchanging : TokenIssueState
    /** Held only until the UI has copied it. */
    data class Issued(val token: String, val user: User) : TokenIssueState
    data class Failed(val message: String) : TokenIssueState
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

    /** PKCE verifier and state of an in-flight token issue, kept apart from the sign-in ones. */
    var issuePkceVerifier: String?
        get() = prefs.getString("issue_pkce_verifier", null)
        set(value) = prefs.edit { if (value == null) remove("issue_pkce_verifier") else putString("issue_pkce_verifier", value) }

    var issueOauthState: String?
        get() = prefs.getString("issue_oauth_state", null)
        set(value) = prefs.edit { if (value == null) remove("issue_oauth_state") else putString("issue_oauth_state", value) }

    /** The device name the user picked for the token being issued. */
    var issueDeviceName: String?
        get() = prefs.getString("issue_device_name", null)
        set(value) = prefs.edit { if (value == null) remove("issue_device_name") else putString("issue_device_name", value) }

    fun clearIssue() = prefs.edit {
        remove("issue_pkce_verifier"); remove("issue_oauth_state"); remove("issue_device_name")
    }
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

    private val _tokenIssue = MutableStateFlow<TokenIssueState>(TokenIssueState.Idle)
    val tokenIssue: StateFlow<TokenIssueState> = _tokenIssue.asStateFlow()

    val currentUser: User? get() = (state.value as? AuthState.SignedIn)?.user

    fun buildAuthorizeUri(): Uri {
        val verifier = randomUrlSafe(64)
        val stateParam = randomUrlSafe(16)
        store.pkceVerifier = verifier
        store.oauthState = stateParam
        return authorizeUri(verifier, stateParam)
    }

    /**
     * Starts a fresh Hack Club sign-in whose code is exchanged for a brand-new mobile token. The
     * stored session token is never sent or reused; the result lands in [tokenIssue]. The new
     * session is labelled [deviceName] in Attend, or [defaultIssuedDeviceName] if that's blank.
     */
    fun buildIssueTokenUri(deviceName: String): Uri {
        val verifier = randomUrlSafe(64)
        val stateParam = randomUrlSafe(16)
        store.issuePkceVerifier = verifier
        store.issueOauthState = stateParam
        store.issueDeviceName = deviceName.trim().take(MAX_DEVICE_NAME).ifEmpty { defaultIssuedDeviceName }
        _tokenIssue.value = TokenIssueState.Idle
        return authorizeUri(verifier, stateParam)
    }

    private fun authorizeUri(verifier: String, stateParam: String): Uri {
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

    /** Stores a freshly issued session and switches to the signed-in state. */
    fun signInWith(session: SessionResponse) {
        store.update(session.token, session.expiresAt)
        store.user = session.user
        _state.value = AuthState.SignedIn(session.user)
    }

    fun isOAuthCallback(uri: Uri?): Boolean =
        uri != null && uri.scheme == "attend" && uri.host == "oauth" && uri.path?.startsWith("/callback") == true

    /** Completes sign-in from the redirect URI. Returns null on success or an error message. */
    suspend fun handleCallback(uri: Uri): String? {
        takeTokenIssue(uri)?.let { (verifier, deviceName) -> return completeTokenIssue(uri, verifier, deviceName) }
        // Duplicate delivery (Auth Tab result + intent filter) — the first one wins.
        if (_state.value is AuthState.Loading || _state.value is AuthState.SignedIn) return null
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
            store.pkceVerifier = null
            store.oauthState = null
            signInWith(session)
            null
        } catch (e: CancellationException) {
            _state.value = AuthState.SignedOut()
            throw e
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
     * If [uri] answers the pending token issue, claims it and returns its verifier and device name.
     * Check and claim happen under one lock, so a duplicate delivery of the same redirect (Auth Tab
     * result + intent filter) finds nothing pending and falls through to the ignored sign-in path.
     */
    private fun takeTokenIssue(uri: Uri): Pair<String, String?>? = synchronized(store) {
        val expected = store.issueOauthState ?: return null
        val returned = uri.getQueryParameter("state")
        // An error redirect may omit state; while signed in, an app sign-in can't be what's pending.
        if (returned != expected && !(returned == null && _state.value is AuthState.SignedIn)) return null
        val verifier = store.issuePkceVerifier
        val deviceName = store.issueDeviceName
        store.clearIssue()
        verifier?.let { it to deviceName }
    }

    /** Exchanges the code for a new token without storing it. Returns null on success or an error message. */
    private suspend fun completeTokenIssue(uri: Uri, verifier: String, deviceName: String?): String? {
        // The session ended while the browser was open: nobody is left to hand the token to.
        if (_state.value !is AuthState.SignedIn) { _tokenIssue.value = TokenIssueState.Idle; return null }
        uri.getQueryParameter("error")?.let { err ->
            if (err == "access_denied") { _tokenIssue.value = TokenIssueState.Idle; return null }
            return failIssue(uri.getQueryParameter("error_description") ?: "Sign-in failed ($err)")
        }
        val code = uri.getQueryParameter("code") ?: return failIssue("Sign-in didn't return a code. Please try again.")
        _tokenIssue.value = TokenIssueState.Exchanging
        return try {
            // Unauthenticated exchange: a new session, independent of the one this app is using.
            val session = apiProvider().createSession(code, BuildConfig.OAUTH_REDIRECT_URI, verifier, deviceName ?: defaultIssuedDeviceName)
            // Signed out mid-exchange: don't leave the token waiting for the next account's Settings.
            _tokenIssue.value = if (_state.value is AuthState.SignedIn) TokenIssueState.Issued(session.token, session.user) else TokenIssueState.Idle
            null
        } catch (e: CancellationException) {
            _tokenIssue.value = TokenIssueState.Idle
            throw e
        } catch (e: ApiException) {
            failIssue(
                if (e.status == 401) "We couldn't find an Attend account for that Hack Club login."
                else e.friendlyMessage
            )
        } catch (e: Exception) {
            failIssue(e.friendlyMessage)
        }
    }

    private fun failIssue(message: String): String {
        _tokenIssue.value = TokenIssueState.Failed(message)
        return message
    }

    /** The UI has copied the token or shown the error; forget it. */
    fun tokenIssueHandled() {
        _tokenIssue.value = TokenIssueState.Idle
    }

    /**
     * The browser flow ended without a result. Ignored while a code exchange is in flight: with the
     * Custom Tab fallback the redirect arrives via onNewIntent and the tab then reports "cancelled".
     */
    fun cancelled(message: String? = null) {
        val s = _state.value
        if (s is AuthState.SignedIn) {
            // A token issue. A plain cancel can race the Custom Tab redirect just like sign-in, so the
            // pending state is left for that redirect to claim; the next issue replaces it anyway.
            if (message != null && synchronized(store) { store.issueOauthState?.also { store.clearIssue() } } != null) {
                _tokenIssue.value = TokenIssueState.Failed(message)
            }
            return
        }
        if (s is AuthState.Loading) return
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
        // Keep any pending issue so its redirect is recognised and dropped, not shown as a login error.
        _tokenIssue.value = TokenIssueState.Idle
        _state.value = AuthState.SignedOut("Your session expired. Please sign in again.")
    }

    suspend fun signOut() {
        runCatching { apiProvider().deleteSession() }
        store.update(null, null)
        store.user = null
        store.clearIssue()
        _tokenIssue.value = TokenIssueState.Idle
        _state.value = AuthState.SignedOut()
    }

    private fun deviceName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (BetterAttend)"

    /** Suggested name for a copied token's session, shown in Attend's list of signed-in devices. */
    val defaultIssuedDeviceName: String
        get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (BetterAttend, copied token)"

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val AUTHORIZE_URL = "https://auth.hackclub.com/oauth/authorize"
        const val MAX_DEVICE_NAME = 100
    }
}
