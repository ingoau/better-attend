package au.ingo.betterattend.ui.dashboard

import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DashboardLogicTest {
    private val now = OrganizerSamples.now
    private val stats = EventStats.from(OrganizerSamples.participants, now)
    private val tz = "Australia/Sydney"

    @Test fun heroNumbersAddUp() {
        assertEquals(120, stats.confirmed)
        assertEquals(84, DashboardLogic.checkedInConfirmed(stats))
        assertEquals(36, stats.notArrived)
        assertEquals(stats.confirmed, DashboardLogic.checkedInConfirmed(stats) + stats.notArrived)
        assertEquals(12, stats.checkedInLastHour)
        assertEquals(132, stats.registered)
        assertEquals(12, DashboardLogic.notComplete(stats))
        assertEquals(10, stats.withdrawn)
        assertEquals(5, DashboardLogic.needsAttention(OrganizerSamples.participants))
    }

    @Test fun contextProgressFollowsPositionAndMarksActive() {
        val lunchNow = SampleData.contexts.map {
            if (it.id == "c3") it.copy(startsAt = "2026-10-03T11:00:00+10:00", endsAt = "2026-10-03T13:00:00+10:00") else it
        }.reversed()
        val rows = DashboardLogic.contextProgress(lunchNow, stats, now)
        assertEquals(listOf("c1", "c2", "c3"), rows.map { it.context.id })
        assertEquals(listOf(84, 22, 63), rows.map { it.count })
        assertEquals(listOf(false, false, true), rows.map { it.active })
        assertEquals(0.7f, rows[0].fraction, 0.001f)
    }

    @Test fun fractionIsSafeWithNoConfirmed() {
        val row = ContextProgress(SampleData.contexts[0], 5, 0, false)
        assertEquals(0f, row.fraction)
    }

    @Test fun recentCheckInsAreNewestFirstAndSkipWithdrawn() {
        val withdrawnRecent = OrganizerSamples.participants[135].copy(checkedInAt = now.toString())
        val recent = DashboardLogic.recentCheckIns(OrganizerSamples.participants + withdrawnRecent, limit = 5)
        assertEquals(5, recent.size)
        assertEquals(OrganizerSamples.participants.take(5).map { it.participantEventId }, recent.map { it.participantEventId })
    }

    @Test fun nextArrivalsSkipCollectedLateAndUnscheduled() {
        val a = DashboardLogic.arrivals(OrganizerSamples.travel, now)!!
        assertEquals(listOf("tr2", "tr3", "tr4"), a.next.map { it.id })
        assertEquals(5, a.awaitingPickup)
        // Five hours later only the bus is still due (and it's within the 3 h grace window).
        val later = DashboardLogic.arrivals(OrganizerSamples.travel, now.plusSeconds(5 * 3600))!!
        assertEquals(listOf("tr6"), later.next.map { it.id })
        assertNull(DashboardLogic.arrivals(null, now))
        assertTrue(DashboardLogic.arrivals(TravelCalendar(), now)!!.next.isEmpty())
    }

    @Test fun scanFeedCountsOnlyTodayInEventZone() {
        val feed = DashboardLogic.scanFeed(OrganizerSamples.scans, tz, now)
        assertEquals(40, feed.today)
        assertFalse(feed.capped)
        assertEquals(6, feed.recent.size)
        assertEquals("s0", feed.recent.first().id)
    }

    @Test fun scanFeedIsCappedWhenAllHundredAreToday() {
        val scans = (0 until 100).map { Scan(id = "s$it", participantEventId = "p${it % 30}", scannedAt = now.minusSeconds(it * 30L).toString()) }
        val feed = DashboardLogic.scanFeed(scans, tz, now)
        assertTrue(feed.capped)
        assertEquals(30, feed.uniquePeopleToday)
    }

    @Test fun countdown() {
        val start = "2026-10-02T22:00:00Z" // Sat 3 Oct 08:00 Sydney
        assertEquals("Starts in 3 days", DashboardLogic.countdown(start, tz, Instant.parse("2026-09-30T02:00:00Z")))
        assertEquals("Starts tomorrow", DashboardLogic.countdown(start, tz, Instant.parse("2026-10-02T02:00:00Z")))
        assertEquals("Starts in 6 h", DashboardLogic.countdown(start, tz, Instant.parse("2026-10-02T15:30:00Z")))
        assertEquals("Starts in 15 min", DashboardLogic.countdown(start, tz, Instant.parse("2026-10-02T21:45:00Z")))
        assertNull(DashboardLogic.countdown(start, tz, Instant.parse("2026-10-02T22:00:00Z")))
        assertNull(DashboardLogic.countdown(null, tz, now))
    }

    @Test fun ended() {
        val end = "2026-10-04T06:00:00Z" // Sun 4 Oct 17:00 Sydney
        assertEquals("Ended today", DashboardLogic.ended(end, tz, Instant.parse("2026-10-04T08:00:00Z")))
        assertEquals("Ended yesterday", DashboardLogic.ended(end, tz, Instant.parse("2026-10-05T01:00:00Z")))
        assertEquals("Ended 4 days ago", DashboardLogic.ended(end, tz, Instant.parse("2026-10-08T02:00:00Z")))
        assertNull(DashboardLogic.ended(end, tz, now))
    }

    @Test fun labels() {
        assertEquals("Sat 3 – Sun 4 Oct · Sydney", DashboardLogic.subtitle(SampleData.event))
        assertEquals("Read only", DashboardLogic.roleLabel("read_only"))
        assertEquals("Some thing", DashboardLogic.roleLabel("some_thing"))
        assertEquals("Updated just now", DashboardLogic.updated(now.minusSeconds(10), now))
        assertEquals("Updated 5 min ago", DashboardLogic.updated(now.minusSeconds(300), now))
        assertNull(DashboardLogic.updated(null, now))
    }
}
