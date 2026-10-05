package au.ingo.betterattend.data.model

/**
 * What the signed-in user may do on an event, from [Event.role] (the highest-precedence role
 * upstream reports: global_admin, series_member, event_admin, safeguarding_lead, ops, limited,
 * read_only). Mirrors hackclub/attend's Pundit policies so the app only offers actions the server
 * will accept. Upstream reports a single role, so someone holding e.g. safeguarding_lead + ops is
 * judged on safeguarding_lead alone: we err towards hiding an action rather than offering one
 * that would fail.
 */
object EventPermissions {
    private val PARTICIPANT_API_ROLES = setOf("global_admin", "series_member", "event_admin", "safeguarding_lead", "ops", "limited")

    /** Roster access at all (EventPolicy#api_participants?). */
    fun canViewParticipants(event: Event?): Boolean =
        event != null && event.canViewParticipants && event.role in PARTICIPANT_API_ROLES

    /**
     * PATCH a participant: profile fields and withdraw/reinstate (ParticipantEventPolicy#can_edit?).
     * Direct event roles only: series members and safeguarding leads get 403.
     */
    fun canEditParticipant(event: Event?): Boolean =
        event?.role in setOf("global_admin", "event_admin", "ops", "limited")

    /** Phone and date of birth are writable by PII-restricted roles but not readable, so don't offer editing what they can't see. */
    fun canEditPii(event: Event?): Boolean = canEditParticipant(event) && event?.canViewParticipantPii == true

    /** POST participants (EventPolicy#invite_participants? → User#event_admin_for?, which includes series members). */
    fun canInviteParticipants(event: Event?): Boolean =
        event?.role in setOf("global_admin", "series_member", "event_admin")

    /** DELETE a participant (ParticipantEventPolicy#destroy?: direct event_admin or global admin). */
    fun canRemoveParticipants(event: Event?): Boolean =
        event?.role in setOf("global_admin", "event_admin")

    /** The /staff endpoints (EventPolicy#manage_staff? → User#event_admin_for?). */
    fun canManageStaff(event: Event?): Boolean =
        event?.role in setOf("global_admin", "series_member", "event_admin")

    /** Medical, dietary, safeguarding and guardian contact fields in the roster. */
    fun canViewSensitiveData(event: Event?): Boolean = event?.canViewSensitiveData == true

    /** Series-inherited assignments can't be removed or re-roled from the event (upstream answers 409). */
    fun canChangeStaffMember(member: StaffMember): Boolean = !member.inheritedFromSeries
}
