package au.ingo.betterattend.smoke

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import au.ingo.betterattend.AttendApp
import au.ingo.betterattend.MainActivity
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.*
import au.ingo.betterattend.ui.preview.SampleData
import kotlinx.serialization.KSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Boots the real app (AttendApp + MainActivity + navigation) against a local mock of the Attend
 * API and walks every tab and the main detail screens. Catches runtime crashes that screenshot
 * tests of isolated composables can't.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = AttendApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppSmokeTest {
    @get:Rule val compose: ComposeTestRule = createEmptyComposeRule()

    private val server = MockWebServer()
    private val requests = mutableListOf<String>()
    private val app get() = ApplicationProvider.getApplicationContext<AttendApp>()

    private val now = Instant.now()
    private val liveEvent = SampleData.event.copy(
        startsAt = now.minus(3, ChronoUnit.HOURS).toString(),
        endsAt = now.plus(30, ChronoUnit.HOURS).toString(),
    )

    private fun <T> json(ser: KSerializer<T>, value: T) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json").body(AttendJson.encodeToString(ser, value)).build()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val eid = liveEvent.id
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/v1")
                synchronized(requests) { requests += "${request.method} $path" }
                val detailId = SampleData.participantDetail.participantEventId
                return when {
                    path == "/me" -> json(User.serializer(), SampleData.user)
                    path == "/events" -> json(EventsResponse.serializer(), EventsResponse(listOf(liveEvent) + SampleData.events.drop(1)))
                    path == "/tickets" -> json(TicketsResponse.serializer(), TicketsResponse(SampleData.tickets))
                    path.startsWith("/tickets/") -> json(TicketResponse.serializer(), TicketResponse(SampleData.ticket))
                    path == "/events/$eid/scan_contexts" -> json(ScanContextsResponse.serializer(), ScanContextsResponse(SampleData.contexts))
                    path == "/events/$eid/participants" -> json(ParticipantsResponse.serializer(), ParticipantsResponse(SampleData.participants, "2026-10-03T01:30:00.123456Z"))
                    path == "/events/$eid/participants/$detailId" -> json(ParticipantResponse.serializer(), ParticipantResponse(SampleData.participantDetail))
                    path.endsWith("/notes") -> json(NotesResponse.serializer(), NotesResponse(SampleData.notes))
                    path == "/events/$eid/travel" -> json(TravelCalendar.serializer(), SampleData.travel)
                    path == "/events/$eid/scans" && request.method == "POST" -> json(ScanResult.serializer(), ScanResult(
                        outcome = "scanned", firstScanInContext = true, firstScannedAt = now.toString(),
                        scan = Scan(id = "s1", scannedAt = now.toString()),
                        scanContext = ScanContextRef("c1", "Check-in desk", checksIn = true),
                        participant = SampleData.participants[0].copy(checkedInAt = now.toString()),
                    ))
                    path == "/events/$eid/scans" -> json(ScansResponse.serializer(), ScansResponse(emptyList(), false, now.toString()))
                    path == "/events/$eid/slack_blasts" -> json(SlackBlastsResponse.serializer(), SlackBlastsResponse(SampleData.blasts))
                    else -> MockResponse.Builder().code(404).body("{\"error\":\"Not found\"}").build()
                }
            }
        }
        server.start()
        app.container.api.baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() { server.close() }

    private fun waitForText(text: String, timeout: Long = 10_000) {
        try {
            compose.waitUntil(timeout) {
                compose.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // Leave evidence: what was on screen and what the app asked the server for.
            runCatching { compose.onRoot().captureRoboImage("build/outputs/roborazzi/smoke_failure.png") }
            throw AssertionError("'$text' never appeared. Requests: ${synchronized(requests) { requests.toList() }}", e)
        }
    }

    private fun assertSelected(label: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tab(label: String) =
        compose.onAllNodes(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).onFirst()

    @Test fun signedOut_showsLogin() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Sign in with Hack Club")
        }
    }

    @Test fun signedIn_organizer_walksEveryTab() {
        app.container.auth.signInWith(SessionResponse("test-token", now.plus(14, ChronoUnit.DAYS).toString(), SampleData.user))
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            // Home dashboard with live counts from the mock roster.
            waitForText("Campfire Sydney")
            waitForText("checked in")

            tab("People").performClick()
            waitForText("Search name, email or code")
            compose.onAllNodes(hasSetTextAction() and hasText("Search name, email or code")).onFirst().performTextInput("Maya")
            waitForText("Maya")
            compose.onAllNodes(hasText("she/her", substring = true) and hasClickAction()).onFirst().performClick()
            waitForText("Peanuts") // participant detail with sensitive alerts
            withActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()

            tab("Travel").performClick()
            waitForText("Search name, route or flight")

            tab("Scan").performClick()
            waitForText("Find person")
            // Manual check-in: Find person → search → Check in → result card.
            compose.onAllNodesWithText("Find person", useUnmergedTree = true).onFirst().performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            // The sheet's field is the newest one (all tab pages stay composed, so match the last).
            compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("Leo")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Check in", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Check in", useUnmergedTree = true).onFirst().performClick()
            waitForText("Scanned")
            synchronized(requests) { check(requests.any { it == "POST /events/${liveEvent.id}/scans" }) { "scan never posted: $requests" } }

            tab("Tickets").performClick()
            waitForText("Campfire Sydney")

            tab("Home").performClick()
            waitForText("checked in")
            assertSelected("Home")

            // Swipe between tabs: Home → Scan → People.
            compose.onRoot().performTouchInput { swipeLeft(startX = right * 0.9f, endX = left + right * 0.1f) }
            compose.waitForIdle()
            assertSelected("Scan")
            compose.onRoot().performTouchInput { swipeLeft(startX = right * 0.9f, endX = left + right * 0.1f) }
            compose.waitForIdle()
            assertSelected("People")

            // System back from a tab returns to Home instead of leaving the app.
            withActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertSelected("Home")

            // Detail screens push over the tabs and back returns to the same tab.
            tab("People").performClick()
            compose.waitForIdle()
            val accounts = compose.onAllNodes(hasContentDescription("Account and settings"))
            val visible = (0 until accounts.fetchSemanticsNodes().size).first { i -> runCatching { accounts[i].assertIsDisplayed() }.isSuccess }
            accounts[visible].performClick()
            waitForText("Appearance")
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()

            // Predictive back: drag Settings halfway, capture the frame, then release.
            withActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(10f, 800f, 0f, BackEventCompat.EDGE_LEFT)) }
            for (step in 1..5) {
                withActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(10f + step * 60f, 800f, step * 0.1f, BackEventCompat.EDGE_LEFT)) }
                compose.waitForIdle()
            }
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/smoke_predictive_back_mid.png")
            withActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertSelected("People")
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/smoke_predictive_back_done.png")
        }
        synchronized(requests) {
            check(requests.any { it == "GET /events" }) { "events never requested: $requests" }
            check(requests.any { it.startsWith("GET /events/${liveEvent.id}/participants") }) { "roster never synced: $requests" }
        }
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        scenario?.onActivity(block)
    }

    private var scenario: ActivityScenario<MainActivity>? = null
}
