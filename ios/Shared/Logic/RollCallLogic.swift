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

/// What the roll call knows about the scan behind someone's tick, when ticks are recorded as scans.
/// The rule is "own the scan or leave it alone": unticking only ever takes back a scan the roll call
/// is sure it made, because Attend's undo deletes every scan the person has at the scan point.
enum RollCallScanState: String, Codable, Hashable, Sendable {
    /// Ticked; the scan is on its way (or the app closed before Attend answered, so it's uncertain).
    case sending
    /// Waiting in the offline queue (`clientScanId`): nothing has reached Attend yet.
    case queued
    /// Attend confirmed the roll call made their first scan at the scan point (`clientScanId`).
    case recorded
    /// They already had a scan there before the roll call ticked them: it's never taken back.
    case preExisting
    /// Unticked, but the scan at the scan point stays: it couldn't safely be taken back.
    case kept
    /// Recording the tick as a scan failed or was refused, so nothing was recorded.
    case notRecorded
}

/// One person's scan bookkeeping (see `RollCallScanState`).
struct RollCallScanRecord: Codable, Hashable, Sendable {
    var participantEventId: String
    var state: RollCallScanState
    var clientScanId: String?
    /// The roster already showed a scan at the scan point when they were ticked.
    @Default<False> var hadEarlierScan: Bool = false
    /// Queued because Attend took too long to answer: the request may have landed anyway.
    @Default<False> var timedOut: Bool = false
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
    /// What happened to each tick's scan at `scanPoint`, so unticking only ever takes back a scan
    /// this roll call made, never one they already had there.
    @Default<Empty<RollCallScanRecord>> var scans: [RollCallScanRecord] = []

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

    // MARK: Scan bookkeeping

    func scanRecord(_ id: String) -> RollCallScanRecord? { scans.first { $0.participantEventId == id } }

    func scanState(_ id: String) -> RollCallScanState? { scanRecord(id)?.state }

    /// Records what happened to someone's scan; nil forgets it (nothing of ours is at the scan point).
    mutating func noteScan(_ id: String, _ state: RollCallScanState?, clientScanId: String? = nil, timedOut: Bool = false) {
        guard let state else {
            scans.removeAll { $0.participantEventId == id }
            return
        }
        if let i = scans.firstIndex(where: { $0.participantEventId == id }) {
            scans[i].state = state
            scans[i].clientScanId = clientScanId
            scans[i].timedOut = timedOut
        } else {
            scans.append(RollCallScanRecord(participantEventId: id, state: state, clientScanId: clientScanId, timedOut: timedOut))
        }
    }

    /// Bookkeeping at the moment someone is ticked (the scan itself follows in the background).
    /// - Parameter hadEarlierScan: the cached roster already shows them scanned at the scan point.
    mutating func noteTicked(_ id: String, hadEarlierScan: Bool) {
        switch scanState(id) {
        case nil, .notRecorded:
            scans.removeAll { $0.participantEventId == id }
            scans.append(RollCallScanRecord(participantEventId: id, state: .sending, hadEarlierScan: hadEarlierScan))
        case .kept, .preExisting:
            // A scan is already standing there, and it isn't one the roll call can vouch for.
            noteScan(id, .preExisting)
        case .sending, .queued, .recorded:
            // Unticked a moment ago and that untick hasn't run yet: it sorts this out when it does.
            break
        }
    }

    /// Bookkeeping at the moment someone is unticked. A failed recording has nothing to take back.
    mutating func noteUnticked(_ id: String) {
        if scanState(id) == .notRecorded { noteScan(id, nil) }
    }
}

/// Something the roll call's background work wants to tell whoever is looking (a toast).
struct RollCallNotice: Hashable, Sendable, Identifiable {
    var id = UUID()
    var eventId: String
    var message: String
    var isError = false
}

/// A row on the roll call list.
struct RollCallEntry: Hashable, Sendable, Identifiable {
    var participant: Participant
    var accounted: Bool
    var accountedAt: String?
    /// Not on the expected list: added during the roll call.
    var added: Bool
    /// Unticked, but a scan of theirs still stands at the scan point (one they already had, or one
    /// the roll call couldn't safely take back).
    var stillRecorded: Bool = false
    /// Ticked, but recording it as a scan failed or was refused.
    var notRecorded: Bool = false
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
    /// Ticked, but the scan wasn't recorded (included in `accounted`).
    var notRecorded = 0

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
        let states = Dictionary(session.scans.map { ($0.participantEventId, $0.state) }, uniquingKeysWith: { a, _ in a })
        let expected = Set(session.expectedIds)
        return session.listIds.map { id in
            let accounted = ticks[id] != nil
            let state = session.scanPoint == nil ? nil : states[id]
            return RollCallEntry(participant: byId[id] ?? placeholder(id), accounted: accounted, accountedAt: ticks[id],
                                 added: !expected.contains(id),
                                 stillRecorded: !accounted && (state == .kept || state == .preExisting),
                                 notRecorded: accounted && state == .notRecorded,
                                 known: byId[id] != nil)
        }
    }

    static func counts(_ entries: [RollCallEntry]) -> RollCallCounts {
        RollCallCounts(total: entries.count, accounted: entries.count(where: \.accounted), added: entries.count(where: \.added),
                       notRecorded: entries.count(where: \.notRecorded))
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

    /// The participant has at least one scan at the scan point (per their record).
    static func hasScan(_ p: Participant?, at scanPointId: String) -> Bool {
        p?.scansByContext.contains { $0.scanContextId == scanPointId && $0.scanCount > 0 } ?? false
    }

    /// How many scans the participant has at the scan point (per their record).
    static func scanCount(_ p: Participant, at scanPointId: String) -> Int {
        p.scansByContext.first { $0.scanContextId == scanPointId }?.scanCount ?? 0
    }

    /// The setup screen's footer once a scan point is picked.
    static func recordingFooter(_ scanPointName: String) -> String {
        "Each tick is recorded as a scan at \(scanPointName), and waits to sync if you're offline. "
            + "Unticking takes back a scan only when the roll call made the only scan there; earlier scans are never removed."
    }
}

/// What the roll call tells staff when a tick's scan couldn't be recorded or taken back.
enum RollCallText {
    static func notRecorded(_ name: String, at point: String, reason: String) -> String {
        "\(name) is ticked, but no scan was recorded at \(point): \(reason)"
    }

    static func keptOffline(_ name: String, at point: String) -> String {
        "You're offline, so \(name)'s scan at \(point) stays recorded."
    }

    static func keptUnchecked(_ name: String, at point: String, reason: String) -> String {
        "Couldn't check \(name)'s scans at \(point), so the scan stays: \(reason)"
    }

    static func keptOthers(_ name: String, at point: String) -> String {
        "\(name) has other scans at \(point), so the roll call's scan stays."
    }

    static func keptSynced(_ name: String, at point: String) -> String {
        "\(name)'s scan at \(point) had already synced, so it stays recorded."
    }

    static func keptUncertain(_ name: String, at point: String) -> String {
        "\(name)'s scan at \(point) may have reached Attend, so it stays recorded."
    }

    static func keptTimedOut(_ name: String, at point: String) -> String {
        "\(name)'s scan at \(point) may have reached Attend before it timed out, so any scan there stays."
    }

    static func keptUndoFailed(_ name: String, at point: String, reason: String) -> String {
        "Couldn't take back \(name)'s scan at \(point), so it stays: \(reason)"
    }
}
