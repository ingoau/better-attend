package au.ingo.betterattend.data

import au.ingo.betterattend.data.api.ApiException
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.TokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AttendApiTest {
    private val server = MockWebServer()
    private val refreshes = AtomicInteger()
    private var expired = false

    private val tokens = object : TokenStore {
        @Volatile override var token: String? = "old"
        override fun update(token: String?, expiresAt: String?) { this.token = token }
    }

    private val me = """{"id":"u1","email":"a@b.c"}"""

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.headers["Authorization"]
                return when (request.url.encodedPath) {
                    "/api/v1/session/refresh" -> {
                        refreshes.incrementAndGet()
                        if (auth == "Bearer old") MockResponse.Builder().code(200)
                            .body("""{"token":"new","expires_at":"2026-12-01T00:00:00Z","user":$me}""").build()
                        else MockResponse.Builder().code(401).body("""{"error":"Unauthorized"}""").build()
                    }
                    "/api/v1/me" -> when (auth) {
                        "Bearer new" -> MockResponse.Builder().code(200).body(me).build()
                        // Old token: still valid until rotated; ask the client to refresh.
                        "Bearer old" -> if (refreshes.get() == 0) MockResponse.Builder().code(200)
                            .addHeader("X-Token-Refresh-Recommended", "true").body(me).build()
                            else MockResponse.Builder().code(401).body("""{"error":"Unauthorized"}""").build()
                        else -> MockResponse.Builder().code(401).body("""{"error":"Unauthorized"}""").build()
                    }
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.close() }

    private fun api() = AttendApi(tokens, onSessionExpired = { expired = true }, baseUrl = server.url("/").toString().trimEnd('/'))

    @Test fun parallelRefreshHints_rotateOnlyOnce_andNobodyIsSignedOut() = runBlocking {
        val api = api()
        (1..8).map { async { runCatching { api.me() } } }.awaitAll()
        // Let the background rotation finish.
        repeat(50) { if (tokens.token == "new") return@repeat; delay(20) }
        assertEquals("new", tokens.token)
        assertEquals(1, refreshes.get())
        // A request still holding the old token gets a 401, waits for rotation and retries.
        assertEquals("u1", api.me().id)
        assertFalse(expired)
    }

    @Test fun genuine401_expiresSession() = runBlocking {
        tokens.token = "revoked"
        val api = api()
        val result = runCatching { api.me() }
        assertTrue(result.exceptionOrNull() is ApiException)
        assertTrue(expired)
    }
}
