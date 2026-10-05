package au.ingo.betterattend.screenshots

import au.ingo.betterattend.ui.firstaid.FirstAidContent
import au.ingo.betterattend.ui.firstaid.FirstAidFilter
import au.ingo.betterattend.ui.firstaid.FirstAidUiState
import au.ingo.betterattend.ui.preview.OrganizerSamples
import au.ingo.betterattend.ui.preview.SafetySamples
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FirstAidScreenshots : ScreenshotTest() {
    private val base = FirstAidUiState(eventsLoaded = true, event = SampleData.event, roster = SafetySamples.firstAidRoster, rosterRead = true)

    private fun snapState(name: String, state: FirstAidUiState, filter: FirstAidFilter? = null) = snap(name) {
        FirstAidContent(
            state = state, filter = filter, query = "", onFilter = {}, onQuery = {}, onBack = {}, onRefresh = {},
            onOpen = {}, onCall = {}, onPrint = { _, _ -> }, now = OrganizerSamples.now,
        )
    }

    /** A safeguarding lead / admin: medical details and contacts. */
    @Test fun sensitive() = snapState("firstaid_sensitive", base)

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun sensitiveEveryone() = snapState("firstaid_sensitive_everyone", base, FirstAidFilter.Everyone)

    /** Ops: flags only, with a calm notice instead of medical text. */
    @Test fun withoutSensitive() = snapState(
        "firstaid_flags_only",
        base.copy(
            event = SampleData.event.copy(role = "ops", canViewSensitiveData = false),
            // What the server sends this role: no medical fields at all.
            roster = SafetySamples.firstAidRoster.copy(
                participants = SafetySamples.firstAidRoster.participants.map {
                    it.copy(
                        allergies = null, medicalConditions = null, medications = null, lifeThreateningAllergies = null, dietType = null,
                        emergencyContacts = null, parentGuardianName = null, parentGuardianPhone = null, parentGuardianEmail = null,
                    )
                },
            ),
        ),
        FirstAidFilter.Everyone,
    )

    @Test fun empty() = snapState(
        "firstaid_empty",
        base.copy(
            roster = SafetySamples.firstAidRoster.copy(
                participants = SampleData.participants.map {
                    it.copy(hasAnaphylaxisRisk = false, requiresRefrigeration = false, highSupportFlag = false, allergies = null)
                },
            ),
        ),
    )

    @Test fun noAccess() = snapState("firstaid_no_access", base.copy(event = SampleData.event.copy(role = "read_only", canViewParticipants = false)))
}
