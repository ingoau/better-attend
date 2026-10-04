package au.ingo.betterattend.data

import androidx.test.core.app.ApplicationProvider
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.api.TokenStore
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.ScanContextRef
import au.ingo.betterattend.data.model.ScanResult
import au.ingo.betterattend.data.repo.ParticipantRepository
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanOutcome
import au.ingo.betterattend.data.repo.ScanRejection
import au.ingo.betterattend.data.repo.ScanRepository
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.data.store.SecureBox
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.ui.scan.ResultKind
import au.ingo.betterattend.ui.scan.serverRejection
import au.ingo.betterattend.ui.scan.toCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit

/** The scan pipeline against a mock Attend: server verdicts, the 5 s timeout, offline pre-checks and flush rejections. */
@RunWith(RobolectricTestRunner::class)
class ScanRepositoryTest {
    private val server = MockWebServer()
    private val requests: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var scanResponse: () -> MockResponse = { ok(scanned(mia)) }
    private var undoResponse: () -> MockResponse = { MockResponse.Builder().code(200).body("""{"success":true,"deleted_scans":1}""").build() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val tokens = object : TokenStore {
        override val token: String = "t"
        override fun update(token: String?, expiresAt: String?) {}
    }

    private val mia = Participant(
        participantId = "aaaaaaa1-0000-4000-8000-000000000000",
        participantEventId = "bbbbbbb1-0000-4000-8000-000000000000",
        displayName = "Mia", fullName = "Mia Chen", status = "complete", waiverSigned = true,
    )
    private val other = mia.copy(participantId = "aaaaaaa9-0000-4000-8000-000000000000", participantEventId = "bbbbbbb9-0000-4000-8000-000000000000", displayName = "Ollie")
    private val desk = ScanContextRef("desk", "Check-in desk", checksIn = true)

    private lateinit var cache: JsonCache
    private lateinit var api: AttendApi
    private lateinit var participants: ParticipantRepository

    private fun ok(result: ScanResult) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body(AttendJson.encodeToString(ScanResult.serializer(), result)).build()

    private fun scanned(p: Participant, outcome: String = "scanned") = ScanResult(
        outcome = outcome, firstScannedAt = "2026-10-04T00:00:00Z", scan = Scan(id = "s1", scannedAt = "2026-10-04T00:00:00Z"),
        scanContext = desk, participant = p,
    )

    @Before fun setUp() = runBlocking {
        SecureBox.injectForTesting(IdentityCipher)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/v1")
                requests += "${request.method} $path${request.url.query?.let { "?$it" } ?: ""}" to (request.body?.utf8() ?: "")
                return when {
                    request.method == "POST" && path == "/events/e1/scans" -> scanResponse()
                    request.method == "DELETE" -> undoResponse()
                    else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                }
            }
        }
        server.start()
        cache = JsonCache(ApplicationProvider.getApplicationContext())
        cache.clear()
        api = AttendApi(tokens, onSessionExpired = {}, baseUrl = server.url("/").toString().trimEnd('/'))
        participants = ParticipantRepository(api, cache)
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
        SecureBox.resetForTesting()
    }

    private suspend fun seedRoster(vararg people: Participant, syncedAt: String = Instant.now().minusSeconds(14 * 60).toString()) {
        cache.write("roster_e1", Roster.serializer(), Roster("e1", people.toList(), syncedAt = syncedAt, lastSyncAt = syncedAt))
    }

    private fun repo(timeout: Long = 400) = ScanRepository(api, cache, participants, scope, scanTimeoutMillis = timeout)

    private fun offline() { api.baseUrl = "http://127.0.0.1:1" } // connection refused

    private val qrMia = ScanInput(participantId = mia.participantId)

    // ---------------------------------------------------------------- online

    @Test fun online_serverConfirms_isFullSuccess() = runBlocking {
        seedRoster(mia)
        val outcome = repo().submit("e1", qrMia, "desk", "Check-in desk")
        assertTrue(outcome is ScanOutcome.Scanned)
        assertFalse(outcome.isServerRejection)
        assertEquals(ResultKind.Scanned, outcome.toCard("k", null, null, null, qrMia).kind)
    }

    @Test fun online_serverRecordSaysWithdrawn_rejectsAndTakesTheScanBack() = runBlocking {
        seedRoster(mia) // the cache thinks she's fine…
        scanResponse = { ok(scanned(mia.copy(status = "withdrawn"))) } // …the server knows better
        val outcome = repo().submit("e1", qrMia, "desk", "Check-in desk")

        outcome as ScanOutcome.Rejected
        assertEquals(RejectReason.Withdrawn, outcome.reason)
        assertFalse(outcome.offline)
        assertTrue("the scan Attend recorded is undone", outcome.reverted)
        assertTrue(requests.any { it.first == "DELETE /events/e1/scans/${mia.participantEventId}?scan_context_id=desk" })
        assertTrue(outcome.isServerRejection)

        val alert = outcome.serverRejection("e1", "Check-in desk", "2026-10-04T00:00:00Z")!!
        assertEquals("Mia: registration withdrawn", alert.headline)
        assertEquals(mia.participantEventId, alert.participantEventId)
        val card = outcome.toCard("k", null, null, null, qrMia)
        assertEquals(ResultKind.Rejected, card.kind)
        assertEquals("Withdrawn", card.title)
    }

    @Test fun online_missingConsent_rejectedOnlyAtCheckIn() = runBlocking {
        seedRoster(mia)
        scanResponse = { ok(scanned(mia.copy(waiverSigned = false))) }
        val atDesk = repo().submit("e1", qrMia, "desk", "Check-in desk", checksIn = true)
        assertEquals(RejectReason.ConsentMissing, (atDesk as ScanOutcome.Rejected).reason)

        scanResponse = { ok(scanned(mia.copy(waiverSigned = false)).copy(scanContext = ScanContextRef("lunch", "Lunch"))) }
        val atLunch = repo().submit("e1", qrMia, "lunch", "Lunch", checksIn = false)
        assertTrue(atLunch is ScanOutcome.Scanned)
    }

    @Test fun online_alreadyScannedButWithdrawn_keepsEarlierScans() = runBlocking {
        seedRoster(mia)
        scanResponse = { ok(scanned(mia.copy(status = "withdrawn"), outcome = "already_scanned")) }
        val outcome = repo().submit("e1", qrMia, "desk", "Check-in desk") as ScanOutcome.Rejected
        assertFalse(outcome.reverted)
        assertTrue(requests.none { it.first.startsWith("DELETE") })
    }

    @Test fun online_serverRefuses_isARejectionToInterruptFor() = runBlocking {
        seedRoster(mia)
        scanResponse = { MockResponse.Builder().code(422).body("""{"error":"Invalid scan context"}""").build() }
        val scans = repo()
        val outcome = scans.submit("e1", qrMia, "desk", "Check-in desk")
        outcome as ScanOutcome.Failed
        assertEquals(422, outcome.status)
        assertTrue(outcome.isServerRejection)
        assertEquals("Mia: Invalid scan context", outcome.serverRejection("e1", null, "t")!!.headline)
        assertTrue(scans.pending.value.isEmpty())
    }

    @Test fun online_timeout_isTreatedAsOffline_andKeepsTheScanTime() = runBlocking {
        seedRoster(mia)
        scanResponse = { ok(scanned(mia)).newBuilder().headersDelay(10, TimeUnit.SECONDS).build() }
        val scans = repo(timeout = 300)
        val started = System.nanoTime()
        val outcome = scans.submit("e1", qrMia, "desk", "Check-in desk")
        assertTrue("gave up at the timeout", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 8_000)

        outcome as ScanOutcome.Queued
        assertEquals("Attend took too long to respond.", outcome.reason)
        assertNotNull(outcome.rosterAt)
        val card = outcome.toCard("k", null, null, null, qrMia)
        assertEquals("Offline, will confirm later", card.message)
        assertEquals("Roster from 14 min ago", card.rosterNote)
        assertFalse(card.rosterStale)

        // Back online: the flush sends the original scan time, not the time it synced.
        scanResponse = { ok(scanned(mia)) }
        assertEquals(0, scans.flush())
        val sent = requests.filter { it.first == "POST /events/e1/scans" }.last().second
        val body = Json.parseToJsonElement(sent).jsonObject
        assertEquals(outcome.pending.scannedAt, body["scanned_at"]!!.jsonPrimitive.content)
        assertEquals(outcome.clientScanId, body["client_scan_id"]!!.jsonPrimitive.content)
    }

    @Test fun timeout_butRosterSaysNo_rejectsLocallyWithoutQueueing() = runBlocking {
        seedRoster(mia.copy(status = "withdrawn"))
        scanResponse = { ok(scanned(mia)).newBuilder().headersDelay(10, TimeUnit.SECONDS).build() }
        val scans = repo(timeout = 300)
        val outcome = scans.submit("e1", qrMia, "desk", "Check-in desk") as ScanOutcome.Rejected
        assertEquals(RejectReason.Withdrawn, outcome.reason)
        assertTrue(outcome.offline)
        assertFalse("an offline pre-check isn't the server's verdict", outcome.isServerRejection)
        assertTrue("not a check-in waiting to sync", scans.pending.value.isEmpty())

        // The timed-out request may have been recorded anyway: the next flush replays it (same
        // client_scan_id, so Attend finds the original) and undoes it, without a second alert.
        scanResponse = { ok(scanned(mia.copy(status = "withdrawn"))) }
        assertEquals(0, scans.flush())
        val posts = requests.filter { it.first == "POST /events/e1/scans" }
        assertEquals(outcome.clientScanId, Json.parseToJsonElement(posts.last().second).jsonObject["client_scan_id"]!!.jsonPrimitive.content)
        assertTrue(requests.any { it.first == "DELETE /events/e1/scans/${mia.participantEventId}?scan_context_id=desk" })
        assertTrue(scans.rejections.value.isEmpty())
    }

    @Test fun offlineRejectionWithoutATimeout_needsNoUndo() = runBlocking {
        // Connection refused: the request never left, so there's nothing to take back.
        seedRoster(mia.copy(status = "withdrawn"))
        offline()
        val scans = repo()
        scans.submit("e1", qrMia, "desk", "Desk") as ScanOutcome.Rejected
        api.baseUrl = server.url("/").toString().trimEnd('/')
        assertEquals(0, scans.flush())
        assertTrue(requests.isEmpty())
    }

    @Test fun checkInFromTheirPage_overridesTheAdmissionRules() = runBlocking {
        // A paper waiver signed at the desk: staff check them in from their page anyway.
        seedRoster(mia)
        scanResponse = { ok(scanned(mia.copy(waiverSigned = false))) }
        val outcome = repo().submit("e1", ScanInput(participantId = mia.participantEventId, source = "manual"), "desk", "Desk", enforceAdmission = false)
        assertTrue(outcome is ScanOutcome.Scanned)
        assertTrue(requests.none { it.first.startsWith("DELETE") })
    }

    @Test fun verdictUsesTheServersRecordNeverTheCache() = runBlocking {
        // Reinstated on Attend; the cache still says withdrawn; the response carries no participant.
        seedRoster(mia.copy(status = "withdrawn"))
        scanResponse = { ok(scanned(mia).copy(participant = null)) }
        val outcome = repo().submit("e1", qrMia, "desk", "Desk")
        assertTrue(outcome is ScanOutcome.Scanned)
        assertTrue(requests.none { it.first.startsWith("DELETE") })
    }

    @Test fun failedUndo_saysTheScanIsStillRecorded() = runBlocking {
        seedRoster(mia)
        scanResponse = { ok(scanned(mia.copy(status = "withdrawn"))) }
        undoResponse = { MockResponse.Builder().code(500).body("""{"error":"boom"}""").build() }
        val outcome = repo().submit("e1", qrMia, "desk", "Desk") as ScanOutcome.Rejected
        assertFalse(outcome.reverted)
        assertTrue(outcome.stillRecorded)
        assertTrue(outcome.serverRejection("e1", "Desk", "t")!!.stillRecorded)
        assertTrue(outcome.toCard("k", null, null, null, qrMia).message!!.contains("Still recorded on Attend"))
    }

    @Test fun flush_usesTheCheckpointsOwnCheckInFlag() = runBlocking {
        // Lunch, offline, waiver unsigned: fine. On sync the response lacks scan_context; the queued flag decides.
        seedRoster(mia.copy(waiverSigned = false))
        offline()
        val scans = repo()
        scans.submit("e1", qrMia, "lunch", "Lunch", checksIn = false) as ScanOutcome.Queued
        api.baseUrl = server.url("/").toString().trimEnd('/')
        scanResponse = { ok(scanned(mia.copy(waiverSigned = false)).copy(scanContext = null)) }
        assertEquals(0, scans.flush())
        assertTrue(scans.rejections.value.isEmpty())
        assertTrue(requests.none { it.first.startsWith("DELETE") })
    }

    // ---------------------------------------------------------------- offline pre-checks

    @Test fun offline_preChecks_rejectLocally() = runBlocking {
        seedRoster(
            mia,
            other.copy(status = "withdrawn"),
            other.copy(participantId = "aaaaaaa2-0000-4000-8000-000000000000", participantEventId = "bbbbbbb2-0000-4000-8000-000000000000", waiverSigned = false),
        )
        offline()
        val scans = repo()
        scans.otherRosters = { mapOf("Campfire Sydney" to Roster("e2", listOf(mia.copy(participantId = "ddddddd1-0000-4000-8000-000000000000")), syncedAt = "x")) }

        suspend fun reason(id: String) = (scans.submit("e1", ScanInput(participantId = id), "desk", "Check-in desk") as ScanOutcome.Rejected).reason
        assertEquals(RejectReason.Withdrawn, reason(other.participantId))
        assertEquals(RejectReason.ConsentMissing, reason("aaaaaaa2-0000-4000-8000-000000000000"))
        assertEquals(RejectReason.WrongEvent, reason("ddddddd1-0000-4000-8000-000000000000"))
        assertEquals(RejectReason.NotRegistered, reason("eeeeeee1-0000-4000-8000-000000000000"))
        assertTrue("nothing that failed a pre-check is queued", scans.pending.value.isEmpty())

        // Passes → queued; scanning the same ticket again offline → already checked in, not queued twice.
        assertTrue(scans.submit("e1", qrMia, "desk", "Check-in desk") is ScanOutcome.Queued)
        val again = scans.submit("e1", qrMia, "desk", "Check-in desk") as ScanOutcome.Rejected
        assertEquals(RejectReason.AlreadyCheckedIn, again.reason)
        assertEquals(ResultKind.AlreadyScanned, again.toCard("k", null, null, null, qrMia).kind)
        assertEquals(1, scans.pending.value.size)
    }

    @Test fun offline_oldRoster_makesTheWarningProminent() = runBlocking {
        seedRoster(mia, syncedAt = Instant.now().minusSeconds(3 * 3600).toString())
        offline()
        val card = repo().submit("e1", qrMia, "desk", "Check-in desk").toCard("k", null, null, null, qrMia)
        assertEquals(ResultKind.SavedOffline, card.kind)
        assertEquals("Roster from 3 h ago", card.rosterNote)
        assertTrue(card.rosterStale)
    }

    // ---------------------------------------------------------------- flush

    @Test fun flush_permanentRejection_isSurfacedNotJustLogged() = runBlocking {
        seedRoster(mia)
        offline()
        val scans = repo()
        val notified = mutableListOf<ScanRejection>()
        scans.onRejected = { notified += it }
        scans.submit("e1", qrMia, "desk", "Check-in desk") as ScanOutcome.Queued

        api.baseUrl = server.url("/").toString().trimEnd('/')
        scanResponse = { MockResponse.Builder().code(404).body("""{"error":"Participant not found for this event"}""").build() }
        assertEquals(0, scans.flush())

        val r = scans.rejections.value.single()
        assertEquals("Mia: not registered for this event", r.headline)
        assertEquals(mia.participantEventId, r.participantEventId)
        assertEquals("Check-in desk", r.contextName)
        assertEquals(listOf(r), notified)

        // Persisted (encrypted cache): still there after a restart, until dismissed.
        val restarted = repo().also { it.flush() } // flush waits for the saved state to load
        assertEquals(listOf(r), restarted.rejections.value)
        restarted.dismissRejections()
        assertTrue(restarted.rejections.value.isEmpty())
        assertTrue(repo().also { it.flush() }.rejections.value.isEmpty())
    }

    @Test fun flush_acceptedButWithdrawnSince_isRejectedAndUndone() = runBlocking {
        seedRoster(mia)
        offline()
        val scans = repo()
        scans.submit("e1", qrMia, "desk", "Check-in desk") as ScanOutcome.Queued

        api.baseUrl = server.url("/").toString().trimEnd('/')
        scanResponse = { ok(scanned(mia.copy(status = "withdrawn"))) }
        assertEquals(0, scans.flush())
        assertEquals("Mia: registration withdrawn", scans.rejections.value.single().headline)
        assertTrue(requests.any { it.first.startsWith("DELETE /events/e1/scans/${mia.participantEventId}") })
    }

    @Test fun flush_transientFailure_keepsTheScanQueued() = runBlocking {
        seedRoster(mia)
        offline()
        val scans = repo()
        scans.submit("e1", qrMia, "desk", "Check-in desk")
        assertEquals(1, scans.flush())
        assertTrue(scans.rejections.value.isEmpty())
    }
}
