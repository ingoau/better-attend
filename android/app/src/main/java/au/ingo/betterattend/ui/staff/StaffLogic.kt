package au.ingo.betterattend.ui.staff

import au.ingo.betterattend.data.model.StaffMember
import au.ingo.betterattend.data.model.StaffRole
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.util.Validation

/** Pure rules for the Event staff screen. */
object StaffLogic {
    /** Role order comes from the server's catalogue (most access first); unknown roles go last. Then by name. */
    fun sorted(staff: List<StaffMember>, roles: List<StaffRole>): List<StaffMember> {
        val order = roles.mapIndexed { i, r -> r.role to i }.toMap()
        return staff.sortedWith(
            compareBy<StaffMember>({ order[it.role] ?: Int.MAX_VALUE }, { it.role }, { it.user.displayName.lowercase() }, { it.user.email.lowercase() }),
        )
    }

    /** Sorted members grouped under their role's label, for section headers. */
    fun grouped(staff: List<StaffMember>, roles: List<StaffRole>): List<Pair<String, List<StaffMember>>> =
        sorted(staff, roles).groupBy { it.role }.map { (_, members) -> roleLabel(members.first(), roles) to members }

    fun roleLabel(member: StaffMember, roles: List<StaffRole>): String = roleLabel(member.role, roles, member.roleLabel)

    fun roleLabel(role: String, roles: List<StaffRole>, fallback: String? = null): String =
        roles.firstOrNull { it.role == role }?.label ?: fallback?.takeIf { it.isNotBlank() }
            ?: role.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** /me and the staff list both carry the email; ids differ between the two (user vs. assignment). */
    fun isSelf(member: StaffMember, me: User?): Boolean = me != null && member.user.email.equals(me.email.trim(), ignoreCase = true)

    /**
     * Would changing [member] to [newRole] (null = removing them) take away the signed-in user's
     * ability to manage staff here? Only event admins and series members manage staff (global
     * admins always can), so it's a self-demotion from event admin with no other such assignment.
     */
    fun losesStaffAccess(member: StaffMember, newRole: String?, me: User?, staff: List<StaffMember>): Boolean {
        if (!isSelf(member, me) || me?.globalAdmin == true || member.user.globalAdmin) return false
        if (member.role != "event_admin" || newRole == "event_admin") return false
        val keepsAnother = staff.any { it.id != member.id && isSelf(it, me) && (it.role == "event_admin" || it.inheritedFromSeries) }
        return !keepsAnother
    }

    /** An error to show, or null when the add form can be sent. */
    fun validateAdd(email: String, role: String?, roles: List<StaffRole>): String? = when {
        email.isBlank() -> "Enter their email address"
        !Validation.looksLikeEmail(email) -> "Enter a valid email address"
        role == null || roles.none { it.role == role } -> "Choose a role"
        else -> null
    }

    fun addedMessage(member: StaffMember, accountCreated: Boolean, roles: List<StaffRole>): String {
        val label = roleLabel(member, roles)
        return if (accountCreated) "Added ${member.user.email} as $label. They'll get an email and can sign in with Hack Club using this address."
        else "Added ${member.user.displayName} as $label"
    }
}
