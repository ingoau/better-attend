package au.ingo.betterattend.screenshots

import au.ingo.betterattend.ui.people.FilterOptions
import au.ingo.betterattend.ui.people.FilterSheetContent
import au.ingo.betterattend.ui.people.PeopleContent
import au.ingo.betterattend.ui.people.PeopleUiState
import au.ingo.betterattend.ui.people.QuickFilter
import au.ingo.betterattend.ui.people.SortOrder
import au.ingo.betterattend.ui.people.TriState
import au.ingo.betterattend.ui.preview.SampleData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PeopleScreenshots : ScreenshotTest() {
    private val now = Instant.parse("2026-10-03T01:32:00Z")
    private val base = PeopleUiState(
        user = SampleData.user, eventsLoaded = true, event = SampleData.event,
        roster = SampleData.participants, contexts = SampleData.contexts, lastSyncAt = "2026-10-03T01:30:00Z",
    )

    @Test fun all() = snap("people_all") { PeopleContent(base, now) }

    @Test fun filtered() = snap("people_filtered") {
        PeopleContent(base.copy(quick = QuickFilter.Here, sort = SortOrder.RecentCheckIn, options = FilterOptions(waiverSigned = TriState.Yes)), now)
    }

    @Test fun needsAttention() = snap("people_attention") { PeopleContent(base.copy(quick = QuickFilter.NeedsAttention), now) }

    @Test fun search() = snap("people_search") { PeopleContent(base.copy(query = "ma"), now) }

    @Test fun searchExpanded() = snap("people_search_expanded", screen = true, prepare = {
        // The full-screen search is a dialog with a focused text field, which never lets Compose go idle.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Search name, email or code").performClick()
        settlePaused()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("ma")
        settlePaused()
    }) {
        var query by remember { mutableStateOf("") }
        PeopleContent(base.copy(query = query), now, onQuery = { query = it })
    }

    @Test fun searchNoMatch() = snap("people_search_none") { PeopleContent(base.copy(query = "zzq", remoteResults = emptyList()), now) }

    @Test fun offline() = snap("people_offline") {
        PeopleContent(base.copy(syncError = "You're offline. Check your connection.", lastSyncAt = "2026-10-03T01:10:00Z"), now)
    }

    @Test fun emptyRoster() = snap("people_empty") { PeopleContent(base.copy(roster = emptyList()), now) }

    @Test fun noEvent() = snap("people_no_event") { PeopleContent(base.copy(event = null, roster = null), now) }

    @Test fun noPermission() = snap("people_no_permission") {
        PeopleContent(base.copy(event = SampleData.event.copy(role = "read_only", canViewParticipants = false, canViewParticipantPii = false, canViewSensitiveData = false)), now)
    }

    @Test fun filterSheet() = snap("people_filter_sheet") {
        FilterSheetContent(
            options = FilterOptions(scannedAtContextId = "c2", statuses = setOf("complete"), inboundTravel = TriState.Yes, travelModes = setOf("plane")),
            sort = SortOrder.Name, contexts = SampleData.contexts,
            statuses = listOf("complete", "awaiting_guardian", "withdrawn"), dietTypes = listOf("omnivore", "vegetarian"),
            canViewSensitive = true, resultCount = 4, onOptions = {}, onSort = {}, onDone = {},
        )
    }
}
