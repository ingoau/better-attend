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
    /// managing staff. Global admins and series members keep it through their other role.
    static func losesStaffAccess(_ member: StaffMember, newRole: String?, user: User?, event: Event?) -> Bool {
        guard isSelf(member, user: user) else { return false }
        if user?.globalAdmin == true || member.user.globalAdmin { return false }
        if ["global_admin", "series_member"].contains(event?.role ?? "") { return false }
        guard let newRole else { return true }
        return newRole != "event_admin"
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
