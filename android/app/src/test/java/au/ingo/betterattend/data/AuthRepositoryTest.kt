package au.ingo.betterattend.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.auth.AuthRepository
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.auth.SecureTokenStore
import au.ingo.betterattend.data.auth.TokenIssueState
import au.ingo.betterattend.data.model.User
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** Copying a new mobile token: a second sign-in that must never touch the app's own session. */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private val server = MockWebServer()
    private val sessionRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val user = User(id = "u1", email = "a@b.c")
    private lateinit var store: SecureTokenStore
    private lateinit var auth: AuthRepository

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/api/v1/session" && request.method == "POST") {
                    sessionRequests += request
                    MockResponse.Builder().code(200)
                        .body("""{"token":"issued-token","expires_at":"2026-12-01T00:00:00Z","user":{"id":"u1","email":"a@b.c"}}""")
                        .build()
                } else MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
        }
        server.start()
        store = SecureTokenStore(ApplicationProvider.getApplicationContext())
        store.update("app-token", "2026-11-01T00:00:00Z")
        store.user = user
        val api = AttendApi(store, onSessionExpired = {}, baseUrl = server.url("/").toString().trimEnd('/'))
        auth = AuthRepository(store) { api }
    }

    @After fun tearDown() { server.close() }

    private fun redirectFor(authorize: Uri, extra: String = "code=c1") =
        Uri.parse("attend://oauth/callback?$extra&state=${authorize.getQueryParameter("state")}")

    @Test fun issuesAFreshTokenWithoutTheAppToken() = runBlocking {
        val authorize = auth.buildIssueTokenUri("  My laptop  ")
        assertNull(auth.handleCallback(redirectFor(authorize)))

        assertEquals(1, sessionRequests.size)
        val req = sessionRequests.single()
        assertNull(req.headers["Authorization"])
        val body = req.body!!.utf8()
        assertTrue(body, body.contains("\"device_name\":\"My laptop\""))
        assertTrue(body, body.contains("\"code\":\"c1\""))

        assertEquals(TokenIssueState.Issued("issued-token", user), auth.tokenIssue.value)
        assertEquals("app-token", store.token)
        assertEquals(AuthState.SignedIn(user), auth.state.value)
    }

    @Test fun blankNameFallsBackToDefault() = runBlocking {
        auth.handleCallback(redirectFor(auth.buildIssueTokenUri(" ")))
        val body = sessionRequests.single().body!!.utf8()
        assertTrue(body, body.contains("(BetterAttend, copied token)\""))
    }

    @Test fun duplicateRedirectExchangesOnce() = runBlocking {
        val redirect = redirectFor(auth.buildIssueTokenUri("x"))
        auth.handleCallback(redirect)
        auth.handleCallback(redirect)
        assertEquals(1, sessionRequests.size)
        assertEquals("app-token", store.token)
    }

    @Test fun plainCancelRacingTheRedirectStillIssues() = runBlocking {
        val authorize = auth.buildIssueTokenUri("x")
        // Custom Tab fallback: the tab reports "cancelled" around when the redirect arrives.
        auth.cancelled()
        auth.handleCallback(redirectFor(authorize))
        assertTrue(auth.tokenIssue.value is TokenIssueState.Issued)
    }

    @Test fun browserErrorReportsFailureAndForgetsTheRequest() = runBlocking {
        val authorize = auth.buildIssueTokenUri("x")
        auth.cancelled("Sign-in couldn't be verified. Please try again.")
        assertEquals(TokenIssueState.Failed("Sign-in couldn't be verified. Please try again."), auth.tokenIssue.value)
        auth.handleCallback(redirectFor(authorize))
        assertEquals(0, sessionRequests.size)
        assertEquals(AuthState.SignedIn(user), auth.state.value)
    }

    @Test fun declinedOnHackClubIsSilent() = runBlocking {
        auth.handleCallback(redirectFor(auth.buildIssueTokenUri("x"), extra = "error=access_denied"))
        assertEquals(TokenIssueState.Idle, auth.tokenIssue.value)
        assertEquals(0, sessionRequests.size)
        assertEquals(AuthState.SignedIn(user), auth.state.value)
    }

    @Test fun sessionExpiredMidFlowDropsTheRedirect() = runBlocking {
        val authorize = auth.buildIssueTokenUri("x")
        auth.sessionExpired()
        assertNull(auth.handleCallback(redirectFor(authorize)))
        assertEquals(0, sessionRequests.size)
        assertEquals(TokenIssueState.Idle, auth.tokenIssue.value)
        // The expiry message stays; the stray redirect isn't turned into a login error.
        assertEquals(AuthState.SignedOut("Your session expired. Please sign in again."), auth.state.value)
    }
}
