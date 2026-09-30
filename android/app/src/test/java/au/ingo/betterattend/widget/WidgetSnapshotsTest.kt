package au.ingo.betterattend.widget

import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetSnapshotsTest {
    private val now = Instant.parse("2026-10-03T01:30:00Z")
    private val roster = Roster(SampleData.event.id, SampleData.participants, syncedAt = "2026-10-03T01:20:00.123456Z", lastFullSyncAt = "2026-10-03T00:00:00Z", lastSyncAt = "2026-10-03T01:20:00Z")

    private fun build(
        user: au.ingo.betterattend.data.model.User? = SampleData.user,
        event: au.ingo.betterattend.data.model.Event? = SampleData.event,
        roster: Roster? = this.roster,
        isOrganizer: Boolean = true,
    ) = WidgetSnapshots.build(user, event, roster, SampleData.contexts, SampleData.travel, SampleData.tickets, isOrganizer, now)

    @Test fun signedOutHasNoData() {
        val s = build(user = null)
        assertFalse(s.signedIn)
        assertNull(s.organizer)
        assertNull(s.ticket)
    }

    @Test fun organizerCountsMatchEventStats() {
        val org = build().organizer!!
        val stats = EventStats.from(SampleData.participants, now)
        assertEquals("Campfire Sydney", org.eventName)
        assertTrue(org.hasCounts)
        assertEquals(stats.checkedIn, org.checkedIn)
        assertEquals(maxOf(stats.confirmed, stats.checkedIn), org.expected)
        assertEquals(stats.notArrived, org.notArrived)
        assertEquals(stats.checkedInLastHour, org.lastHour)
        assertEquals("2026-10-03T01:20:00Z", org.updatedAt)
        // One row per scan context, in position order, with unique-participant counts.
        assertEquals(SampleData.contexts.sortedBy { it.position }.map { it.name }, org.contexts.map { it.name })
        org.contexts.forEach { assertEquals(stats.perContext[it.id] ?: 0, it.count) }
        assertTrue(org.contexts.first { it.id == "c2" }.isTravel)
    }

    @Test fun noCountsWithoutRosterAccessOrSync() {
        assertFalse(build(event = SampleData.event.copy(canViewParticipants = false)).organizer!!.hasCounts)
        assertFalse(build(roster = roster.copy(syncedAt = null)).organizer!!.hasCounts)
        assertFalse(build(roster = null).organizer!!.hasCounts)
    }

    @Test fun participantOnlyUserGetsTicketButNoOrganizerData() {
        val s = build(user = SampleData.user.copy(isOrganizer = false), isOrganizer = false)
        assertNull(s.organizer)
        assertNotNull(s.ticket)
        assertTrue(s.isParticipant)
    }

    @Test fun travelUsesServerCountsAndNextUpcomingInbound() {
        val t = build().organizer!!.travel!!
        assertEquals(SampleData.travel.counts.awaitingPickup, t.awaitingPickup)
        assertEquals(SampleData.travel.counts.collected, t.collected)
        assertEquals(SampleData.travel.counts.checkedIn, t.checkedIn)
        // Entries 0 and 1 are collected/checked in; entry 2 (awaiting, +90 min) is next.
        val expected = SampleData.travel.entries[2]
        assertEquals(expected.name, t.nextArrivalName)
        assertEquals(expected.primaryTimeAt, t.nextArrivalAt)
        assertTrue(t.nextArrivalMinor)
    }

    @Test fun nextArrivalSkipsLongPastAndOutbound() {
        val cal = SampleData.travel.copy(entries = listOf(
            TravelEntry(id = "old", direction = "inbound", primaryTimeAt = "2026-10-02T20:00:00Z", participantName = "Old"),
            TravelEntry(id = "out", direction = "outbound", primaryTimeAt = "2026-10-03T02:00:00Z", participantName = "Out"),
            TravelEntry(id = "late", direction = "inbound", primaryTimeAt = "2026-10-03T05:00:00Z", participantName = "Late"),
            TravelEntry(id = "soon", direction = "inbound", primaryTimeAt = "2026-10-03T01:10:00Z", participantName = "Just landed"),
        ))
        assertEquals("Just landed", WidgetSnapshots.travel(cal, now).nextArrivalName)
        assertNull(WidgetSnapshots.travel(cal.copy(entries = cal.entries.take(2)), now).nextArrivalName)
    }

    @Test fun travelOmittedWhenEventHasNoTravel() {
        assertNull(build(event = SampleData.event.copy(travelEnabled = false)).organizer!!.travel)
    }

    @Test fun ticketIsTheNextOne() {
        val t = build().ticket!!
        assertEquals(SampleData.ticket.id, t.id)
        assertEquals("Ready", t.statusLabel)
        assertTrue(t.confirmed)
    }

    @Test fun sameContentIgnoresBuildTime() {
        val a = build()
        val b = a.copy(builtAt = "2030-01-01T00:00:00Z")
        assertTrue(a.sameContentAs(b))
        assertFalse(a.sameContentAs(a.copy(organizer = a.organizer!!.copy(checkedIn = 1))))
        assertFalse(a.sameContentAs(null))
    }

    @Test fun snapshotSurvivesJsonRoundTrip() {
        val s = build()
        val json = AttendJson.encodeToString(WidgetSnapshot.serializer(), s)
        assertEquals(s, AttendJson.decodeFromString(WidgetSnapshot.serializer(), json))
    }

    @Test fun labels() {
        assertEquals("Updated 10 min ago", updatedLabel("2026-10-03T01:20:00Z", now))
        assertEquals("Not synced yet", updatedLabel(null, now))
        val t = WidgetSamples.organizer.travel!!
        assertTrue(nextArrivalLabel(t, "Australia/Sydney", compact = false).startsWith("Next: Sam · "))
        assertEquals("No more arrivals scheduled", nextArrivalLabel(t.copy(nextArrivalAt = null), null, compact = true))
    }
}
