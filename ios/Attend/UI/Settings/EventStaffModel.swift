import Foundation

/// State for the Event staff screen: who can work on one event, and the role catalogue the server
/// offers. Changes go straight to Attend (they need a connection and are never queued).
@MainActor
@Observable
final class EventStaffModel {
    let eventId: String

    /// nil until the first load answers.
    private(set) var staff: [StaffMember]?
    private(set) var roles: [StaffRole] = []
    private(set) var loading = false
    private(set) var error: String?
    /// Attend refused (the user's role changed since the event list loaded).
    private(set) var forbidden = false
    var toast: Toast?
    /// A longer message to show in an alert (e.g. after creating someone's account).
    var notice: String?

    init(eventId: String) {
        self.eventId = eventId
    }

    var sections: [StaffSection] { StaffLogic.sections(staff ?? [], roles: roles) }

    func roleLabel(_ role: String) -> String { StaffLogic.roleLabel(role, roles: roles) }

    /// Roles the person with this email already holds here (other than in `excluding`), which can't
    /// be picked for them again.
    func rolesHeld(byEmail email: String, excluding assignmentId: String? = nil) -> Set<String> {
        StaffLogic.rolesHeld(byEmail: email, in: staff ?? [], excluding: assignmentId)
    }

    // MARK: Loading

    func load(_ app: AppModel) async {
        loading = true
        defer { loading = false }
        do {
            let res = try await app.api.staff(eventId: eventId)
            staff = res.staff
            roles = res.roles
            error = nil
            forbidden = false
        } catch {
            guard !error.isCancellation else { return }
            forbidden = (error as? APIError)?.isForbidden == true
            self.error = error.friendlyMessage
        }
    }

    // MARK: Changes

    private static let offline = "You're offline. Managing staff needs a connection."

    /// Adds someone by email. Returns why it failed (for the sheet to show inline), or nil.
    func add(_ app: AppModel, email: String, role: String) async -> String? {
        guard app.isOnline else { return Self.offline }
        if rolesHeld(byEmail: email).contains(role) { return "They're already \(roleLabel(role)) on this event." }
        do {
            let res = try await app.api.addStaff(eventId: eventId, email: email.trimmingCharacters(in: .whitespacesAndNewlines), role: role)
            let member = res.staffMember
            staff = (staff ?? []).filter { $0.id != member.id } + [member]
            Haptics.confirm()
            toast = Toast(message: "Added \(member.user.displayName) as \(StaffLogic.roleLabel(member, roles: roles))",
                          systemImage: "person.badge.plus")
            if res.accountCreated { notice = StaffLogic.accountCreatedMessage }
            return nil
        } catch {
            guard !error.isCancellation else { return "Not added." }
            Haptics.reject()
            return error.friendlyMessage
        }
    }

    /// Changes someone's role. Returns why it failed, or nil.
    func changeRole(_ app: AppModel, member: StaffMember, role: String) async -> String? {
        guard app.isOnline else { return Self.offline }
        if rolesHeld(byEmail: member.user.email, excluding: member.id).contains(role) {
            return "They're already \(roleLabel(role)) on this event."
        }
        do {
            let updated = try await app.api.updateStaffRole(eventId: eventId, assignmentId: member.id, role: role)
            staff = (staff ?? []).map { $0.id == updated.id ? updated : $0 }
            Haptics.confirm()
            toast = Toast(message: "\(updated.user.displayName) is now \(StaffLogic.roleLabel(updated, roles: roles))",
                          systemImage: "person.crop.circle.badge.checkmark")
            if StaffLogic.isSelf(member, user: app.user) { await updateOwnAccess(app) }
            return nil
        } catch {
            guard !error.isCancellation else { return "Not changed." }
            Haptics.reject()
            return error.friendlyMessage
        }
    }

    /// Removes someone from the event's staff. Returns why it failed, or nil.
    func remove(_ app: AppModel, member: StaffMember) async -> String? {
        guard app.isOnline else { return Self.offline }
        do {
            try await app.api.removeStaff(eventId: eventId, assignmentId: member.id)
        } catch let api as APIError where api.isNotFound {
            // Already gone: same outcome.
        } catch {
            guard !error.isCancellation else { return "Not removed." }
            Haptics.reject()
            return error.friendlyMessage
        }
        staff?.removeAll { $0.id == member.id }
        Haptics.confirm()
        toast = Toast(message: "Removed \(member.user.displayName)", systemImage: "person.fill.xmark", tone: .neutral)
        if StaffLogic.isSelf(member, user: app.user) { await updateOwnAccess(app) }
        return nil
    }

    /// After changing your own assignment, the app's idea of your role (and with it what this screen
    /// and the rest of the app offer) changes straight away, worked out from your remaining rows;
    /// the event list then reloads in the background to confirm it.
    private func updateOwnAccess(_ app: AppModel) async {
        let events = app.events
        if let event = events.events?.first(where: { $0.id == eventId }),
           let updated = StaffLogic.ownAccess(after: staff ?? [], user: app.user, event: event) {
            await events.replace(updated)
        }
        Task { _ = try? await events.refresh() }
    }
}
