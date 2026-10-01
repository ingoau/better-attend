package au.ingo.betterattend.ui.travel

import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.util.Time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TravelLogicTest {
    private val entries = OrganizerSamples.travel.entries
    private val today = LocalDate.parse("2026-10-03")

    @Test fun sectionsAreChronologicalWithUnscheduledLast() {
        val sections = TravelLogic.sections(entries, today)
        assertEquals(listOf("Today", "Tomorrow", "Monday 5 October", "Unscheduled"), sections.map { it.title })
        assertEquals("Saturday 3 October", sections[0].subtitle)
        assertNull(sections[2].subtitle)
        assertEquals(entries.size, sections.sumOf { it.entries.size })
        val times = sections[0].entries.map { Time.parse(it.primaryTimeAt)!! }
        assertEquals(times.sorted(), times)
    }

    @Test fun yesterdayLabel() {
        assertEquals("Yesterday", TravelLogic.relativeLabel(today.minusDays(1), today))
        assertNull(TravelLogic.relativeLabel(today.plusDays(2), today))
    }

    @Test fun badDatesGoToUnscheduled() {
        val e = TravelEntry(id = "x", agendaDate = "not-a-date", participantName = "Zed")
        assertEquals("Unscheduled", TravelLogic.sections(listOf(e), today).single().title)
    }

    @Test fun filters() {
        fun n(f: TravelFilter) = TravelLogic.filter(entries, "", f, null).size
        assertEquals(11, n(TravelFilter.All))
        assertEquals(8, n(TravelFilter.Arrivals))
        assertEquals(3, n(TravelFilter.Departures))
        assertEquals(5, n(TravelFilter.AwaitingPickup))
        assertEquals(2, n(TravelFilter.PickedUp))
        assertEquals(2, n(TravelFilter.Minors))
        assertEquals(7, TravelLogic.filter(entries, "", TravelFilter.All, TravelMode.Plane).size)
        assertEquals(3, TravelLogic.filter(entries, "", TravelFilter.AwaitingPickup, TravelMode.Plane).size)
    }

    @Test fun searchMatchesAllTermsAcrossFields() {
        assertEquals(listOf("tr0"), TravelLogic.filter(entries, "qf810", TravelFilter.All, null).map { it.id })
        assertEquals(listOf("tr2", "tr8"), TravelLogic.filter(entries, "sin", TravelFilter.All, null).map { it.id }.sorted())
        assertEquals(listOf("tr2"), TravelLogic.filter(entries, "  SIN   sq288 ", TravelFilter.All, null).map { it.id })
        assertTrue(TravelLogic.filter(entries, "sin zzz", TravelFilter.All, null).isEmpty())
    }

    @Test fun countsFollowSearchAndMode() {
        val counts = TravelLogic.filterCounts(entries, "", TravelMode.Train)
        assertEquals(2, counts[TravelFilter.All])
        assertEquals(1, counts[TravelFilter.Arrivals])
        assertEquals(1, counts[TravelFilter.Departures])
        val modes = TravelLogic.modeCounts(entries, "", TravelFilter.Departures)
        assertEquals(mapOf(TravelMode.Plane to 2, TravelMode.Train to 1), modes)
        assertEquals(4, TravelLogic.modesPresent(entries).size)
    }

    @Test fun unknownModeIsOther() {
        assertEquals(TravelMode.Other, TravelMode.of(TravelEntry(id = "x", mode = null)))
        assertEquals(TravelMode.Other, TravelMode.of(TravelEntry(id = "x", mode = "boat")))
    }

    @Test fun zoneLabels() {
        val now = Instant.parse("2026-10-03T01:30:00Z") // AEST (+10); Sydney DST starts 4 Oct
        assertEquals("Sydney · GMT+10", TravelLogic.zoneLabel("Australia/Sydney", now))
        assertEquals("Sydney · GMT+11", TravelLogic.zoneLabel("Australia/Sydney", Instant.parse("2026-10-05T01:30:00Z")))
        assertEquals("Kolkata · GMT+5:30", TravelLogic.zoneLabel("Asia/Kolkata", now))
        assertEquals("London · GMT+1", TravelLogic.zoneLabel("Europe/London", now))
        assertEquals("Los Angeles · GMT-7", TravelLogic.zoneLabel("America/Los_Angeles", now))
        assertNull(TravelLogic.zoneLabel(null, now))
        assertTrue(TravelLogic.deviceZoneDiffers("Australia/Sydney", now, ZoneId.of("UTC")))
        assertFalse(TravelLogic.deviceZoneDiffers("Australia/Sydney", now, ZoneId.of("Australia/Melbourne")))
    }

    @Test fun todayUsesEventZone() {
        // 23:30 UTC on the 2nd is already the 3rd in Sydney.
        assertEquals(LocalDate.parse("2026-10-03"), TravelLogic.today("Australia/Sydney", Instant.parse("2026-10-02T23:30:00Z")))
    }

    @Test fun timeSplitting() {
        assertEquals("7:25" to "AM", splitTime("7:25 AM"))
        assertEquals("7:25" to "PM", splitTime("7:25 PM"))
        assertEquals("19:25" to null, splitTime("19:25"))
        assertEquals(null to null, splitTime(null))
    }
}
