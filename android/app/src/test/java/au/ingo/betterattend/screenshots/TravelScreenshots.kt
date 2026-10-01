package au.ingo.betterattend.screenshots

import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.travel.TravelContent
import au.ingo.betterattend.ui.travel.TravelFilter
import au.ingo.betterattend.ui.travel.TravelMode
import au.ingo.betterattend.ui.travel.TravelUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TravelScreenshots : ScreenshotTest() {
    private val loaded = TravelUiState(event = SampleData.event, calendar = OrganizerSamples.travel)

    @androidx.compose.runtime.Composable
    private fun Content(
        state: TravelUiState,
        query: String = "",
        filter: TravelFilter = TravelFilter.All,
        mode: TravelMode? = null,
    ) = TravelContent(
        state = state, query = query, onQueryChange = {}, filter = filter, onFilterChange = {}, mode = mode,
        onModeChange = {}, onRefresh = {}, onOpen = {}, onPickEvent = {}, now = OrganizerSamples.now,
    )

    @Test fun list() = snap("travel_list") { Content(loaded) }

    @Test fun filtered() = snap("travel_filtered") { Content(loaded, filter = TravelFilter.AwaitingPickup, mode = TravelMode.Plane) }

    @Test fun searchNoMatches() = snap("travel_no_matches") { Content(loaded, query = "zzz") }

    @Test fun empty() = snap("travel_empty") { Content(loaded.copy(calendar = TravelCalendar(eventTimezone = "Australia/Canberra"))) }

    @Test fun offline() = snap("travel_offline") {
        Content(loaded.copy(error = "You're offline. Check your connection."), filter = TravelFilter.Departures)
    }

    @Test fun disabled() = snap("travel_disabled") { Content(TravelUiState(event = SampleData.event.copy(travelEnabled = false))) }
}
