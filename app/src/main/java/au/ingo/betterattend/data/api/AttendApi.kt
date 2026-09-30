package au.ingo.betterattend.data.api

import au.ingo.betterattend.BuildConfig
import au.ingo.betterattend.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A non-2xx response from Attend. `status == 0` never happens here; network failures are plain IOExceptions. */
class ApiException(val status: Int, message: String) : IOException(message) {
    val isUnauthorized get() = status == 401
    val isForbidden get() = status == 403
    val isNotFound get() = status == 404
    val isRateLimited get() = status == 429
    val isServerError get() = status >= 500
}

/** True for failures worth retrying later (offline, timeouts, rate limits, 5xx). */
val Throwable.isTransient: Boolean
    get() = when (this) {
        is ApiException -> isRateLimited || isServerError
        is IOException -> true
        else -> false
    }

/** Human-readable message for any failure, suitable for a snackbar. */
val Throwable.friendlyMessage: String
    get() = when (this) {
        is ApiException -> when {
            isUnauthorized -> "Your session has expired. Please sign in again."
            isForbidden -> message?.takeIf { it != "Forbidden" } ?: "You don't have access to this."
            isRateLimited -> "Attend is rate limiting this network. Try again in a minute."
            isServerError -> "Attend is having trouble right now (${status}). Try again shortly."
            else -> message ?: "Something went wrong ($status)."
        }
        is java.net.UnknownHostException, is java.net.ConnectException -> "You're offline. Check your connection."
        is java.net.SocketTimeoutException -> "Attend took too long to respond."
        is IOException -> "Couldn't reach Attend. Check your connection."
        else -> message ?: "Something went wrong."
    }

interface TokenStore {
    val token: String?
    fun update(token: String?, expiresAt: String?)
}

val AttendJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}

