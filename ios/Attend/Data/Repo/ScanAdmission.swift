import Foundation

/// The result of checking a scan against what's cached on this device.
enum Precheck: Hashable, Sendable {
    /// Nothing in the cache says no. The participant is the roster match, if any.
    case pass(Participant?)
    /// `detail` is the other event's name (`.wrongEvent`) or the first scan time (`.alreadyCheckedIn`).
    case block(RejectReason, Participant?, detail: String? = nil)

    var participant: Participant? {
        switch self {
        case .pass(let p), .block(_, let p, _): p
        }
    }
}

/// Who may be admitted, decided from a participant record (port of Android's `ScanAdmission`).
/// Attend records a scan for anyone on the event (withdrawn people included), so the app applies
/// these rules itself: to the fresh record the server returns with each scan (online) and to the
/// cached roster (offline pre-check).
enum ScanAdmission {
    /// Past this, the offline warning about the roster's age gets louder.
    static let staleRoster: TimeInterval = 3600

    /// What stops `p` being admitted, or nil. A missing waiver only blocks at checkpoints that check
    /// people in: someone already inside still gets lunch.
    static func problem(_ p: Participant, checksIn: Bool) -> RejectReason? {
        if p.status == "withdrawn" { return .withdrawn }
        if p.status == "rejected" { return .registrationRejected }
        if checksIn && !p.waiverSigned { return .consentMissing }
        return nil
    }

    /// Checks an offline scan against the cached roster before it's queued.
    /// - Parameters:
    ///   - roster: this event's cached roster (nil if never synced, e.g. no access to the participant list).
    ///   - contextId: the selected checkpoint; nil skips the "already checked in" check.
    ///   - pending: scans already queued, so the same ticket scanned twice offline isn't queued twice.
    ///   - otherRosters: cached rosters of the user's other events, by event name, for "wrong event".
    static func precheck(_ input: ScanInput, roster: Roster?, contextId: String?, checksIn: Bool,
                         pending: [PendingScan] = [], otherRosters: [String: Roster] = [:]) -> Precheck {
        guard let code = input.badgeToken ?? input.participantId else { return .pass(nil) }
        // A roster that's never had a full sync is partial: it can't prove someone isn't registered.
        guard let roster, roster.syncedAt != nil else { return .pass(roster?.find(code)) }
        guard let p = roster.find(code) else {
            // Badges can be written after the last sync, so an unknown badge token isn't proof.
            if input.badgeToken != nil { return .pass(nil) }
            for (name, other) in otherRosters.sorted(by: { $0.key < $1.key }) {
                if let match = other.find(code) { return .block(.wrongEvent, match, detail: name) }
            }
            return .block(.notRegistered, nil)
        }
        if let problem = problem(p, checksIn: checksIn) { return .block(problem, p) }
        if let contextId {
            if let here = p.scansByContext.first(where: { $0.scanContextId == contextId }) {
                return .block(.alreadyCheckedIn, p, detail: here.firstScannedAt)
            }
            let queued = pending.first { q in
                q.eventId == roster.eventId && q.scanContextId == contextId
                    && (q.input.badgeToken ?? q.input.participantId).flatMap(roster.find)?.participantEventId == p.participantEventId
            }
            if let queued { return .block(.alreadyCheckedIn, p, detail: queued.scannedAt) }
        }
        return .pass(p)
    }

    /// When `roster` was last synced (ISO), or nil if there's no complete roster on this device.
    static func rosterTime(_ roster: Roster?) -> String? {
        guard let roster, roster.syncedAt != nil else { return nil }
        return roster.lastSyncAt ?? roster.syncedAt
    }

    /// "Roster from 14 min ago" for a roster synced at `rosterAt`; nil when there's no roster.
    static func rosterAgeLabel(_ rosterAt: String?, now: Date = Date()) -> String? {
        guard let ago = Time.ago(rosterAt, now: now) else { return nil }
        return ago == "just now" ? "Roster synced just now" : "Roster from \(ago)"
    }

    /// True when there's no roster or it's older than `staleRoster`.
    static func isRosterStale(_ rosterAt: String?, now: Date = Date()) -> Bool {
        guard let date = Time.parse(rosterAt) else { return true }
        return now.timeIntervalSince(date) > staleRoster
    }
}
