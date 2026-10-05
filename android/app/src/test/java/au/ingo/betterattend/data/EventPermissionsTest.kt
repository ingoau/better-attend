package au.ingo.betterattend.data

import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.StaffMember
import au.ingo.betterattend.data.model.StaffUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPermissionsTest {
    private fun e(role: String?, pii: Boolean = true, sensitive: Boolean = false, participants: Boolean = true) =
        Event(id = "e", name = "E", slug = "e", role = role, canViewParticipantPii = pii, canViewSensitiveData = sensitive, canViewParticipants = participants)

    private val roles = listOf("global_admin", "series_member", "event_admin", "safeguarding_lead", "ops", "limited", "read_only", null)

    private fun allowed(check: (Event) -> Boolean) = roles.filter { check(e(it)) }

    @Test fun editMatchesParticipantEventPolicyCanEdit() =
        assertEquals(listOf("global_admin", "event_admin", "ops", "limited"), allowed(EventPermissions::canEditParticipant))

    @Test fun inviteAndStaffIncludeSeriesMembers() {
        assertEquals(listOf("global_admin", "series_member", "event_admin"), allowed(EventPermissions::canInviteParticipants))
        assertEquals(listOf("global_admin", "series_member", "event_admin"), allowed(EventPermissions::canManageStaff))
    }

    @Test fun removeIsDirectEventAdminsOnly() =
        assertEquals(listOf("global_admin", "event_admin"), allowed(EventPermissions::canRemoveParticipants))

    @Test fun readOnlyCannotViewParticipants() {
        assertFalse(EventPermissions.canViewParticipants(e("read_only")))
        assertFalse(EventPermissions.canViewParticipants(e("ops", participants = false)))
        assertTrue(EventPermissions.canViewParticipants(e("safeguarding_lead")))
    }

    @Test fun piiEditNeedsPiiAccess() {
        assertFalse(EventPermissions.canEditPii(e("limited", pii = false)))
        assertTrue(EventPermissions.canEditPii(e("ops")))
        assertFalse(EventPermissions.canEditPii(e("safeguarding_lead")))
    }

    @Test fun nullEventAllowsNothing() {
        assertFalse(EventPermissions.canEditParticipant(null))
        assertFalse(EventPermissions.canManageStaff(null))
        assertFalse(EventPermissions.canViewSensitiveData(null))
    }

    @Test fun seriesInheritedStaffAreLocked() {
        val user = StaffUser(id = "u", email = "a@b.c")
        assertFalse(EventPermissions.canChangeStaffMember(StaffMember(id = "1", role = "event_admin", inheritedFromSeries = true, user = user)))
        assertTrue(EventPermissions.canChangeStaffMember(StaffMember(id = "2", role = "ops", user = user)))
    }
}
