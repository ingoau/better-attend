package au.ingo.betterattend.ui.rollcall

import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.RollCall
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.util.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RollCallLogicTest {
    private val now = Instant.parse("2026-10-03T01:30:00Z")

    /** Local time as the app formats it (locale-dependent, e.g. "11:30 AM"). */
    private fun t(iso: String) = Time.time(iso, "Australia/Canberra")!!

    private fun person(n: Int, name: String, checkedIn: Boolean = true, status: String = "complete") = Participant(
        participantId = "0000000$n-aaaa-4000-8000-000000000000",
        participantEventId = "pe$n",
        displayName = name.substringBefore(' '), fullName = name, email = "${name.substringBefore(' ').lowercase()}@example.com",
        status = status, checkedInAt = if (checkedIn) "2026-10-02T23:00:00Z" else null,
    )

    private val mia = person(1, "Mia Chen")
    private val leo = person(2, "Leo Nguyen")
    private val ava = person(3, "Ava Brown", checkedIn = false)
    private val zed = person(4, "Zed Withdrawn", status = "withdrawn")
    private val kai = person(5, "Kai Tanaka", checkedIn = false, status = "invited")
    private val roster = Roster("e1", listOf(mia, leo, ava, zed, kai), syncedAt = "2026-10-03T01:00:00Z")
    private val contexts = listOf(ScanContext("c1", "Check-in desk", checksIn = true), ScanContext("c2", "Muster point"))

    private fun start(mode: RollCallExpected = RollCallExpected.CheckedIn, recording: TickRecording = TickRecording.PhoneOnly) =
        RollCallLogic.start("e1", roster.participants, mode, recording, contexts, now)

    @Test fun expected_checkedIn_isActiveAndArrivedOnly() {
        assertEquals(listOf("pe2", "pe1"), start().expectedIds) // Leo, Mia by name; withdrawn Zed excluded
    }

    @Test fun expected_everyone_isEveryActiveRegistration() {
        assertEquals(listOf("pe3", "pe5", "pe2", "pe1"), start(RollCallExpected.Registered).expectedIds)
    }

    @Test fun start_phoneOnly_recordsNothing() {
        val rc = start()
        assertNull(rc.scanContextId)
        assertFalse(rc.recordsScans)
        assertEquals(now.toString(), rc.startedAt)
    }

    @Test fun start_atChosenScanPoint_keepsExactlyThatOne() {
        val rc = start(recording = TickRecording.AtScanPoint("c2"))
        assertEquals("c2", rc.scanContextId)
        assertEquals("Muster point", rc.scanContextName)
        assertFalse(rc.scanContextChecksIn)
    }

    @Test(expected = IllegalArgumentException::class)
    fun start_unknownScanPoint_isRefusedNotSubstituted() {
        start(recording = TickRecording.AtScanPoint("gone"))
    }

    @Test fun expectedList_isFrozen_whenTheRosterChanges() {
        val rc = start()
        // Ava checks in after the roll call started: she isn't expected, so counts don't move.
        val later = roster.copy(participants = roster.participants.map { if (it == ava) it.copy(checkedInAt = "2026-10-03T01:40:00Z") else it })
        assertEquals(listOf("pe2", "pe1"), rc.expectedIds)
        assertEquals(RollCallCounts(0, 2), RollCallLogic.counts(rc))
        assertEquals(2, RollCallLogic.allRows(rc, later).size)
    }

    @Test fun toggle_ticksAndUnticks() {
        val rc = start().toggle("pe1", "t1")
        assertEquals(mapOf("pe1" to "t1"), rc.accounted)
        assertEquals(RollCallCounts(1, 2), RollCallLogic.counts(rc))
        assertEquals(1, RollCallLogic.counts(rc).missing)
        val back = rc.toggle("pe1", "t2")
        assertTrue(back.accounted.isEmpty())
        assertEquals(2, RollCallLogic.counts(back).missing)
    }

    @Test fun toggle_someoneNotOnTheList_doesNothing() {
        val rc = start()
        assertEquals(rc, rc.toggle("pe3", "t"))
    }

    @Test fun add_unexpectedPerson_isTickedAndCounted() {
        val rc = start().add("pe3", "Ava", "t1")
        assertEquals(listOf("pe3"), rc.added)
        assertEquals("t1", rc.accounted["pe3"])
        assertEquals(RollCallCounts(1, 3), RollCallLogic.counts(rc))
        // Unticking someone who was added takes them off the list again.
        val undone = rc.toggle("pe3", "t2")
        assertTrue(undone.added.isEmpty())
        assertEquals(RollCallCounts(0, 2), RollCallLogic.counts(undone))
    }

    @Test fun add_expectedPerson_justTicksThem() {
        val rc = start().add("pe1", "Mia", "t1")
        assertTrue(rc.added.isEmpty())
        assertEquals(setOf("pe1"), rc.accounted.keys)
    }

    @Test fun tick_clearsStillRecordedMarker() {
        val rc = start().copy(stillRecorded = setOf("pe1"))
        assertTrue(RollCallLogic.allRows(rc, roster).first { it.id == "pe1" }.stillRecorded)
        assertFalse(rc.toggle("pe1", "t").stillRecorded.contains("pe1"))
    }

    @Test fun filters_andChipCounts() {
        val rc = start(RollCallExpected.Registered).toggle("pe1", "t").toggle("pe3", "t")
        assertEquals(listOf("Kai Tanaka", "Leo Nguyen"), RollCallLogic.rows(rc, roster, RollCallFilter.Missing, "").map { it.name })
        assertEquals(listOf("Ava Brown", "Mia Chen"), RollCallLogic.rows(rc, roster, RollCallFilter.Accounted, "").map { it.name })
        assertEquals(4, RollCallLogic.rows(rc, roster, RollCallFilter.All, "").size)
        assertEquals(mapOf(RollCallFilter.Missing to 2, RollCallFilter.Accounted to 2, RollCallFilter.All to 4), RollCallLogic.filterCounts(rc))
    }

    @Test fun search_matchesNamesWithinTheFilter() {
        val rc = start(RollCallExpected.Registered)
        assertEquals(listOf("pe2"), RollCallLogic.rows(rc, roster, RollCallFilter.All, "nguy").map { it.id })
        assertTrue(RollCallLogic.rows(rc.toggle("pe2", "t"), roster, RollCallFilter.Missing, "leo").isEmpty())
    }

    @Test fun rows_personWhoLeftTheRoster_keepsTheirFrozenName() {
        val rc = start()
        val row = RollCallLogic.allRows(rc, roster.copy(participants = listOf(mia))).first { it.id == "pe2" }
        assertEquals("Leo Nguyen", row.name)
        assertNull(row.participant)
    }

    @Test fun addCandidates_areActivePeopleNotOnTheList() {
        val rc = start()
        assertEquals(listOf("pe3", "pe5"), RollCallLogic.addCandidates(rc, roster, "").map { it.participantEventId })
        assertEquals(listOf("pe5"), RollCallLogic.addCandidates(rc, roster, "kai").map { it.participantEventId })
        assertTrue(RollCallLogic.addCandidates(rc, roster, "zed").isEmpty()) // withdrawn
    }

    @Test fun missingShareText_listsMissingNamesOnly() {
        val rc = start().toggle("pe1", "t").copy(finishedAt = "2026-10-03T01:45:00Z")
        val text = RollCallLogic.missingShareText(rc, roster, "Campfire Canberra", "Australia/Canberra", now)
        assertEquals(
            "Roll call: Campfire Canberra\nStarted ${t("2026-10-03T01:30:00Z")}, as of ${t("2026-10-03T01:45:00Z")}\n1 / 2 accounted for · 1 missing\n\nMissing:\n• Leo Nguyen",
            text,
        )
        assertFalse(text.contains("@")) // no contact details leave the app
    }

    @Test fun missingShareText_whenEveryoneIsHere() {
        val rc = start().toggle("pe1", "t").toggle("pe2", "t")
        assertTrue(RollCallLogic.missingShareText(rc, roster, "Campfire", null, now).endsWith("Everyone is accounted for."))
    }

    @Test fun duration_andSubtitle() {
        val rc = start(recording = TickRecording.AtScanPoint("c1")).copy(finishedAt = "2026-10-03T02:35:00Z")
        assertEquals("1 h 5 min", RollCallLogic.duration(rc))
        assertEquals("Started ${t(now.toString())} · Recording at Check-in desk", RollCallLogic.subtitle(rc, "Australia/Canberra"))
        assertEquals("Started ${t(now.toString())} · Only on this phone", RollCallLogic.subtitle(start(), "Australia/Canberra"))
    }

    @Test fun session_survivesJsonRoundTrip() {
        val rc = start(recording = TickRecording.AtScanPoint("c1")).add("pe3", "Ava", "t1").toggle("pe1", "t2")
            .copy(stillRecorded = setOf("pe2"), queued = mapOf("pe1" to "client-1"))
        val json = AttendJson.encodeToString(RollCall.serializer(), rc)
        assertTrue(json.contains("\"checked_in\""))
        val back = AttendJson.decodeFromString(RollCall.serializer(), json)
        assertEquals(rc, back)
        assertTrue(back.isExpected("pe1"))
        assertFalse(back.isExpected("pe3"))
    }
}
