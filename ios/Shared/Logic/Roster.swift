import Foundation

/// An event's cached participant list plus sync bookkeeping.
struct Roster: Codable, Hashable, Sendable {
    var eventId: String
    var participants: [Participant] = []
    /// Server cursor for `updated_since` delta syncs. nil = never fully synced (partial roster).
    var syncedAt: String?
    var lastFullSyncAt: String?
    var lastSyncAt: String?

    var byEventId: [String: Participant] {
        Dictionary(participants.map { ($0.participantEventId, $0) }, uniquingKeysWith: { _, b in b })
    }

    /// Finds by participant_event id, participant id, NFC token, or QR payload.
    func find(_ identifier: String) -> Participant? {
        var id = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["attend://checkin/", "attend:P:"] where id.hasPrefix(prefix) {
            id.removeFirst(prefix.count)
        }
        if let p = participants.first(where: { $0.participantEventId == id }) { return p }
        return participants.first {
            $0.participantId.caseInsensitiveCompare(id) == .orderedSame
                || ($0.nfcBadgeToken?.caseInsensitiveCompare(id) == .orderedSame)
        }
    }

    /// Newer list data wins, but detail-only blocks survive a list refresh.
    static func mergeKeepingDetail(_ old: Participant?, _ new: Participant) -> Participant {
        guard let old else { return new }
        var m = new
        m.personal = new.personal ?? old.personal
        m.accommodation = new.accommodation ?? old.accommodation
        m.consents = new.consents ?? old.consents
        m.guardians = new.guardians ?? old.guardians
        m.medicalDetail = new.medicalDetail ?? old.medicalDetail
        m.dietaryDetail = new.dietaryDetail ?? old.dietaryDetail
        m.accessibility = new.accessibility ?? old.accessibility
        m.safeguardingDetail = new.safeguardingDetail ?? old.safeguardingDetail
        m.groups = new.groups.isEmpty ? old.groups : new.groups
        m.tshirtSize = new.tshirtSize ?? old.tshirtSize
        m.headshotUrl = new.headshotUrl ?? old.headshotUrl
        return m
    }

    /// Roster order: by name, case-insensitively.
    static func sortedByName(_ list: [Participant]) -> [Participant] {
        list.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }
}

/// Counts for dashboards and widgets, all derived from the roster (the API has no stats endpoint).
struct EventStats: Hashable, Sendable {
    var registered = 0
    var confirmed = 0
    var checkedIn = 0
    /// People we expect to show up: confirmed registrations, or every active one when none are marked complete.
    var expected = 0
    /// Expected people who haven't checked in yet.
    var notArrived = 0
    var withdrawn = 0
    var byStatus: [String: Int] = [:]
    /// scan context id -> unique participants scanned there
    var perContext: [String: Int] = [:]
    var anaphylaxis = 0
    var highSupport = 0
    var checkedInLastHour = 0

    var progress: Double {
        expected == 0 ? 0 : Double(min(checkedIn, expected)) / Double(expected)
    }

    static func from(_ participants: [Participant], now: Date = Date()) -> EventStats {
        let active = participants.filter(\.isActive)
        let confirmed = active.filter { $0.status == "complete" }
        let checkedIn = active.filter(\.isCheckedIn)
        // Attend's status column lags reality (it can say "in_progress" for someone who's done), so
        // if nobody is marked complete yet, expect everyone who's registered rather than nobody.
        let basis = confirmed.isEmpty ? active : confirmed
        var perContext: [String: Int] = [:]
        for p in active {
            for s in p.scansByContext { perContext[s.scanContextId, default: 0] += 1 }
        }
        var byStatus: [String: Int] = [:]
        for p in participants { byStatus[p.status ?? "unknown", default: 0] += 1 }
        let hourAgo = now.addingTimeInterval(-3600)
        return EventStats(
            registered: active.count,
            confirmed: confirmed.count,
            checkedIn: checkedIn.count,
            expected: max(basis.count, checkedIn.count),
            notArrived: basis.count(where: { !$0.isCheckedIn }),
            withdrawn: participants.count - active.count,
            byStatus: byStatus,
            perContext: perContext,
            anaphylaxis: active.count(where: \.hasAnaphylaxisRisk),
            highSupport: active.count(where: \.highSupportFlag),
            checkedInLastHour: checkedIn.count(where: { (Time.parse($0.checkedInAt).map { $0 > hourAgo }) ?? false })
        )
    }
}
