package au.ingo.betterattend.scan

import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.PendingScan
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.repo.ScanInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ScanAdmissionTest {
    private val now = Instant.parse("2026-10-04T01:00:00Z")

    private fun person(n: Int, status: String = "complete", waiver: Boolean = true, scannedAt: List<String> = emptyList()) = Participant(
        participantId = "aaaaaaa$n-0000-4000-8000-000000000000",
        participantEventId = "bbbbbbb$n-0000-4000-8000-000000000000",
        displayName = "Person $n",
        status = status,
        waiverSigned = waiver,
        nfcBadgeToken = "badge-$n",
        scansByContext = scannedAt.map { ContextScanSummary(it, checksIn = it == "desk", scanCount = 1, firstScannedAt = "2026-10-04T00:10:00Z") },
    )

    private val ok = person(1)
    private val withdrawn = person(2, status = "withdrawn")
    private val rejected = person(3, status = "rejected")
    private val noWaiver = person(4, waiver = false)
    private val inAlready = person(5, scannedAt = listOf("desk"))

    private val roster = Roster("e1", listOf(ok, withdrawn, rejected, noWaiver, inAlready), syncedAt = "2026-10-04T00:46:00Z", lastSyncAt = "2026-10-04T00:46:00Z")
    private val otherEvent = Roster("e2", listOf(person(9)), syncedAt = "2026-10-03T00:00:00Z")

    private fun qr(p: Participant) = ScanInput(participantId = p.participantId)

    private fun check(input: ScanInput, r: Roster? = roster, contextId: String? = "desk", checksIn: Boolean = true, pending: List<PendingScan> = emptyList()) =
        ScanAdmission.precheck(input, r, contextId, checksIn, pending, mapOf("Campfire Sydney" to otherEvent))

    @Test fun passes_registeredAndClear() {
        assertEquals(Precheck.Pass(ok), check(qr(ok)))
        // Matched by participant_event id and NFC badge token too.
        assertEquals(Precheck.Pass(ok), check(ScanInput(participantId = ok.participantEventId)))
        assertEquals(Precheck.Pass(ok), check(ScanInput(badgeToken = "badge-1", source = "nfc")))
    }

    @Test fun blocks_withdrawn() {
        assertEquals(Precheck.Block(RejectReason.Withdrawn, withdrawn), check(qr(withdrawn)))
        assertEquals(Precheck.Block(RejectReason.RegistrationRejected, rejected), check(qr(rejected)))
    }

    @Test fun blocks_missingConsent_onlyWhereItChecksIn() {
        assertEquals(Precheck.Block(RejectReason.ConsentMissing, noWaiver), check(qr(noWaiver)))
        // Lunch: they're already inside, so a missing waiver doesn't stop a meal scan.
        assertEquals(Precheck.Pass(noWaiver), check(qr(noWaiver), contextId = "lunch", checksIn = false))
    }

    @Test fun blocks_alreadyCheckedIn_atThisCheckpoint() {
        assertEquals(Precheck.Block(RejectReason.AlreadyCheckedIn, inAlready, "2026-10-04T00:10:00Z"), check(qr(inAlready)))
        assertEquals(Precheck.Pass(inAlready), check(qr(inAlready), contextId = "lunch", checksIn = false))
    }

    @Test fun blocks_alreadyQueuedOffline() {
        // The same ticket scanned twice while offline: the roster doesn't know yet, the queue does.
        val queued = PendingScan("q1", "e1", "desk", "Check-in desk", ScanInput(badgeToken = "badge-1"), "2026-10-04T00:55:00Z")
        assertEquals(Precheck.Block(RejectReason.AlreadyCheckedIn, ok, "2026-10-04T00:55:00Z"), check(qr(ok), pending = listOf(queued)))
        // Queued for another checkpoint or event: fine.
        assertEquals(Precheck.Pass(ok), check(qr(ok), pending = listOf(queued.copy(scanContextId = "lunch"))))
        assertEquals(Precheck.Pass(ok), check(qr(ok), pending = listOf(queued.copy(eventId = "e2"))))
    }

    @Test fun blocks_wrongEvent() {
        val other = otherEvent.participants.single()
        assertEquals(Precheck.Block(RejectReason.WrongEvent, other, "Campfire Sydney"), check(qr(other)))
    }

    @Test fun blocks_notRegistered() {
        assertEquals(Precheck.Block(RejectReason.NotRegistered, null), check(ScanInput(participantId = "ccccccc0-0000-4000-8000-000000000000")))
    }

    @Test fun cantProveAbsence_withoutAFullRoster_orForBadges() {
        val stranger = ScanInput(participantId = "ccccccc0-0000-4000-8000-000000000000")
        assertEquals(Precheck.Pass(null), check(stranger, r = null))
        // Partial roster (never fully synced): someone missing might just not be in it yet.
        assertEquals(Precheck.Pass(null), check(stranger, r = roster.copy(syncedAt = null)))
        // A badge written at the desk after the last sync isn't in the roster yet.
        assertEquals(Precheck.Pass(null), check(ScanInput(badgeToken = "new-badge", source = "nfc")))
        // But known problems in a partial roster still count.
        assertEquals(Precheck.Pass(withdrawn), check(qr(withdrawn), r = roster.copy(syncedAt = null)))
    }

    @Test fun serverRecordRules() {
        assertNull(ScanAdmission.problem(ok, checksIn = true))
        assertEquals(RejectReason.Withdrawn, ScanAdmission.problem(withdrawn, checksIn = false))
        assertEquals(RejectReason.ConsentMissing, ScanAdmission.problem(noWaiver, checksIn = true))
        assertNull(ScanAdmission.problem(noWaiver, checksIn = false))
    }

    @Test fun rosterAge() {
        val fresh = now.minus(Duration.ofMinutes(14)).toString()
        val old = now.minus(Duration.ofMinutes(75)).toString()
        assertEquals("Roster from 14 min ago", ScanAdmission.rosterAgeLabel(fresh, now))
        assertEquals("Roster synced just now", ScanAdmission.rosterAgeLabel(now.toString(), now))
        assertFalse(ScanAdmission.isRosterStale(fresh, now))
        assertTrue(ScanAdmission.isRosterStale(old, now))
        assertTrue("no roster at all is the stalest", ScanAdmission.isRosterStale(null, now))
        assertNull(ScanAdmission.rosterAgeLabel(null, now))
        assertEquals("2026-10-04T00:46:00Z", ScanAdmission.rosterTime(roster))
        assertNull(ScanAdmission.rosterTime(roster.copy(syncedAt = null)))
    }
}
