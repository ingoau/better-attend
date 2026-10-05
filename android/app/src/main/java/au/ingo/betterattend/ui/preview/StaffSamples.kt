package au.ingo.betterattend.ui.preview

import au.ingo.betterattend.data.model.StaffMember
import au.ingo.betterattend.data.model.StaffRole
import au.ingo.betterattend.data.model.StaffUser

/** Campfire Canberra's staff, as `GET /events/:id/staff` returns it (previews and screenshot tests only). */
object StaffSamples {
    /** Upstream's catalogue (EventRoleAssignment::ROLE_DETAILS), in its order. */
    val roles = listOf(
        StaffRole("event_admin", "Event Admin", "Full control of this event."),
        StaffRole("ops", "Ops", "Day-to-day logistics and operations."),
        StaffRole("limited", "Limited", "Day-to-day logistics, without phone numbers, addresses, or exact birthdays."),
        StaffRole("safeguarding_lead", "Safeguarding Lead", "Welfare, medical, and safeguarding."),
        StaffRole("read_only", "Read Only", "View-only access — cannot make changes."),
    )

    private fun member(id: Int, role: String, name: String?, email: String, globalAdmin: Boolean = false, series: String? = null) = StaffMember(
        id = "$id", role = role, roleLabel = roles.firstOrNull { it.role == role }?.label,
        inheritedFromSeries = series != null, seriesRole = series, createdAt = "2026-08-0${id % 9 + 1}T02:00:00Z",
        user = StaffUser(id = "u$id", email = email, name = name, globalAdmin = globalAdmin),
    )

    /** Unsorted on purpose: upstream orders by email, the app by role then name. */
    val staff = listOf(
        member(7, "ops", "Heidi Hakkarainen", "heidi@hackclub.com"),
        member(1, "event_admin", "Orpheus Dino", "orpheus@hackclub.com"),
        member(3, "safeguarding_lead", "Priya Raman", "priya.raman@example.com"),
        member(4, "event_admin", "Sam Rivera", "sam.rivera@hackclub.com", globalAdmin = true),
        member(5, "read_only", null, "door.volunteer@example.com"),
        member(2, "event_admin", "Leah Mitchell", "leah@hackclub.com", series = "organizer"),
        member(6, "ops", "Tom Nguyen", "tom.nguyen@example.com"),
        member(8, "limited", "Grace Okafor", "grace.okafor@example.com"),
    )
}
