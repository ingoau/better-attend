package au.ingo.betterattend.data

import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ParticipantResponse
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
import java.util.concurrent.TimeUnit

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
    private val scannedAt = "2026-10-03T01:00:00Z"

    /** What Attend answers a scan with: "scanned" (first there) or "already_scanned". */
    @Volatile private var scanOutcome = "scanned"
    /** How long Attend takes to answer a scan. */
    @Volatile private var scanDelayMs = 0L
    /** Attend refuses scans (422). */
    @Volatile private var scanRefused = false
    /** Scans at the muster point Attend's fresh record of Mia shows (0 = none). */
    @Volatile private var scansThere = 1

    private lateinit var cache: JsonCache
    private lateinit var api: AttendApi
    private lateinit var participants: ParticipantRepository
    private lateinit var scans: ScanRepository

    private fun miaWith(count: Int) = mia.copy(
        scansByContext = if (count == 0) emptyList() else listOf(ContextScanSummary("muster", "Muster point", scanCount = count, firstScannedAt = scannedAt, lastScannedAt = scannedAt)),
    )

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body)

    @Before fun setUp() = runBlocking {
        SecureBox.injectForTesting(IdentityCipher)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/v1")
                requests += "${request.method} $path${request.url.query?.let { "?$it" } ?: ""} ${request.body?.utf8().orEmpty()}"
                return when {
                    request.method == "POST" && path == "/events/e1/scans" && scanRefused ->
                        MockResponse.Builder().code(422).body("""{"error":"Invalid scan context"}""").build()
                    request.method == "POST" && path == "/events/e1/scans" -> json(
                        AttendJson.encodeToString(
                            ScanResult.serializer(),
                            ScanResult(
                                outcome = scanOutcome, scan = Scan(id = "s1", scannedAt = scannedAt),
                                scanContext = ScanContextRef("muster", "Muster point"),
                                participant = miaWith(if (scanOutcome == "scanned") 1 else 2),
                            ),
                        ),
                    ).headersDelay(scanDelayMs, TimeUnit.MILLISECONDS).build()
                    request.method == "GET" && path == "/events/e1/participants/pe-mia" ->
                        json(AttendJson.encodeToString(ParticipantResponse.serializer(), ParticipantResponse(miaWith(scansThere)))).build()
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
        seedRoster(mia, leo)
        scans = ScanRepository(api, cache, participants, scope, scanTimeoutMillis = 400)
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
        SecureBox.resetForTesting()
    }

    private suspend fun seedRoster(vararg people: Participant) {
        cache.write("roster_e1", Roster.serializer(), Roster("e1", people.toList(), syncedAt = "2026-10-03T01:00:00Z", lastSyncAt = "2026-10-03T01:00:00Z"))
    }

    private fun repo() = RollCallRepository(cache, scans, participants, scope)

    private fun rollCall(recording: TickRecording) =
        RollCallLogic.start("e1", listOf(mia, leo), RollCallExpected.CheckedIn, recording, contexts)

    private suspend fun eventually(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }

    private fun posts() = requests.filter { it.startsWith("POST ") }
    private fun deletes() = requests.filter { it.startsWith("DELETE ") }
    private fun details() = requests.filter { it.startsWith("GET /events/e1/participants/pe-mia") }

    private fun RollCallRepository.rc(): RollCall = sessions.value["e1"]!!

    /** Starts recording at the muster point and ticks Mia, waiting until the tick's outcome is noted. */
    private suspend fun RollCallRepository.tickMia(settled: (RollCall) -> Boolean) {
        start(rollCall(TickRecording.AtScanPoint("muster"))).join()
        toggle("e1", "pe-mia").join()
        eventually { settled(rc()) }
    }

    /** Unticks Mia and waits until that's settled (nothing of hers left pending in the roll call). */
    private suspend fun RollCallRepository.untickMia() {
        toggle("e1", "pe-mia").join()
        eventually { rc().let { "pe-mia" !in it.recorded && "pe-mia" !in it.queued && "pe-mia" !in it.preExisting } }
        delay(150) // anything that would still be sent would be on its way
    }

    // ---------------------------------------------------------------- persistence

    @Test fun session_persists_acrossRepositoryInstances() = runBlocking {
        val first = repo()
        first.start(rollCall(TickRecording.PhoneOnly)).join()
        first.toggle("e1", "pe-mia").join()
        // A new process: the roll call comes back from the encrypted cache.
        val restored = repo().load("e1")
        assertNotNull(restored)
        assertEquals(listOf("pe-leo", "pe-mia"), restored!!.expectedIds)
        assertEquals(setOf("pe-mia"), restored.accounted.keys)
    }

    @Test fun end_clearsTheStoredSession() = runBlocking {
        val r = repo()
        r.start(rollCall(TickRecording.PhoneOnly)).join()
        r.end("e1").join()
        assertNull(r.load("e1"))
        assertNull(repo().load("e1"))
    }

    @Test fun withoutAKey_nothingIsSaved_butTheRollCallStillWorks() = runBlocking {
        SecureBox.injectForTesting(null)
        val r = repo()
        r.start(rollCall(TickRecording.PhoneOnly)).join()
        r.toggle("e1", "pe-mia").join()
        assertEquals(setOf("pe-mia"), r.rc().accounted.keys)
        assertNull(cache.read("rollcall_e1", RollCall.serializer()))
    }

    @Test fun phoneOnly_sendsNothing() = runBlocking {
        val r = repo()
        r.start(rollCall(TickRecording.PhoneOnly)).join()
        r.toggle("e1", "pe-mia").join()
        r.toggle("e1", "pe-mia").join()
        r.add("e1", "pe-x", "Walk-in").join()
        delay(300)
        assertTrue("no requests: $requests", requests.isEmpty())
    }

    @Test fun clear_stopsWork_andNothingIsRewrittenAfterSignOut() = runBlocking {
        scanDelayMs = 300 // the tick's scan is still on its way when the user signs out
        val r = repo()
        r.start(rollCall(TickRecording.AtScanPoint("muster"))).join()
        r.toggle("e1", "pe-mia").join()
        r.clear()
        delay(800)
        assertNull(cache.read("rollcall_e1", RollCall.serializer()))
        assertTrue(r.sessions.value.isEmpty())
    }

    // ---------------------------------------------------------------- tick → untick

    @Test fun confirmedFirstScan_untickRechecksAttend_thenTakesItBack() = runBlocking {
        val r = repo()
        r.tickMia { "pe-mia" in it.recorded }
        assertTrue(posts().single().contains("\"scan_context_id\":\"muster\""))
        assertTrue(posts().single().contains("\"participant_id\":\"pe-mia\""))
        r.untickMia()
        assertEquals(1, details().size)
        assertTrue(deletes().single().startsWith("DELETE /events/e1/scans/pe-mia?scan_context_id=muster"))
        assertTrue(r.rc().accounted.isEmpty())
        assertFalse("pe-mia" in r.rc().stillRecorded)
    }

    @Test fun untickAfterAlreadyScanned_neverDeletes() = runBlocking {
        scanOutcome = "already_scanned"
        val r = repo()
        r.tickMia { "pe-mia" in it.preExisting }
        r.untickMia()
        assertTrue("no undo: $requests", deletes().isEmpty())
        assertTrue("their earlier scan is shown as staying", "pe-mia" in r.rc().stillRecorded)
    }

    @Test fun untick_whenTheRosterAlreadyHadAScanThere_neverDeletes() = runBlocking {
        seedRoster(miaWith(1), leo)
        // Even if Attend (oddly) calls it a first scan, the roster said they'd been scanned there already.
        val r = repo()
        r.tickMia { "pe-mia" in it.preExisting }
        assertFalse("pe-mia" in r.rc().recorded)
        r.untickMia()
        assertTrue("no undo: $requests", deletes().isEmpty())
        assertTrue("pe-mia" in r.rc().stillRecorded)
    }

    @Test fun untick_whenAttendNowShowsTwoScans_keepsThemAndSaysSo() = runBlocking {
        val r = repo()
        r.tickMia { "pe-mia" in it.recorded }
        scansThere = 2 // someone else scanned her there since
        r.untickMia()
        assertEquals(1, details().size)
        assertTrue("no undo: $requests", deletes().isEmpty())
        assertTrue("pe-mia" in r.rc().stillRecorded)
        assertTrue(r.rc().accounted.isEmpty())
    }

    @Test fun untick_offline_keepsTheScanAndMarksIt() = runBlocking {
        val r = repo()
        r.tickMia { "pe-mia" in it.recorded }
        api.baseUrl = "http://127.0.0.1:1"
        r.untickMia()
        assertTrue("pe-mia" in r.rc().stillRecorded)
        // Ticking them again clears the marker.
        r.toggle("e1", "pe-mia").join()
        assertFalse("pe-mia" in r.rc().stillRecorded)
    }

    @Test fun queuedTick_isDiscarded_andNeverFollowedByAnUndo() = runBlocking {
        api.baseUrl = "http://127.0.0.1:1" // connection refused: the tick is queued
        val r = repo()
        r.tickMia { "pe-mia" in it.queued }
        assertEquals(1, scans.pending.value.size)
        api.baseUrl = server.url("/").toString().trimEnd('/') // back online before the untick
        r.untickMia()
        assertTrue(scans.pending.value.isEmpty())
        assertTrue("nothing sent: $requests", requests.isEmpty())
        assertFalse("pe-mia" in r.rc().stillRecorded)
    }

    @Test fun queuedTickThatSyncedMeanwhile_isKeptAndMarked() = runBlocking {
        api.baseUrl = "http://127.0.0.1:1"
        val r = repo()
        r.tickMia { "pe-mia" in it.queued }
        api.baseUrl = server.url("/").toString().trimEnd('/')
        assertEquals(0, scans.flush()) // it syncs before the untick runs
        r.untickMia()
        assertTrue("no undo: $requests", deletes().isEmpty())
        assertTrue("pe-mia" in r.rc().stillRecorded)
    }

    @Test fun slowSubmit_thenUntick_waitsForTheTick_andDropsTheQueuedScan() = runBlocking {
        scanDelayMs = 1_500 // longer than the 400 ms timeout: the tick ends up queued
        val r = repo()
        r.start(rollCall(TickRecording.AtScanPoint("muster"))).join()
        r.toggle("e1", "pe-mia").join()
        r.toggle("e1", "pe-mia").join() // untick while the tick's scan is still on its way
        assertTrue(r.rc().accounted.isEmpty())
        eventually { scans.log.value.isNotEmpty() } // the tick's submit gave up and queued it
        eventually { scans.pending.value.isEmpty() }
        delay(200)
        // The untick ran after the tick and took its queued scan back out: nothing left to sync while shown unticked.
        assertTrue(scans.pending.value.isEmpty())
        assertTrue("no undo of anyone else's scans: $requests", deletes().isEmpty())
        assertTrue(r.rc().queued.isEmpty())
    }

    @Test fun slowSubmitThatAnswers_thenUntick_isTakenBackOnlyAfterTheRecheck() = runBlocking {
        scans = ScanRepository(api, cache, participants, scope, scanTimeoutMillis = 4_000)
        scanDelayMs = 300 // slow, but inside the timeout
        val r = repo()
        r.start(rollCall(TickRecording.AtScanPoint("muster"))).join()
        r.toggle("e1", "pe-mia").join()
        r.toggle("e1", "pe-mia").join()
        eventually { deletes().isNotEmpty() }
        val order = requests.map { it.substringBefore(' ') + " " + it.substringAfter(' ').substringBefore('?').substringBefore(' ') }
        assertEquals(listOf("POST /events/e1/scans", "GET /events/e1/participants/pe-mia", "DELETE /events/e1/scans/pe-mia"), order)
        assertTrue(scans.pending.value.isEmpty())
    }

    @Test fun refusedTick_isShownAsNotRecorded_andUntickSendsNothing() = runBlocking {
        scanRefused = true
        val r = repo()
        r.tickMia { "pe-mia" in it.notRecorded }
        assertTrue(RollCallLogic.allRows(r.rc(), participants.roster("e1")).first { it.id == "pe-mia" }.notRecorded)
        r.toggle("e1", "pe-mia").join()
        delay(200)
        assertTrue(deletes().isEmpty())
        assertFalse("pe-mia" in r.rc().notRecorded)
        assertFalse("pe-mia" in r.rc().stillRecorded)
    }
}
