import Foundation

/// Event staff for demo mode: the organizer (an event admin), a global admin, a series-inherited
/// admin (which can't be removed from the event) and one of each other role.
extension DemoData {
    /// Upstream's role catalogue (EventRoleAssignment::ROLE_DETAILS), most powerful first.
    static let staffRoles: [StaffRole] = [
        StaffRole(role: "event_admin", label: "Event Admin", summary: "Full control of this event."),
        StaffRole(role: "ops", label: "Ops", summary: "Day-to-day logistics and operations."),
        StaffRole(role: "limited", label: "Limited", summary: "Day-to-day logistics, without phone numbers, addresses, or exact birthdays."),
        StaffRole(role: "safeguarding_lead", label: "Safeguarding Lead", summary: "Welfare, medical, and safeguarding."),
        StaffRole(role: "read_only", label: "Read Only", summary: "View-only access — cannot make changes."),
    ]

    static func staffRoleLabel(_ role: String) -> String {
        staffRoles.first { $0.role == role }?.label ?? role
    }

    static func demoStaff(user: User, now: Date = Date()) -> [StaffMember] {
        func member(_ n: Int, _ role: String, _ name: String, _ email: String, globalAdmin: Bool = false,
                    seriesRole: String? = nil, days: Double) -> StaffMember {
            StaffMember(
                id: String(format: "5e0a%04x-1c2d-4e3f-8a9b-0c1d2e3f4a5b", n),
                role: role,
                roleLabel: staffRoleLabel(role),
                inheritedFromSeries: seriesRole != nil,
                seriesRole: seriesRole,
                createdAt: Time.iso(now.addingTimeInterval(-days * 86_400)),
                user: StaffUser(id: String(format: "u-staff-%02d", n), email: email, name: name, globalAdmin: globalAdmin)
            )
        }
        return [
            StaffMember(id: "5e0a0000-1c2d-4e3f-8a9b-0c1d2e3f4a5b", role: "event_admin", roleLabel: staffRoleLabel("event_admin"),
                        createdAt: Time.iso(now.addingTimeInterval(-60 * 86_400)),
                        user: StaffUser(id: user.id, email: user.email, name: user.name, globalAdmin: user.globalAdmin)),
            member(1, "event_admin", "Alex Rivera", "alex.rivera@example.com", globalAdmin: true, days: 58),
            member(2, "event_admin", "Jamie Chen", "jamie.chen@example.com", seriesRole: "organizer", days: 90),
            member(3, "ops", "Heidi Park", "heidi.park@example.com", days: 40),
            member(4, "ops", "Marcus Webb", "marcus.webb@example.com", days: 21),
            member(5, "limited", "Tom Baker", "tom.baker@example.com", days: 14),
            member(6, "safeguarding_lead", "Priya Rao", "priya.rao@example.com", days: 30),
            member(7, "read_only", "Casey Morgan", "casey.morgan@example.com", days: 7),
        ]
    }
}
