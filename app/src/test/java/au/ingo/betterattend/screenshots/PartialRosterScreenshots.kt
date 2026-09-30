package au.ingo.betterattend.screenshots

import au.ingo.betterattend.ui.dashboard.DashboardContent
import au.ingo.betterattend.ui.dashboard.DashboardState
import au.ingo.betterattend.ui.people.PeopleContent
import au.ingo.betterattend.ui.people.PeopleUiState
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A roster with `syncedAt == null` is only partial (e.g. a few people learned from scans before the first
 * full sync). Home and People must show a loading state for the numbers rather than "3 of 3 checked in".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PartialRosterScreenshots : ScreenshotTest() {
    private val now = OrganizerSamples.now
    private val partial = OrganizerSamples.roster.copy(
        participants = OrganizerSamples.participants.filter { it.isCheckedIn }.take(3),
        syncedAt = null, lastFullSyncAt = null, lastSyncAt = null,
    )

    @Test fun dashboard() = snap("partial_roster_dashboard") {
        DashboardContent(
            state = DashboardState(
                user = SampleData.user, events = SampleData.events, event = SampleData.event,
                roster = partial, contexts = SampleData.contexts, refreshing = true,
            ),
            onRefresh = {}, onPickEvent = {}, onAccount = {}, onSwitchTab = {},
            onOpenParticipant = {}, onAnnounce = {}, onKiosk = {}, now = now,
        )
    }

    @Test fun people() = snap("partial_roster_people") {
        PeopleContent(
            PeopleUiState(
                user = SampleData.user, eventsLoaded = true, event = SampleData.event,
                roster = partial.participants, rosterComplete = false, contexts = SampleData.contexts, syncing = true,
            ),
            now,
        )
    }
}
