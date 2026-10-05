import Foundation

/// One role's group on the Event staff screen.
struct StaffSection: Hashable, Sendable, Identifiable {
    var role: String
    var title: String
    var summary: String?
    var members: [StaffMember]
    var id: String { role }
}

/// Pure helpers for the Event staff screen (sorting, labels, self-demotion warnings).
enum StaffLogic {
    /// Role order when the server's catalogue doesn't list a role (it normally lists them all,
    /// most powerful first).
    private static let fallbackOrder = ["event_admin", "ops", "limited", "safeguarding_lead", "read_only"]

    private static func rank(_ role: String, roles: [StaffRole]) -> Int {
        if let i = roles.firstIndex(where: { $0.role == role }) { return i }
        if let i = fallbackOrder.firstIndex(of: role) { return roles.count + i }
        return roles.count + fallbackOrder.count
    }

    /// By role (catalogue order), then name, case-insensitively.
    static func sorted(_ staff: [StaffMember], roles: [StaffRole]) -> [StaffMember] {
        staff.sorted { a, b in
            let ra = rank(a.role, roles: roles), rb = rank(b.role, roles: roles)
            if ra != rb { return ra < rb }
            if a.role != b.role { return a.role < b.role }
            let na = a.user.displayName.lowercased(), nb = b.user.displayName.lowercased()
            if na != nb { return na < nb }
            return a.user.email.lowercased() < b.user.email.lowercased()
        }
    }

    /// Sorted staff grouped by role, one section per role that has anyone in it.
    static func sections(_ staff: [StaffMember], roles: [StaffRole]) -> [StaffSection] {
        var out: [StaffSection] = []
        for m in sorted(staff, roles: roles) {
            if let last = out.indices.last, out[last].role == m.role {
                out[last].members.append(m)
            } else {
                let catalogue = roles.first { $0.role == m.role }
                out.append(StaffSection(role: m.role, title: catalogue?.label ?? roleLabel(m, roles: roles),
                                        summary: catalogue?.summary, members: [m]))
            }
        }
        return out
    }

    /// The server's label for the member's role, else the catalogue's, else a humanized role.
    static func roleLabel(_ member: StaffMember, roles: [StaffRole]) -> String {
        if let label = member.roleLabel?.nonBlank { return label }
        return roleLabel(member.role, roles: roles)
    }

    static func roleLabel(_ role: String, roles: [StaffRole]) -> String {
        roles.first { $0.role == role }?.label ?? role.replacingOccurrences(of: "_", with: " ").capitalizedFirst
    }

    /// "Series owner" / "Series organizer" for an inherited assignment.
    static func seriesLabel(_ member: StaffMember) -> String? {
        guard member.inheritedFromSeries else { return nil }
        guard let role = member.seriesRole?.nonBlank else { return "From series" }
        return "Series \(role.replacingOccurrences(of: "_", with: " "))"
    }

    /// The signed-in user's own row (emails compared case-insensitively).
    static func isSelf(_ member: StaffMember, user: User?) -> Bool {
        guard let user else { return false }
        return member.user.email.trimmingCharacters(in: .whitespaces).lowercased() == user.email.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// True when changing your own row to `newRole` (nil = removing it) would take away your access to
    /// managing staff: it's your event admin row, and you have no other event admin or series row
    /// here. Global admins and series members keep it through their other role.
    static func losesStaffAccess(_ member: StaffMember, newRole: String?, user: User?, event: Event?, staff: [StaffMember]) -> Bool {
        guard isSelf(member, user: user) else { return false }
        if user?.globalAdmin == true || member.user.globalAdmin { return false }
        if ["global_admin", "series_member"].contains(event?.role ?? "") { return false }
        guard member.role == "event_admin", newRole != "event_admin" else { return false }
        let keepsAnother = staff.contains { $0.id != member.id && isSelf($0, user: user) && ($0.role == "event_admin" || $0.inheritedFromSeries) }
        return !keepsAnother
    }

    /// Roles the person with `email` already holds on this event, in rows other than `excluding`.
    /// Attend allows one assignment per role per person, so these can't be picked for them again.
    static func rolesHeld(byEmail email: String, in staff: [StaffMember], excluding assignmentId: String? = nil) -> Set<String> {
        let wanted = normalizedEmail(email)
        guard !wanted.isEmpty else { return [] }
        return Set(staff.filter { $0.id != assignmentId && normalizedEmail($0.user.email) == wanted }.map(\.role))
    }

    /// "Already Ops": why a role can't be picked for someone.
    static func alreadyHolds(_ role: String, roles: [StaffRole]) -> String { "Already \(roleLabel(role, roles: roles))" }

    private static func normalizedEmail(_ email: String) -> String { email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }

    /// Upstream's order when someone holds several roles on one event (EventsController::ROLE_PRECEDENCE).
    static let rolePrecedence = ["event_admin", "safeguarding_lead", "ops", "limited", "read_only"]
    private static let participantAPIRoles: Set = ["event_admin", "ops", "limited", "safeguarding_lead"]

    /// The signed-in user's standing on `event` worked out from their rows left in `staff`, the way
    /// Attend's event list reports it (role by precedence, plus the capability flags), so the app can
    /// act on a change to their own row straight away. No role and no access when none are left.
    /// nil when their own rows don't decide it (global admins and series members keep theirs).
    static func ownAccess(after staff: [StaffMember], user: User?, event: Event) -> Event? {
        guard let user, !user.globalAdmin, !["global_admin", "series_member"].contains(event.role ?? "") else { return nil }
        let mine = staff.filter { isSelf($0, user: user) }
        if mine.contains(where: \.inheritedFromSeries) { return nil }
        let held = Set(mine.map(\.role))
        var e = event
        e.role = rolePrecedence.first { held.contains($0) } ?? held.sorted().first
        e.canViewParticipants = !held.isDisjoint(with: participantAPIRoles)
        e.canViewParticipantPii = held.contains { $0 != "limited" }
        e.canViewSensitiveData = held.contains("safeguarding_lead")
        return e
    }

    /// Whether a staff email entry is plausible enough to send.
    static func emailProblem(_ email: String) -> String? {
        let e = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if e.isEmpty { return "Enter their email address." }
        return ParticipantEditLogic.looksLikeEmail(e) ? nil : "Enter a valid email address."
    }

    /// Shown after adding someone who had never signed in to Attend.
    static let accountCreatedMessage = "They'll get an email and can sign in with Hack Club using this address."
}
