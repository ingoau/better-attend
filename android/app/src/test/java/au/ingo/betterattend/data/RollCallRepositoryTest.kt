package au.ingo.betterattend.data

import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.ScanContextRef
import au.ingo.betterattend.data.model.ScanResult
import au.ingo.betterattend.data.repo.ParticipantRepository
import au.ingo.betterattend.data.repo.RollCall
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.data.repo.RollCallRepository
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.repo.ScanRepository
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SecureBox
import au.ingo.betterattend.ui.rollcall.RollCallLogic
import au.ingo.betterattend.ui.rollcall.TickRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/** Roll call persistence and how ticks become scans (or don't) against a mock Attend. */
@RunWith(RobolectricTestRunner::class)
class RollCallRepositoryTest {
    private val server = MockWebServer()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokens = object : TokenStore {
        override val token: String = "t"
        override fun update(token: String?, expiresAt: String?) {}
    }

    private val mia = Participant("aaaaaaa1-0000-4000-8000-000000000000", "pe-mia", displayName = "Mia", status = "complete", waiverSigned = true, checkedInAt = "2026-10-03T00:00:00Z")
    private val leo = Participant("aaaaaaa2-0000-4000-8000-000000000000", "pe-leo", displayName = "Leo", status = "complete", waiverSigned = true, checkedInAt = "2026-10-03T00:00:00Z")
    private val contexts = listOf(ScanContext("desk", "Check-in desk", checksIn = true), ScanContext("muster", "Muster point"))

    private lateinit var cache: JsonCache
    private lateinit var api: AttendApi
    private lateinit var participants: ParticipantRepository
    private lateinit var scans: ScanRepository

    @Before fun setUp() = runBlocking {
        SecureBox.injectForTesting(IdentityCipher)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/v1")
                requests += "${request.method} $path${request.url.query?.let { "?$it" } ?: ""} ${request.body?.utf8().orEmpty()}"
                return when {
                    request.method == "POST" && path == "/events/e1/scans" -> MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body(AttendJson.encodeToString(ScanResult.serializer(), ScanResult(outcome = "scanned", scan = Scan(id = "s1", scannedAt = "2026-10-03T01:00:00Z"),
                            scanContext = ScanContextRef("muster", "Muster point"), participant = mia))).build()
                    request.method == "DELETE" -> MockResponse.Builder().code(200).body("""{"success":true,"deleted_scans":1}""").build()
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
        cache = JsonCache(ApplicationProvider.getApplicationContext())
        cache.clear()
        api = AttendApi(tokens, onSessionExpired = {}, baseUrl = server.url("/").toString().trimEnd('/'))
        participants = ParticipantRepository(api, cache)
        cache.write("roster_e1", Roster.serializer(), Roster("e1", listOf(mia, leo), syncedAt = "2026-10-03T01:00:00Z", lastSyncAt = "2026-10-03T01:00:00Z"))
        scans = ScanRepository(api, cache, participants, scope, scanTimeoutMillis = 400)
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
        SecureBox.resetForTesting()
    }

    private fun repo() = RollCallRepository(cache, scans, participants, scope)

    private fun rollCall(recording: TickRecording) =
        RollCallLogic.start("e1", listOf(mia, leo), RollCallExpected.CheckedIn, recording, contexts)

    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }

    private fun posts() = requests.filter { it.startsWith("POST ") }

    @Test fun session_persists_acrossRepositoryInstances() = runBlocking {
        val first = repo()
        first.start(rollCall(TickRecording.PhoneOnly))
        first.toggle("e1", "pe-mia")
        // A new process: the roll call comes back from the encrypted cache.
        val restored = repo().load("e1")
        assertNotNull(restored)
        assertEquals(listOf("pe-leo", "pe-mia"), restored!!.expectedIds)
        assertEquals(setOf("pe-mia"), restored.accounted.keys)
    }

    @Test fun end_clearsTheStoredSession() = runBlocking {
        val r = repo()
        r.start(rollCall(TickRecording.PhoneOnly))
        r.end("e1")
        assertNull(r.load("e1"))
        assertNull(repo().load("e1"))
    }

    @Test fun withoutAKey_nothingIsSaved_butTheRollCallStillWorks() = runBlocking {
        SecureBox.injectForTesting(null)
        val r = repo()
        r.start(rollCall(TickRecording.PhoneOnly))
        r.toggle("e1", "pe-mia")
        assertEquals(setOf("pe-mia"), r.sessions.value["e1"]!!.accounted.keys)
        assertNull(cache.read("rollcall_e1", RollCall.serializer()))
    }

    @Test fun phoneOnly_sendsNothing() = runBlocking {
        val r = repo()
        r.start(rollCall(TickRecording.PhoneOnly))
        r.toggle("e1", "pe-mia")
        r.toggle("e1", "pe-mia")
        r.add("e1", "pe-x", "Walk-in")
        delay(300)
        assertTrue("no requests: $requests", requests.isEmpty())
    }

    @Test fun atScanPoint_tickRecordsAScanThere_untickUndoesIt() = runBlocking {
        val r = repo()
        r.start(rollCall(TickRecording.AtScanPoint("muster")))
        r.toggle("e1", "pe-mia")
        eventually { posts().isNotEmpty() }
        assertTrue(posts().single().contains("\"scan_context_id\":\"muster\""))
        assertTrue(posts().single().contains("\"participant_id\":\"pe-mia\""))
        r.toggle("e1", "pe-mia")
        eventually { requests.any { it.startsWith("DELETE /events/e1/scans/pe-mia?scan_context_id=muster") } }
        assertTrue(r.sessions.value["e1"]!!.accounted.isEmpty())
    }

    @Test fun offline_tickQueues_andAnUntickDropsItBeforeItSyncs() = runBlocking {
        api.baseUrl = "http://127.0.0.1:1" // connection refused
        val r = repo()
        r.start(rollCall(TickRecording.AtScanPoint("muster")))
        r.toggle("e1", "pe-mia")
        eventually { scans.pending.value.isNotEmpty() }
        eventually { r.sessions.value["e1"]!!.queued.containsKey("pe-mia") }
        r.toggle("e1", "pe-mia")
        eventually { scans.pending.value.isEmpty() }
        assertFalse(r.sessions.value["e1"]!!.stillRecorded.contains("pe-mia"))
    }

    @Test fun offline_untickOfARecordedScan_marksItStillRecorded() = runBlocking {
        val r = repo()
        r.start(rollCall(TickRecording.AtScanPoint("muster")))
        r.toggle("e1", "pe-mia")
        eventually { posts().isNotEmpty() }
        delay(100)
        api.baseUrl = "http://127.0.0.1:1"
        r.toggle("e1", "pe-mia")
        eventually { r.sessions.value["e1"]!!.stillRecorded.contains("pe-mia") }
        // Ticking them again clears the marker.
        r.toggle("e1", "pe-mia")
        assertFalse(r.sessions.value["e1"]!!.stillRecorded.contains("pe-mia"))
    }
}
