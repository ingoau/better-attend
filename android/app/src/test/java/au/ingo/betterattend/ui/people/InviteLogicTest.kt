package au.ingo.betterattend.ui.people

import au.ingo.betterattend.data.model.InviteResult
import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteLogicTest {
    @Test fun emailIsRequiredAndChecked() {
        assertEquals("Enter their email address", InviteLogic.validate("  "))
        assertEquals("Enter a valid email address", InviteLogic.validate("jordan@"))
        assertNull(InviteLogic.validate(" Jordan@Example.com "))
    }

    @Test fun heldInvitationsSaySo() {
        val held = InviteResult(held = true, participantEventId = "pe")
        assertEquals("Invitation saved", InviteLogic.resultTitle(held))
        assertTrue(InviteLogic.resultBody(held, "a@b.co").contains("when the event releases invitations"))
        assertEquals("Invitation sent", InviteLogic.resultTitle(held.copy(held = false)))
    }

    @Test fun inviteButtonOnlyForAdmins() {
        val base = PeopleUiState(event = SampleData.event)
        assertTrue(base.canInvite) // event_admin
        assertTrue(base.copy(event = SampleData.event.copy(role = "series_member")).canInvite)
        assertTrue(base.copy(event = SampleData.event.copy(role = "global_admin")).canInvite)
        for (role in listOf("ops", "limited", "safeguarding_lead", "read_only", null)) {
            assertFalse(role ?: "null", base.copy(event = SampleData.event.copy(role = role)).canInvite)
        }
        assertFalse(PeopleUiState(event = null).canInvite)
    }
}