class AttendApi(
    private val tokens: TokenStore,
    private val onSessionExpired: () -> Unit,
    /** Mutable only so tests can point the app at a local mock server. */
    var baseUrl: String = BuildConfig.API_BASE_URL,
    client: OkHttpClient? = null,
) {
    val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = AttendJson
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ---------------- low level ----------------

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                // If the caller was cancelled in the meantime, don't leak the connection.
                cont.resume(response) { _, _, _ -> response.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private fun buildRequest(method: String, path: String, query: Map<String, String?>, body: JsonObject?, token: String?): Request {
        val url = (baseUrl.trimEnd('/') + "/api/v1" + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        val reqBody = when {
            body != null -> json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonType)
            method == "POST" || method == "PUT" || method == "PATCH" -> "{}".toRequestBody(jsonType)
            else -> null
        }
        return Request.Builder().url(url).method(method, reqBody).apply {
            header("Accept", "application/json")
            header("User-Agent", "BetterAttend-Android/${BuildConfig.VERSION_NAME}")
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
    }

    /** Token a background rotation has been started for, so parallel responses don't each rotate. */
    private val rotatingFor = AtomicReference<String?>(null)

    /**
     * Performs a request on the IO dispatcher, returning the raw body. A 401 caused by a concurrent
     * token rotation is retried once with the new token instead of signing the user out.
     */
    suspend fun raw(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: JsonObject? = null,
        authenticated: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        val usedToken = if (authenticated) tokens.token else null
        var response = http.newCall(buildRequest(method, path, query, body, usedToken)).await()
        if (response.code == 401 && authenticated && usedToken != null) {
            // Let any in-flight rotation finish, then retry if the token changed underneath us.
            refreshMutex.withLock { }
            val current = tokens.token
            if (current != null && current != usedToken) {
                response.close()
                response = http.newCall(buildRequest(method, path, query, body, current)).await()
            }
        }
        response.use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) {
                // Only expire the session if the token that failed is still the current one.
                if (res.code == 401 && authenticated && res.request.header("Authorization") == "Bearer ${tokens.token}") {
                    onSessionExpired()
                }
                throw ApiException(res.code, parseError(res.code, text))
            }
            // The hint is about the token this request carried; ignore it if that's already been rotated.
            val token = res.request.header("Authorization")?.removePrefix("Bearer ")
            if (authenticated && token != null && token == tokens.token &&
                res.header("X-Token-Refresh-Recommended") == "true" && rotatingFor.getAndSet(token) != token
            ) {
                scope.launch { runCatching { refreshSession(expected = token) } }
            }
            text
        }
    }

    private fun parseError(code: Int, text: String): String {
        val parsed = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        return parsed?.error ?: parsed?.message ?: when {
            code == 429 -> "Too many requests"
            text.isNotBlank() && text.length < 160 && !text.trimStart().startsWith("<") -> text.trim()
            else -> "Server error ($code)"
        }
    }

    private suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T {
        val text = raw("GET", path, query)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(text) }
    }

    private suspend inline fun <reified T> send(method: String, path: String, body: JsonObject? = null, query: Map<String, String?> = emptyMap()): T {
        val text = raw(method, path, query, body)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(text) }
    }

    // ---------------- session ----------------

    suspend fun createSession(code: String, redirectUri: String, codeVerifier: String, deviceName: String): SessionResponse {
        val text = raw("POST", "/session", body = buildJsonObject {
            put("code", code)
            put("redirect_uri", redirectUri)
            put("code_verifier", codeVerifier)
            put("device_name", deviceName)
        }, authenticated = false)
        return json.decodeFromString(SessionResponse.serializer(), text)
    }

    /**
     * Rotates the token. Serialized, and skipped if [expected] has already been rotated away:
     * the server revokes the old token immediately, so rotating twice would sign us out.
     */
    suspend fun refreshSession(expected: String? = tokens.token): SessionResponse? = refreshMutex.withLock {
        val current = tokens.token ?: return null
        if (expected != null && current != expected) return null
        val text = withContext(Dispatchers.IO) {
            http.newCall(buildRequest("POST", "/session/refresh", emptyMap(), null, current)).await().use { res ->
                if (!res.isSuccessful) null else res.body.string()
            }
        } ?: return null
        val session = json.decodeFromString(SessionResponse.serializer(), text)
        tokens.update(session.token, session.expiresAt)
        session
    }

    suspend fun deleteSession() { runCatching { raw("DELETE", "/session") } }

    suspend fun me(): User = get("/me")

    // ---------------- events ----------------

    suspend fun events(): List<Event> = get<EventsResponse>("/events").events

    suspend fun scanContexts(eventId: String): List<ScanContext> =
        get<ScanContextsResponse>("/events/$eventId/scan_contexts").scanContexts.sortedBy { it.position }

    // ---------------- scans ----------------

    suspend fun createScan(
        eventId: String,
        participantId: String? = null,
        badgeToken: String? = null,
        scanContextId: String? = null,
        source: String? = null,
        clientScanId: String,
        scannedAt: String,
    ): ScanResult = send("POST", "/events/$eventId/scans", buildJsonObject {
        participantId?.let { put("participant_id", it) }
        badgeToken?.let { put("badge_token", it) }
        scanContextId?.let { put("scan_context_id", it) }
        source?.let { put("source", it) }
        put("client_scan_id", clientScanId)
        put("scanned_at", scannedAt)
    })

    suspend fun scans(eventId: String, since: String? = null, scanContextId: String? = null): ScansResponse =
        get("/events/$eventId/scans", mapOf("since" to since, "scan_context_id" to scanContextId))

    /** Undo: deletes this participant's scans in one context, or in every context when [scanContextId] is null. */
    suspend fun undoScans(eventId: String, participantEventId: String, scanContextId: String? = null): UndoResult =
        send("DELETE", "/events/$eventId/scans/$participantEventId", query = mapOf("scan_context_id" to scanContextId))

    // ---------------- participants ----------------

    suspend fun participants(eventId: String, updatedSince: String? = null): ParticipantsResponse =
        get("/events/$eventId/participants", mapOf("updated_since" to updatedSince))

    suspend fun participant(eventId: String, participantEventId: String): Participant =
        get<ParticipantResponse>("/events/$eventId/participants/$participantEventId").participant

    suspend fun searchParticipants(eventId: String, q: String): List<Participant> =
        get<SearchResponse>("/events/$eventId/participants/search", mapOf("q" to q)).results

    suspend fun updateParticipantStatus(eventId: String, participantEventId: String, status: String): Participant =
        send<ParticipantResponse>("PATCH", "/events/$eventId/participants/$participantEventId", buildJsonObject {
            put("status", status)
        }).participant

    // ---------------- notes ----------------

    suspend fun notes(eventId: String, participantEventId: String): List<Note> =
        get<NotesResponse>("/events/$eventId/participants/$participantEventId/notes").notes

    suspend fun createNote(eventId: String, participantEventId: String, content: String, noteType: String, sensitivity: String): Note =
        send<NoteResponse>("POST", "/events/$eventId/participants/$participantEventId/notes", buildJsonObject {
            put("content", content)
            put("note_type", noteType)
            put("sensitivity", sensitivity)
        }).note

    // ---------------- NFC badges ----------------

    suspend fun nfcEnsure(eventId: String, participantEventId: String): NfcBadge =
        send("POST", "/events/$eventId/participant_events/$participantEventId/nfc_badge/ensure")

    suspend fun nfcConfirm(eventId: String, participantEventId: String, badgeToken: String): NfcBadge =
        send("POST", "/events/$eventId/participant_events/$participantEventId/nfc_badge/confirm", buildJsonObject {
            put("badge_token", badgeToken)
        })

    suspend fun nfcReset(eventId: String, participantEventId: String): NfcBadge =
        send("POST", "/events/$eventId/participant_events/$participantEventId/nfc_badge/reset")

    // ---------------- travel ----------------

    suspend fun travel(eventId: String): TravelCalendar = get("/events/$eventId/travel")

    // ---------------- slack blasts ----------------

    suspend fun slackBlasts(eventId: String): List<SlackBlast> =
        get<SlackBlastsResponse>("/events/$eventId/slack_blasts").slackBlasts

    suspend fun slackBlast(eventId: String, id: String): SlackBlast =
        get<SlackBlastResponse>("/events/$eventId/slack_blasts/$id").slackBlast

    suspend fun sendSlackBlast(eventId: String, message: String): SlackBlast =
        send<SlackBlastResponse>("POST", "/events/$eventId/slack_blasts", buildJsonObject { put("message", message) }).slackBlast

    // ---------------- tickets ----------------

    suspend fun tickets(): List<Ticket> = get<TicketsResponse>("/tickets").tickets

    suspend fun ticket(id: String): Ticket = get<TicketResponse>("/tickets/$id").ticket

    suspend fun googleWalletUrl(ticketId: String): String = get<UrlResponse>("/tickets/$ticketId/google_wallet").url
}

