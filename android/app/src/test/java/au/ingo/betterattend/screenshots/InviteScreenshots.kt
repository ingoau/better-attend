package au.ingo.betterattend.screenshots

import au.ingo.betterattend.data.model.InviteResult
import au.ingo.betterattend.ui.people.InviteSheetContent
import au.ingo.betterattend.ui.people.InviteState
import au.ingo.betterattend.ui.people.PeopleContent
import au.ingo.betterattend.ui.people.PeopleUiState
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class InviteScreenshots : ScreenshotTest() {
    private val now = Instant.parse("2026-10-03T01:32:00Z")
    private val form = InviteState(email = "jordan.walkin@example.com", firstName = "Jordan", lastName = "Walker")
    private val sent = InviteResult(success = true, held = false, participantEventId = "pe-new", status = "invited")

    @Test fun inviteSheet() = snap("invite_sheet") { InviteSheetContent(form, {}, {}, {}, {}) }

    @Test fun inviteSheetConflict() = snap("invite_sheet_error") {
        InviteSheetContent(form.copy(error = "This email is already registered for this event"), {}, {}, {}, {})
    }

    @Test fun inviteSheetSending() = snap("invite_sheet_sending") { InviteSheetContent(form.copy(sending = true), {}, {}, {}, {}) }

    @Test fun inviteSent() = snap("invite_sheet_sent") { InviteSheetContent(form.copy(result = sent), {}, {}, {}, {}) }

    @Test fun inviteHeld() = snap("invite_sheet_held") { InviteSheetContent(form.copy(result = sent.copy(held = true)), {}, {}, {}, {}) }

    private val people = PeopleUiState(
        user = SampleData.user, eventsLoaded = true, event = SampleData.event,
        roster = SampleData.participants, contexts = SampleData.contexts, lastSyncAt = "2026-10-03T01:30:00Z",
    )

    /** Ops can browse the roster but not add to it: no invite button in the bar. */
    @Test fun peopleWithoutInvite() = snap("people_ops_no_invite") {
        PeopleContent(people.copy(event = SampleData.event.copy(role = "ops", canViewSensitiveData = false)), now)
    }
}
