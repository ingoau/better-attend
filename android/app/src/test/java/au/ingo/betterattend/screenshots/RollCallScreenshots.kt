package au.ingo.betterattend.screenshots

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SafetySamples
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.rollcall.RollCallActions
import au.ingo.betterattend.ui.rollcall.RollCallContent
import au.ingo.betterattend.ui.rollcall.RollCallFilter
import au.ingo.betterattend.ui.rollcall.RollCallSetupChoice
import au.ingo.betterattend.ui.rollcall.RollCallUiState
import au.ingo.betterattend.ui.rollcall.TickRecording
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RollCallScreenshots : ScreenshotTest() {
    private val now = OrganizerSamples.now

    private val base = RollCallUiState(
        eventsLoaded = true,
        event = SampleData.event,
        roster = OrganizerSamples.roster,
        contexts = SampleData.contexts,
        loaded = true,
    )

    private fun snapState(
        name: String,
        state: RollCallUiState,
        setup: RollCallSetupChoice = RollCallSetupChoice(RollCallExpected.CheckedIn, TickRecording.PhoneOnly),
        filter: RollCallFilter = RollCallFilter.Missing,
        query: String = "",
    ) = snap(name) {
        RollCallContent(state, remember { SnackbarHostState() }, setup, filter, query, RollCallActions(), now)
    }

    /** Fresh start screen: ticks stay on the phone; no scan point is selected. */
    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun setup() = snapState("rollcall_setup", base)

    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun setupScanPointChosen() = snapState(
        "rollcall_setup_scan_point", base,
        setup = RollCallSetupChoice(RollCallExpected.Registered, TickRecording.AtScanPoint("c3")),
    )

    @Test fun running() = snapState("rollcall_running", base.copy(rollCall = SafetySamples.rollCall))

    @Test fun runningAccounted() = snapState("rollcall_running_accounted", base.copy(rollCall = SafetySamples.rollCall), filter = RollCallFilter.Accounted)

    @Test fun summary() = snapState("rollcall_summary", base.copy(rollCall = SafetySamples.finishedRollCall))

    @Test fun noAccess() = snapState(
        "rollcall_no_access",
        base.copy(event = SampleData.event.copy(role = "read_only", canViewParticipants = false)),
    )
}
