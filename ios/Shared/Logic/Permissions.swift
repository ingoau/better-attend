import Foundation

/// What the signed-in user may do on an event, from `Event.role` (the highest-precedence role
/// upstream reports: global_admin, series_member, event_admin, safeguarding_lead, ops, limited,
/// read_only). Mirrors hackclub/attend's Pundit policies so the app only offers actions the server
/// will accept. Upstream reports a single role, so someone holding e.g. safeguarding_lead + ops is
/// judged on safeguarding_lead alone: we err towards hiding an action rather than offering one
/// that would fail.
enum EventPermissions {
    private static let participantAPIRoles: Set = ["global_admin", "series_member", "event_admin", "safeguarding_lead", "ops", "limited"]

    /// Roster access at all (EventPolicy#api_participants?).
    static func canViewParticipants(_ event: Event?) -> Bool {
        guard let event else { return false }
        return event.canViewParticipants && participantAPIRoles.contains(event.role ?? "")
    }

    /// Roster access on the event with this id, looked up in `events`. False when the event isn't
    /// known (e.g. an offline scan rejected for an event that's since left the list).
    static func canViewParticipants(eventId: String, in events: [Event]?) -> Bool {
        canViewParticipants(events?.first { $0.id == eventId })
    }

    /// PATCH a participant: profile fields and withdraw/reinstate (ParticipantEventPolicy#can_edit?).
    /// Direct event roles only: series members and safeguarding leads get 403.
    static func canEditParticipant(_ event: Event?) -> Bool {
        ["global_admin", "event_admin", "ops", "limited"].contains(event?.role ?? "")
    }

    /// Phone and date of birth are writable by PII-restricted roles but not readable, so don't offer
    /// editing what they can't see.
    static func canEditPII(_ event: Event?) -> Bool {
        canEditParticipant(event) && event?.canViewParticipantPii == true
    }

    /// POST participants (EventPolicy#invite_participants? → User#event_admin_for?, which includes series members).
    static func canInviteParticipants(_ event: Event?) -> Bool {
        ["global_admin", "series_member", "event_admin"].contains(event?.role ?? "")
    }

    /// DELETE a participant (ParticipantEventPolicy#destroy?: direct event_admin or global admin).
    static func canRemoveParticipants(_ event: Event?) -> Bool {
        ["global_admin", "event_admin"].contains(event?.role ?? "")
    }

    /// The /staff endpoints (EventPolicy#manage_staff? → User#event_admin_for?).
    static func canManageStaff(_ event: Event?) -> Bool {
        ["global_admin", "series_member", "event_admin"].contains(event?.role ?? "")
    }

    /// Medical, dietary, safeguarding and guardian contact fields in the roster.
    static func canViewSensitiveData(_ event: Event?) -> Bool { event?.canViewSensitiveData == true }

    /// Series-inherited assignments can't be removed or re-roled from the event (upstream answers 409).
    static func canChangeStaffMember(_ member: StaffMember) -> Bool { !member.inheritedFromSeries }
}
