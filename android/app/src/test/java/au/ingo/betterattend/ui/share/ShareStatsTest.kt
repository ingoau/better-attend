package au.ingo.betterattend.ui.share

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.ui.dashboard.DashboardLogic
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ShareStatsTest {
    private val now = OrganizerSamples.now
    private val event = SampleData.event
    private val stats = EventStats.from(OrganizerSamples.participants, now)
    private val contexts = DashboardLogic.contextProgress(SampleData.contexts, stats, now)

    private fun ids(at: Instant = now) = ShareStats.available(event, stats, contexts, null, null, at).map { it.id }

    @Test fun liveEventSharesCheckInsAndEveryTile() {
        val all = ShareStats.available(event, stats, contexts, DashboardLogic.arrivals(OrganizerSamples.travel, now), null, now)
        val checkedIn = all.first()
        assertEquals(ShareStats.CHECKED_IN, checkedIn.id)
        assertEquals(84, checkedIn.value)
        assertEquals(120, checkedIn.total)
        assertEquals(ShareStats.CHECKED_IN, ShareStats.heroId(event, now))
        assertTrue(all.map { it.id }.containsAll(listOf(ShareStats.REGISTERED, ShareStats.CONFIRMED, ShareStats.NOT_COMPLETE, ShareStats.WITHDRAWN, ShareStats.TO_COLLECT)))
        assertTrue(SampleData.contexts.all { ShareStats.context(it.id) in all.map { s -> s.id } })
        assertEquals("+12", all.single { it.id == ShareStats.LAST_HOUR }.display)
    }

    @Test fun upcomingEventSharesRegistrationsInstead() {
        val before = Instant.parse("2026-09-30T02:00:00Z")
        assertEquals(ShareStats.REGISTRATIONS, ShareStats.heroId(event, before))
        assertEquals(ShareStats.REGISTRATIONS, ids(before).first())
        assertFalse(ShareStats.CHECKED_IN in ids(before))
    }

    @Test fun pastEventDropsLastHour() {
        val after = Instant.parse("2026-10-08T02:00:00Z")
        assertTrue(ShareStats.CHECKED_IN in ids(after))
        assertFalse(ShareStats.LAST_HOUR in ids(after))
    }

    @Test fun limitedRolesShareScanCounts() {
        val feed = DashboardLogic.scanFeed(OrganizerSamples.scans, event.timezone, now)
        val all = ShareStats.available(event, null, emptyList(), null, feed, now)
        assertEquals(listOf(ShareStats.SCANS_TODAY, ShareStats.PEOPLE_SCANNED), all.map { it.id })
    }

    @Test fun toggleKeepsOrderAndLimits() {
        assertEquals(listOf("a", "b"), ShareStats.toggle(listOf("a"), "b"))
        assertEquals(listOf("b"), ShareStats.toggle(listOf("a", "b"), "a"))
        // The last number can't be removed.
        assertEquals(listOf("a"), ShareStats.toggle(listOf("a"), "a"))
        val full = (1..ShareStats.MAX_ON_CARD).map { "s$it" }
        assertEquals(full, ShareStats.toggle(full, "extra"))
    }

    @Test fun layoutColumns() {
        assertEquals(listOf(1, 2, 3, 2, 3, 3), (1..6).map { ShareCardLayout.Row.columns(it) })
        assertEquals(listOf(1, 2, 2), (1..3).map { ShareCardLayout.Grid.columns(it) })
        assertEquals(1, ShareCardLayout.List.columns(4))
    }

    @Test fun fractionNeedsATotal() {
        assertEquals(null, ShareStat("x", "X", 5, Icons.Outlined.Groups).fraction)
        assertEquals(0.5f, ShareStat("x", "X", 5, Icons.Outlined.Groups, total = 10).fraction)
        assertEquals(null, ShareStat("x", "X", 5, Icons.Outlined.Groups, total = 0).fraction)
    }

    @Test fun savedFileNameIsSafeAndLocal() {
        // 01:30 UTC is 11:30 in Sydney.
        assertEquals("Campfire Sydney 2026-10-03 1130.png", shareFileName(event, now))
        assertEquals("A B C 2026-10-03 1130.png", shareFileName(event.copy(name = "A/B: C?"), now))
    }
}
