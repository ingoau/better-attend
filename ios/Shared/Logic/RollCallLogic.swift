import Foundation

// Pure logic for roll call (muster): a headcount against a list frozen from the cached roster when
// the roll call starts. Optionally each tick is also recorded as a scan at a scan point the user
// picked (never chosen automatically). No UI imports, so it's unit tested directly.

/// Who the roll call expects to find.
enum RollCallExpected: String, Codable, CaseIterable, Hashable, Sendable, Identifiable {
    /// Active registrations that have checked in (the default).
    case checkedIn
    /// Every active registration.
    case everyone

    var id: String { rawValue }

    var label: String {
        switch self {
        case .checkedIn: "Checked in"
        case .everyone: "Everyone registered"
        }
    }

    var detail: String {
        switch self {
        case .checkedIn: "People who've checked in at the event."
        case .everyone: "Everyone who hasn't withdrawn, here or not."
        }
    }

    func includes(_ p: Participant) -> Bool {
        switch self {
        case .checkedIn: p.isActive && p.isCheckedIn
        case .everyone: p.isActive
        }
    }
}

/// The scan point ticks are recorded at. Only ever one the user picked.
struct RollCallScanPoint: Codable, Hashable, Sendable {
    var id: String
    var name: String
    var checksIn: Bool = false

    init(id: String, name: String, checksIn: Bool = false) {
        self.id = id
        self.name = name
        self.checksIn = checksIn
    }

    init(_ context: ScanContext) {
        self.init(id: context.id, name: context.name, checksIn: context.checksIn)
    }
}

/// One tick: who, and when they were accounted for.
struct RollCallTick: Codable, Hashable, Sendable {
    var participantEventId: String
    var at: String
}

/// A roll call in progress for one event. Persisted in the encrypted cache so it survives restarts.
/// Ids are kept in arrays (not dictionaries) so the snake_case cache coders never touch them.
struct RollCallSession: Codable, Hashable, Sendable {
    var eventId: String
    var startedAt: String
    var expected: RollCallExpected
    /// nil: ticks stay on this phone.
    var scanPoint: RollCallScanPoint?
    /// The participant_event ids expected when the roll call started, in list order.
    var expectedIds: [String]
    /// People who weren't on the expected list but were found ("Add someone"), in the order added.
    var addedIds: [String] = []
    var ticks: [RollCallTick] = []
    /// People whose scan at `scanPoint` this roll call created (or queued), so unticking only ever
    /// takes back a scan we made, never one they already had there.
    var recordedIds: [String] = []
    /// Unticked while offline: the scan we made is still recorded at the scan point.
    var stillRecordedIds: [String] = []

    var isRecordingScans: Bool { scanPoint != nil }

    /// Everyone on the list: expected, then added.
    var listIds: [String] { expectedIds + addedIds.filter { !expectedIds.contains($0) } }

    func isAccounted(_ id: String) -> Bool { ticks.contains { $0.participantEventId == id } }
    func tickTime(_ id: String) -> String? { ticks.first { $0.participantEventId == id }?.at }
    func isOnList(_ id: String) -> Bool { expectedIds.contains(id) || addedIds.contains(id) }
    func isAdded(_ id: String) -> Bool { addedIds.contains(id) && !expectedIds.contains(id) }

    /// Marks someone accounted for (or not). Unticking someone who was only added takes them off the
    /// list again: they were never expected. Returns true if anything changed.
    @discardableResult
    mutating func set(_ id: String, accounted: Bool, at: String) -> Bool {
        guard isOnList(id), accounted != isAccounted(id) else { return false }
        if accounted {
            ticks.append(RollCallTick(participantEventId: id, at: at))
        } else {
            ticks.removeAll { $0.participantEventId == id }
            if isAdded(id) { addedIds.removeAll { $0 == id } }
        }
        return true
    }

    /// Flips someone's tick. Returns their new state.
    @discardableResult
    mutating func toggle(_ id: String, at: String) -> Bool {
        let now = !isAccounted(id)
        set(id, accounted: now, at: at)
        return isAccounted(id)
    }

    /// "Add someone": marks a person present, putting them on the list if they weren't expected.
    mutating func add(_ id: String, at: String) {
        if !isOnList(id) { addedIds.append(id) }
        set(id, accounted: true, at: at)
    }

    mutating func noteRecorded(_ id: String) {
        if !recordedIds.contains(id) { recordedIds.append(id) }
        stillRecordedIds.removeAll { $0 == id }
    }

    mutating func noteUndone(_ id: String) {
        recordedIds.removeAll { $0 == id }
        stillRecordedIds.removeAll { $0 == id }
    }

    mutating func noteStillRecorded(_ id: String) {
        if !stillRecordedIds.contains(id) { stillRecordedIds.append(id) }
    }
}

/// A row on the roll call list.
struct RollCallEntry: Hashable, Sendable, Identifiable {
    var participant: Participant
    var accounted: Bool
    var accountedAt: String?
    /// Not on the expected list: added during the roll call.
    var added: Bool
    /// Unticked offline after their scan was recorded: it still stands at the scan point.
    var stillRecorded: Bool = false
    /// False when they've dropped out of the cached roster since the roll call started.
    var known: Bool = true
    var id: String { participant.participantEventId }
}

/// Missing / Accounted / All, above the list.
enum RollCallFilter: String, CaseIterable, Hashable, Sendable, Identifiable {
    case missing, accounted, all

