import Foundation

/// Who signed up between two syncs of a roster, and how a notification words it.
enum SignupLogic {
    /// People who count as signed up in `new` but didn't in `old`: registrations that weren't on the
    /// roster before, or that were only invited by staff and have now started registering. Staff
    /// invites, withdrawals and rejections aren't signups.
    static func newSignups(old: Roster, new: Roster) -> [Participant] {
        let before = old.byEventId
        return new.participants.filter { p in
            isSignedUp(p) && !(before[p.participantEventId].map(isSignedUp) ?? false)
        }
    }

    private static func isSignedUp(_ p: Participant) -> Bool { p.isActive && p.status != "invited" }

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
