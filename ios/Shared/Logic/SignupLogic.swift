import Foundation

/// Who signed up between two syncs of a roster, and how a notification words it.
enum SignupLogic {
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

    private static func isSignedUp(_ p: Participant) -> Bool { p.isActive && p.status != "invited" }

    /// Only report signups measured against a roster synced after the setting was turned on: one cached
    /// from days earlier would announce everyone who registered while it was off.
    static func shouldNotify(enabled: Bool, since: String?, previousSyncAt: String?) -> Bool {
        guard enabled, let since = Time.parse(since), let previous = Time.parse(previousSyncAt) else { return false }
        return previous >= since
    }

    /// "New signup for Campfire" / "3 new signups for Campfire"
    static func title(count: Int, eventName: String?) -> String {
        let what = count == 1 ? "New signup" : "\(count) new signups"
        guard let eventName, !eventName.isBlank else { return what }
        return "\(what) for \(eventName)"
    }

    /// "Mia Chen, Ollie Smith and 2 others"
    static func summary(_ people: [Participant], shown: Int = 3) -> String {
        let names = people.map(\.name)
        if names.count <= 1 { return names.first ?? "" }
        if names.count <= shown { return names.dropLast().joined(separator: ", ") + " and " + names.last! }
        let rest = names.count - shown
        return names.prefix(shown).joined(separator: ", ") + " and \(rest) other\(rest == 1 ? "" : "s")"
    }
}