    var id: String { rawValue }

    var label: String {
        switch self {
        case .missing: "Missing"
        case .accounted: "Accounted"
        case .all: "All"
        }
    }

    func matches(_ e: RollCallEntry) -> Bool {
        switch self {
        case .missing: !e.accounted
        case .accounted: e.accounted
        case .all: true
        }
    }

    var emptyTitle: String {
        switch self {
        case .missing: "Everyone's Accounted For"
        case .accounted: "No One Ticked Yet"
        case .all: "No One on the List"
        }
    }
}

struct RollCallCounts: Hashable, Sendable {
    /// Everyone on the list (expected + added).
    var total = 0
    var accounted = 0
    /// Added during the roll call (included in `total` and `accounted`).
    var added = 0

    var missing: Int { max(0, total - accounted) }
    var progress: Double { total == 0 ? 0 : Double(accounted) / Double(total) }
    var isComplete: Bool { total > 0 && missing == 0 }
}

enum RollCallLogic {
    /// The ids to expect, in name order: frozen when the roll call starts.
    static func expectedIds(_ participants: [Participant], expected: RollCallExpected) -> [String] {
        PeopleFilter.sorted(participants.filter(expected.includes), .name).map(\.participantEventId)
    }

    /// How many people each choice would expect, for the start screen.
    static func expectedCount(_ participants: [Participant], expected: RollCallExpected) -> Int {
        participants.count(where: expected.includes)
    }

    static func start(eventId: String, participants: [Participant], expected: RollCallExpected,
                      scanPoint: RollCallScanPoint?, now: Date = Date()) -> RollCallSession {
        RollCallSession(eventId: eventId, startedAt: Time.iso(now), expected: expected, scanPoint: scanPoint,
                        expectedIds: expectedIds(participants, expected: expected))
    }

    /// A stand-in for someone on the frozen list who's since dropped out of the cached roster.
    static func placeholder(_ id: String) -> Participant {
        Participant(participantId: id, participantEventId: id, displayName: "Unknown participant")
    }

    /// The list as rows, in list order, from the latest roster (so names and photos stay fresh).
    static func entries(_ session: RollCallSession, roster: [Participant]) -> [RollCallEntry] {
        let byId = Dictionary(roster.map { ($0.participantEventId, $0) }, uniquingKeysWith: { _, b in b })
        let ticks = Dictionary(session.ticks.map { ($0.participantEventId, $0.at) }, uniquingKeysWith: { a, _ in a })
        let still = Set(session.stillRecordedIds)
        let expected = Set(session.expectedIds)
        return session.listIds.map { id in
            RollCallEntry(participant: byId[id] ?? placeholder(id), accounted: ticks[id] != nil, accountedAt: ticks[id],
                          added: !expected.contains(id), stillRecorded: still.contains(id), known: byId[id] != nil)
        }
    }

    static func counts(_ entries: [RollCallEntry]) -> RollCallCounts {
        RollCallCounts(total: entries.count, accounted: entries.count(where: \.accounted), added: entries.count(where: \.added))
    }

    /// Search by name, email, pronouns or ticket code (same rules as the People list).
    static func filter(_ entries: [RollCallEntry], filter: RollCallFilter, query: String) -> [RollCallEntry] {
        entries.filter { filter.matches($0) && PeopleFilter.matchesQuery($0.participant, query) }
    }

    /// Count for each filter chip, after search.
    static func filterCounts(_ entries: [RollCallEntry], query: String) -> [RollCallFilter: Int] {
        let searched = entries.filter { PeopleFilter.matchesQuery($0.participant, query) }
        var out: [RollCallFilter: Int] = [:]
        for f in RollCallFilter.allCases { out[f] = searched.count(where: f.matches) }
        return out
    }

    /// "37 / 52 accounted for · 15 missing".
    static func headline(_ c: RollCallCounts) -> String {
        "\(c.accounted) / \(c.total) accounted for · \(c.missing) missing"
    }

    /// People to offer under "Add someone": active, matching the search, not already ticked. Name order.
    static func addCandidates(_ roster: [Participant], session: RollCallSession, query: String) -> [Participant] {
        let ticked = Set(session.ticks.map(\.participantEventId))
        return PeopleFilter.sorted(roster.filter { $0.isActive && !ticked.contains($0.participantEventId) && PeopleFilter.matchesQuery($0, query) }, .name)
    }

    /// Who's missing, ready to paste into a group chat.
    static func missingShareText(eventName: String, session: RollCallSession, entries: [RollCallEntry], tz: String?, now: Date = Date()) -> String {
        let c = counts(entries)
        let missing = entries.filter { !$0.accounted }
        let started = Time.time(session.startedAt, tz: tz).map { " (started \($0))" } ?? ""
        var lines = ["\(eventName) roll call\(started)", headline(c)]
        if missing.isEmpty {
            lines.append("")
            lines.append("Everyone is accounted for.")
        } else {
            lines.append("")
            lines.append("Missing (\(missing.count)):")
            for e in missing { lines.append("• \(PeopleText.listTitle(e.participant))") }
        }
        lines.append("")
        lines.append("As of \(Time.time(now, zone: Time.zone(tz)))")
        return lines.joined(separator: "\n")
    }

    /// The cache key for an event's roll call.
    static func cacheKey(_ eventId: String) -> String { "rollcall_\(eventId)" }
}
