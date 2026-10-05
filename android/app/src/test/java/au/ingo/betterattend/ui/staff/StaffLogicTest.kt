package au.ingo.betterattend.ui.staff

import au.ingo.betterattend.data.api.AttendJson
import au.ingo.betterattend.data.model.StaffResponse
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.preview.StaffSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffLogicTest {
    private val roles = StaffSamples.roles
    private val staff = StaffSamples.staff
    private val me = SampleData.user // orpheus@hackclub.com, an event admin in the samples
    private val orpheus = staff.first { it.user.email == "orpheus@hackclub.com" }

    @Test fun sortsByCatalogueRoleThenName() {
        assertEquals(
            listOf(
                "Leah Mitchell", "Orpheus Dino", "Sam Rivera", // Event Admin
                "Heidi Hakkarainen", "Tom Nguyen", // Ops
                "Grace Okafor", // Limited
                "Priya Raman", // Safeguarding Lead
                "door.volunteer", // Read Only (no name: falls back to the email's local part)
            ),
            StaffLogic.sorted(staff, roles).map { it.user.displayName },
        )
    }

    @Test fun unknownRolesSortLast() {
        val odd = orpheus.copy(id = "99", role = "caterer", roleLabel = null, user = orpheus.user.copy(name = "Aaron"))
        assertEquals("Aaron", StaffLogic.sorted(staff + odd, roles).last().user.displayName)
        assertEquals("Caterer", StaffLogic.roleLabel(odd, roles))
    }

    @Test fun groupsUnderRoleLabels() {
        val groups = StaffLogic.grouped(staff, roles)
        assertEquals(listOf("Event Admin", "Ops", "Limited", "Safeguarding Lead", "Read Only"), groups.map { it.first })
        assertEquals(3, groups.first().second.size)
    }

    @Test fun selfIsMatchedByEmail() {
        assertTrue(StaffLogic.isSelf(orpheus, me))
        assertTrue(StaffLogic.isSelf(orpheus, me.copy(email = "Orpheus@HackClub.com")))
        assertFalse(StaffLogic.isSelf(staff.first { it.user.name == "Heidi Hakkarainen" }, me))
        assertFalse(StaffLogic.isSelf(orpheus, null))
    }

    @Test fun demotingOrRemovingYourselfLosesStaffAccess() {
        assertTrue(StaffLogic.losesStaffAccess(orpheus, "ops", me, staff))
        assertTrue(StaffLogic.losesStaffAccess(orpheus, null, me, staff))
        assertFalse(StaffLogic.losesStaffAccess(orpheus, "event_admin", me, staff))
        // Someone else, or a global admin (who always keeps access), isn't warned.
        assertFalse(StaffLogic.losesStaffAccess(staff.first { it.user.name == "Tom Nguyen" }, null, me, staff))
        assertFalse(StaffLogic.losesStaffAccess(orpheus, "ops", me.copy(globalAdmin = true), staff))
        // Already not an admin here: nothing to lose.
        val ops = orpheus.copy(role = "ops")
        assertFalse(StaffLogic.losesStaffAccess(ops, null, me, listOf(ops)))
        // Still a series member through another assignment: keeps access.
        val viaSeries = orpheus.copy(id = "77", role = "ops", inheritedFromSeries = true, seriesRole = "organizer")
        assertFalse(StaffLogic.losesStaffAccess(orpheus, "ops", me, staff + viaSeries))
    }

    @Test fun addValidation() {
        assertEquals("Enter their email address", StaffLogic.validateAdd("", "ops", roles))
        assertEquals("Enter a valid email address", StaffLogic.validateAdd("heidi", "ops", roles))
        assertEquals("Choose a role", StaffLogic.validateAdd("new@example.com", null, roles))
        assertEquals("Choose a role", StaffLogic.validateAdd("new@example.com", "owner", roles))
        assertNull(StaffLogic.validateAdd("new@example.com", "ops", roles))
    }

    @Test fun addedMessageExplainsNewAccounts() {
        val m = staff.first { it.user.name == "Tom Nguyen" }
        assertEquals("Added Tom Nguyen as Ops", StaffLogic.addedMessage(m, accountCreated = false, roles))
        assertTrue(StaffLogic.addedMessage(m, accountCreated = true, roles).contains("can sign in with Hack Club using this address"))
    }

    @Test fun decodesUpstreamPayloadWithNumericIds() {
        // Upstream serializes assignment and user ids as integers.
        val json = """{"staff":[{"id":12,"role":"ops","role_label":"Ops","inherited_from_series":false,"series_role":null,
            "created_at":"2026-08-01T00:00:00Z","user":{"id":34,"email":"a@b.co","name":"A B","global_admin":false}}],
            "roles":[{"role":"event_admin","label":"Event Admin","summary":"Full control of this event."}]}"""
        val res = AttendJson.decodeFromString(StaffResponse.serializer(), json)
        assertEquals("12", res.staff.single().id)
        assertEquals("34", res.staff.single().user.id)
        assertEquals("Event Admin", res.roles.single().label)
    }
}
