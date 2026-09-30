package au.ingo.betterattend.screenshots

import androidx.compose.runtime.Composable
import au.ingo.betterattend.ui.dashboard.DashboardContent
import au.ingo.betterattend.ui.dashboard.DashboardState
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DashboardScreenshots : ScreenshotTest() {
    private val now = OrganizerSamples.now

    private val live = DashboardState(
        user = SampleData.user,
        events = SampleData.events,
        event = SampleData.event,
        roster = OrganizerSamples.roster,
        contexts = SampleData.contexts.map {
            // Make lunch "happening now" so the active-context treatment shows.
            if (it.id == "c3") it.copy(startsAt = "2026-10-03T11:00:00+10:00", endsAt = "2026-10-03T13:00:00+10:00") else it
        },
        travel = OrganizerSamples.travel,
        lastUpdated = now.minusSeconds(20),
        pendingScans = 2,
    )

    @Composable
    private fun Content(state: DashboardState, at: Instant = now) = DashboardContent(
        state = state, onRefresh = {}, onPickEvent = {}, onAccount = {}, onSwitchTab = {},
        onOpenParticipant = {}, onAnnounce = {}, onKiosk = {}, now = at,
    )

    @Test fun live() = snap("dashboard_live") { Content(live) }

    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun liveFull() = snap("dashboard_live_full") { Content(live) }

    @Test fun loadingEvents() = snap("dashboard_loading") { Content(DashboardState(user = SampleData.user)) }

    @Test fun loadingRoster() = snap("dashboard_loading_roster") {
        Content(live.copy(roster = null, refreshing = true, lastUpdated = null))
    }

    @Test fun offline() = snap("dashboard_offline") {
        Content(live.copy(error = "You're offline. Check your connection.", lastUpdated = now.minusSeconds(60 * 12)))
    }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun noPermission() = snap("dashboard_no_permission") {
        Content(
            live.copy(
                event = SampleData.event.copy(role = "read_only", canViewParticipants = false, canViewParticipantPii = false),
                roster = null,
                scans = OrganizerSamples.scans,
            ),
        )
    }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun upcoming() = snap("dashboard_upcoming") {
        val notYet = OrganizerSamples.roster.copy(
            participants = OrganizerSamples.participants.map { it.copy(checkedInAt = null, scansByContext = emptyList()) },
        )
        Content(live.copy(roster = notYet, travel = null), at = Instant.parse("2026-09-30T02:00:00Z"))
    }

    @Test fun past() = snap("dashboard_past") { Content(live, at = Instant.parse("2026-10-08T02:00:00Z")) }

    @Test fun noEvents() = snap("dashboard_no_events") { Content(DashboardState(user = SampleData.user, events = emptyList())) }
}
