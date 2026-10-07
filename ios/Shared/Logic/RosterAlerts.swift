import Foundation

/// What changed between two syncs of a roster that organizers can be notified about.
struct RosterChanges: Sendable {
    var signups: [Participant] = []
    var withdrawals: [Participant] = []
    var isEmpty: Bool { signups.isEmpty && withdrawals.isEmpty }
}

/// Who signed up or withdrew between two syncs of a roster, and how a notification words it.
enum RosterAlerts {
    static func changes(old: Roster, new: Roster) -> RosterChanges {
        RosterChanges(signups: newSignups(old: old, new: new), withdrawals: newWithdrawals(old: old, new: new))
    }

    /// People who count as signed up in `new` but weren't on `old` at all, or were only invited by staff
    /// there and have now started registering. Staff invites, withdrawals, rejections and reinstatements
    /// aren't signups.
    static func newSignups(old: Roster, new: Roster) -> [Participant] {
        let before = old.byEventId
        return new.participants.filter { p in
            guard isSignedUp(p) else { return false }
            guard let was = before[p.participantEventId] else { return true }
            return was.status == "invited"
        }
    }

    /// People who were signed up in `old` and have withdrawn in `new`. Rejections are staff decisions,
    /// and invitees who never registered weren't coming, so neither counts.
    static func newWithdrawals(old: Roster, new: Roster) -> [Participant] {
        let before = old.byEventId
        return new.participants.filter { p in
            p.status == "withdrawn" && (before[p.participantEventId].map(isSignedUp) ?? false)
        }
    }

    private static func isSignedUp(_ p: Participant) -> Bool { p.isActive && p.status != "invited" }

    /// Only report changes measured against a roster synced after the alert was turned on (`since`): one
    /// cached from days earlier would announce everything that happened while it was off.
    static func isBaseline(since: String?, previousSyncAt: String?) -> Bool {
        guard let on = Time.parse(since), let previous = Time.parse(previousSyncAt) else { return false }
        return previous >= on
    }

    /// "New signup for Campfire" / "3 new signups for Campfire"
    static func signupTitle(count: Int, eventName: String?) -> String {
        forEvent(count == 1 ? "New signup" : "\(count) new signups", eventName)
    }

    /// "Withdrawal from Campfire" / "3 withdrawals from Campfire"
    static func withdrawalTitle(count: Int, eventName: String?) -> String {
        let what = count == 1 ? "Withdrawal" : "\(count) withdrawals"
        guard let eventName, !eventName.isBlank else { return what }
        return "\(what) from \(eventName)"
    }

    private static func forEvent(_ what: String, _ eventName: String?) -> String {
        guard let eventName, !eventName.isBlank else { return what }
        return "\(what) for \(eventName)"
    }

    /// "Mia Chen, Ollie Smith and 2 others"
    static func summary(_ names: [String], shown: Int = 3) -> String {
        if names.count <= 1 { return names.first ?? "" }
        if names.count <= shown { return names.dropLast().joined(separator: ", ") + " and " + names.last! }
        let rest = names.count - shown
        return names.prefix(shown).joined(separator: ", ") + " and \(rest) other\(rest == 1 ? "" : "s")"
    }
}
