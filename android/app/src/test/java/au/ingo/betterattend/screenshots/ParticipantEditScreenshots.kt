package au.ingo.betterattend.screenshots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import au.ingo.betterattend.ui.people.EditDetailsContent
import au.ingo.betterattend.ui.people.EditField
import au.ingo.betterattend.ui.people.EditSession
import au.ingo.betterattend.ui.people.ParticipantDetailContent
import au.ingo.betterattend.ui.people.ParticipantEditLogic
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Edit details, remove, and the participant overflow menu for each kind of role. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ParticipantEditScreenshots : ScreenshotTest() {
    private val now = DetailFixtures.now
    private val original = ParticipantEditLogic.formFrom(DetailFixtures.sensitive)
    private val admin = SampleData.event // event_admin

    @Test fun editForm() = snap("edit_details") {
        EditDetailsContent(
            EditSession(original, form = original.copy(preferredName = "Mae", pronouns = "she/they")),
            name = "Maya", canEditPii = true, onChange = {}, onSave = {}, onClose = {},
        )
    }

    @Test fun editFormServerError() = snap("edit_details_error") {
        EditDetailsContent(
            EditSession(
                original, form = original.copy(email = "sam@example.com"),
                error = "Email has already been taken", fieldErrors = mapOf(EditField.Email to "Email has already been taken"),
            ),
            name = "Maya", canEditPii = true, onChange = {}, onSave = {}, onClose = {},
        )
    }

    /** A `limited` staffer: phone and date of birth aren't offered (they can't read them). Non-standard size kept. */
    @Test fun editFormWithoutPii() = snap("edit_details_limited") {
        val o = ParticipantEditLogic.formFrom(DetailFixtures.limited).copy(tshirtSize = "Youth L")
        EditDetailsContent(EditSession(o, saving = true), name = "Maya", canEditPii = false, onChange = {}, onSave = {}, onClose = {})
    }

    private fun openMenu() {
        compose.onAllNodes(hasContentDescription("More options")).onFirst().performClick()
        compose.waitForIdle()
    }

    /** Event admin: Edit details first, Withdraw and the destructive Remove at the bottom. */
    @Test fun overflowMenuAdmin() = snap("detail_menu_admin", screen = true, prepare = ::openMenu) {
        ParticipantDetailContent(DetailFixtures.state(admin, DetailFixtures.sensitive), now = now)
    }

    /** Safeguarding lead: upstream refuses their edits, so no Edit / Withdraw / Remove at all. */
    @Test fun overflowMenuSafeguarding() = snap("detail_menu_safeguarding", screen = true, prepare = ::openMenu) {
        ParticipantDetailContent(DetailFixtures.state(DetailFixtures.eventSensitive, DetailFixtures.sensitive), now = now)
    }

    /** Limited: can edit details and withdraw, but not remove. */
    @Test fun overflowMenuLimited() = snap("detail_menu_limited", screen = true, prepare = ::openMenu) {
        ParticipantDetailContent(DetailFixtures.state(DetailFixtures.eventLimited, DetailFixtures.limited), now = now)
    }

    @Test fun removeDialog() = snap("detail_remove_dialog", screen = true, prepare = {
        openMenu()
        compose.onNodeWithText("Remove from event").performClick()
        compose.waitForIdle()
    }) {
        ParticipantDetailContent(DetailFixtures.state(admin, DetailFixtures.sensitive), now = now)
    }
}
