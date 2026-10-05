import Foundation
import Testing
@testable import Attend

private func member(_ id: String, _ name: String, here: Bool = true, status: String = "complete",
                    pronouns: String? = nil, anaphylaxis: Bool = false) -> Participant {
    Participant(participantId: "p-\(id)", participantEventId: id, displayName: String(name.split(separator: " ")[0]), fullName: name,
                email: "\(id)@example.com", pronouns: pronouns, status: status,
                checkedInAt: here ? "2026-10-03T00:00:00Z" : nil, hasAnaphylaxisRisk: anaphylaxis)
}

/// Zoe (here), Arjun (here), Maya (not here), Leo (here, withdrawn), Priya (here, awaiting guardian).
private let roster = [
    member("pe-zoe", "Zoe Adams", pronouns: "she/her"),
    member("pe-arjun", "Arjun Patel", anaphylaxis: true),
    member("pe-maya", "Maya Chen", here: false),
    member("pe-leo", "Leo Nguyen", status: "withdrawn"),
    member("pe-priya", "Priya Sharma", status: "awaiting_guardian"),
]

private let at = "2026-10-03T01:30:00Z"

@Suite struct RollCallLogicTests {
    private func session(_ expected: RollCallExpected = .checkedIn, scanPoint: RollCallScanPoint? = nil) -> RollCallSession {
        RollCallLogic.start(eventId: "e1", participants: roster, expected: expected, scanPoint: scanPoint, now: Fixtures.now)
    }

    @Test func checkedInExpectsActiveArrivalsInNameOrder() {
        #expect(RollCallLogic.expectedIds(roster, expected: .checkedIn) == ["pe-arjun", "pe-priya", "pe-zoe"])
        #expect(RollCallLogic.expectedCount(roster, expected: .checkedIn) == 3)
    }

    @Test func everyoneExpectsEveryActiveRegistration() {
        #expect(RollCallLogic.expectedIds(roster, expected: .everyone) == ["pe-arjun", "pe-maya", "pe-priya", "pe-zoe"])
        #expect(RollCallLogic.expectedCount(roster, expected: .everyone) == 4)
    }

    @Test func startFreezesTheListAndRecordsChoices() {
        let point = RollCallScanPoint(id: "c3", name: "Saturday lunch")
        let s = session(.checkedIn, scanPoint: point)
        #expect(s.eventId == "e1")
        #expect(s.startedAt == Time.iso(Fixtures.now))
        #expect(s.scanPoint == point)
        #expect(s.isRecordingScans)
        #expect(s.ticks.isEmpty)
        // Maya checks in after the roll call started: the list doesn't move.
        var later = roster
        later[2].checkedInAt = "2026-10-03T01:00:00Z"
        let entries = RollCallLogic.entries(s, roster: later)
        #expect(entries.map(\.id) == ["pe-arjun", "pe-priya", "pe-zoe"])
    }

    @Test func phoneOnlyHasNoScanPoint() {
        let s = session()
        #expect(s.scanPoint == nil)
        #expect(!s.isRecordingScans)
    }

    @Test func togglingTicksAndUnticks() {
        var s = session()
        let ticked = s.toggle("pe-zoe", at: at)
        #expect(ticked)
        #expect(s.isAccounted("pe-zoe"))
        #expect(s.tickTime("pe-zoe") == at)
        let unticked = s.toggle("pe-zoe", at: at)
        #expect(!unticked)
        #expect(!s.isAccounted("pe-zoe"))
        // Setting the same state twice changes nothing; people off the list can't be ticked by set.
        let first = s.set("pe-arjun", accounted: true, at: at)
        let again = s.set("pe-arjun", accounted: true, at: at)
        let offList = s.set("pe-maya", accounted: true, at: at)
        #expect(first)
        #expect(!again)
        #expect(!offList)
        #expect(s.ticks.count == 1)
    }

    @Test func countsAndHeadline() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let c = RollCallLogic.counts(RollCallLogic.entries(s, roster: roster))
        #expect(c.total == 3)
        #expect(c.accounted == 1)
        #expect(c.missing == 2)
        #expect(c.added == 0)
        #expect(!c.isComplete)
        #expect(abs(c.progress - 1.0 / 3.0) < 0.0001)
        #expect(RollCallLogic.headline(c) == "1 / 3 accounted for · 2 missing")
        #expect(RollCallCounts().progress == 0)
    }

    @Test func addingSomeoneOffTheListMarksThemPresent() {
        var s = session()
        s.add("pe-maya", at: at)
        #expect(s.addedIds == ["pe-maya"])
        #expect(s.isAccounted("pe-maya"))
        #expect(s.isAdded("pe-maya"))
        let entries = RollCallLogic.entries(s, roster: roster)
        #expect(entries.map(\.id) == ["pe-arjun", "pe-priya", "pe-zoe", "pe-maya"])
        #expect(entries.last?.added == true)
        let c = RollCallLogic.counts(entries)
        #expect(c.total == 4)
        #expect(c.accounted == 1)
        #expect(c.added == 1)
        // Unticking someone who was only added takes them off the list again.
        s.toggle("pe-maya", at: at)
        #expect(s.addedIds.isEmpty)
        #expect(RollCallLogic.entries(s, roster: roster).count == 3)
    }

    @Test func addingSomeoneExpectedJustTicksThem() {
        var s = session()
        s.add("pe-zoe", at: at)
        #expect(s.addedIds.isEmpty)
        #expect(s.isAccounted("pe-zoe"))
        #expect(!s.isAdded("pe-zoe"))
    }

    @Test func addCandidatesAreActiveUntickedMatches() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let all = RollCallLogic.addCandidates(roster, session: s, query: "").map(\.participantEventId)
        #expect(all == ["pe-arjun", "pe-maya", "pe-priya"]) // no Zoe (ticked), no Leo (withdrawn)
        #expect(RollCallLogic.addCandidates(roster, session: s, query: "chen").map(\.participantEventId) == ["pe-maya"])
    }

    @Test func filtersAndSearch() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let entries = RollCallLogic.entries(s, roster: roster)
        #expect(RollCallLogic.filter(entries, filter: .missing, query: "").map(\.id) == ["pe-arjun", "pe-priya"])
        #expect(RollCallLogic.filter(entries, filter: .accounted, query: "").map(\.id) == ["pe-zoe"])
        #expect(RollCallLogic.filter(entries, filter: .all, query: "").count == 3)
        #expect(RollCallLogic.filter(entries, filter: .all, query: "patel").map(\.id) == ["pe-arjun"])
        #expect(RollCallLogic.filter(entries, filter: .accounted, query: "patel").isEmpty)
        #expect(RollCallLogic.filterCounts(entries, query: "") == [.missing: 2, .accounted: 1, .all: 3])
        #expect(RollCallLogic.filterCounts(entries, query: "zoe") == [.missing: 0, .accounted: 1, .all: 1])
    }

    @Test func someoneMissingFromTheRosterGetsAPlaceholder() {
        let s = session()
        let entries = RollCallLogic.entries(s, roster: roster.filter { $0.participantEventId != "pe-priya" })
        let priya = entries.first { $0.id == "pe-priya" }
        #expect(priya?.known == false)
        #expect(priya?.participant.name == "Unknown participant")
        #expect(entries.first { $0.id == "pe-zoe" }?.known == true)
    }

    @Test func missingShareTextListsOnlyMissingPeople() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let text = RollCallLogic.missingShareText(eventName: "Campfire Sydney", session: s,
                                                  entries: RollCallLogic.entries(s, roster: roster), tz: "Australia/Sydney", now: Fixtures.now)
        #expect(text.hasPrefix("Campfire Sydney roll call (started "))
        #expect(text.contains("1 / 3 accounted for · 2 missing"))
        #expect(text.contains("Missing (2):"))
        #expect(text.contains("• Arjun Patel"))
        #expect(text.contains("• Priya Sharma"))
        #expect(!text.contains("Zoe"))
    }

    @Test func shareTextWhenEveryoneIsAccountedFor() {
        var s = session()
        for id in s.expectedIds { s.toggle(id, at: at) }
        let entries = RollCallLogic.entries(s, roster: roster)
        #expect(RollCallLogic.counts(entries).isComplete)
        let text = RollCallLogic.missingShareText(eventName: "Campfire", session: s, entries: entries, tz: nil, now: Fixtures.now)
        #expect(text.contains("Everyone is accounted for."))
        #expect(!text.contains("Missing ("))
    }

    @Test func scanBookkeepingAtTickAndUntick() {
        var s = session(scanPoint: RollCallScanPoint(id: "c1", name: "Desk", checksIn: true))
        s.toggle("pe-zoe", at: at)
        s.noteTicked("pe-zoe", hadEarlierScan: true)
        #expect(s.scanRecord("pe-zoe") == RollCallScanRecord(participantEventId: "pe-zoe", state: .sending, hadEarlierScan: true))
        s.noteScan("pe-zoe", .recorded, clientScanId: "cs-1")
        #expect(s.scanRecord("pe-zoe")?.clientScanId == "cs-1")
        #expect(s.scanRecord("pe-zoe")?.hadEarlierScan == true, "kept across updates")
        // Ticking again before the untick's job ran leaves the record for that job to settle.
        s.noteTicked("pe-zoe", hadEarlierScan: false)
        #expect(s.scanState("pe-zoe") == .recorded)

        // A scan that stayed (or was already there) is never the roll call's to take back.
        s.noteScan("pe-zoe", .kept)
        s.noteTicked("pe-zoe", hadEarlierScan: false)
        #expect(s.scanState("pe-zoe") == .preExisting)

        // A failed recording has nothing to take back, and ticking again retries it.
        s.noteScan("pe-arjun", .notRecorded)
        s.noteUnticked("pe-arjun")
        #expect(s.scanRecord("pe-arjun") == nil)
        s.noteScan("pe-arjun", .notRecorded)
        s.noteTicked("pe-arjun", hadEarlierScan: false)
        #expect(s.scanState("pe-arjun") == .sending)
        s.noteScan("pe-arjun", nil)
        #expect(s.scans.map(\.participantEventId) == ["pe-zoe"])
    }

    @Test func rowsShowScansThatStayedAndTicksThatWerentRecorded() {
        var s = session(scanPoint: RollCallScanPoint(id: "c1", name: "Desk", checksIn: true))
        s.toggle("pe-arjun", at: at)
        s.noteScan("pe-arjun", .notRecorded)
        s.noteScan("pe-zoe", .kept)
        s.noteScan("pe-priya", .preExisting)
        let entries = RollCallLogic.entries(s, roster: roster)
        let byId = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        #expect(byId["pe-arjun"]?.notRecorded == true)
        #expect(byId["pe-arjun"]?.stillRecorded == false)
        #expect(byId["pe-zoe"]?.stillRecorded == true)
        #expect(byId["pe-priya"]?.stillRecorded == true, "unticked, with the scan they already had")
        #expect(RollCallLogic.counts(entries).notRecorded == 1)
        // Phone-only roll calls never show scan states.
        var phoneOnly = s
        phoneOnly.scanPoint = nil
        #expect(RollCallLogic.entries(phoneOnly, roster: roster).allSatisfy { !$0.stillRecorded && !$0.notRecorded })
    }

    @Test func recordingFooterIsTruthfulAboutUnticking() {
        let footer = RollCallLogic.recordingFooter("Desk")
        #expect(footer.contains("Each tick is recorded as a scan at Desk"))
        #expect(footer.contains("Unticking takes back a scan only when the roll call made the only scan there; earlier scans are never removed."))
    }

    @Test func scanPointFromContext() {
        let p = RollCallScanPoint(Fixtures.contexts[0])
        #expect(p == RollCallScanPoint(id: "c1", name: "Check-in desk", checksIn: true))
    }

    @Test func sessionRoundTripsThroughTheCacheCoders() throws {
        // Ids with capitals and underscores would be mangled as dictionary keys by the snake_case
        // coders; the session keeps them in arrays.
        var s = RollCallLogic.start(eventId: "e1", participants: [member("PE_Mixed-Case", "Ana Ruiz"), member("pe-zoe", "Zoe Adams")],
                                    expected: .everyone, scanPoint: RollCallScanPoint(id: "c_1", name: "Desk", checksIn: true), now: Fixtures.now)
        s.toggle("PE_Mixed-Case", at: at)
        s.add("Added_ID", at: at)
        s.noteScan("PE_Mixed-Case", .recorded, clientScanId: "cs-1")
        s.noteScan("pe-zoe", .queued, clientScanId: "cs-2", timedOut: true)
        let data = try AttendJSON.encoder().encode(s)
        let back = try AttendJSON.decoder().decode(RollCallSession.self, from: data)
        #expect(back == s)
        #expect(back.isAccounted("PE_Mixed-Case"))
        #expect(back.expected == .everyone)
        #expect(back.scanRecord("pe-zoe")?.timedOut == true)
    }

    @Test func sessionsSavedBeforeScanRecordsStillLoad() throws {
        let json = #"{"event_id":"e1","started_at":"2026-10-03T01:00:00Z","expected":"checkedIn","expected_ids":["pe-zoe"],"added_ids":[],"ticks":[]}"#
        let s = try AttendJSON.decoder().decode(RollCallSession.self, from: Data(json.utf8))
        #expect(s.scans.isEmpty)
        #expect(s.expectedIds == ["pe-zoe"])
    }

    @Test func cacheKeyIsPerEvent() {
        #expect(RollCallLogic.cacheKey("e1") == "rollcall_e1")
    }
}

// MARK: - Store

/// A roll call store over a cache, with scan plumbing pointed at the stub Attend below.
@MainActor
private func makeStore(_ cache: JsonCache, scanTimeout: Duration = .milliseconds(400)) -> (RollCallStore, ScanRepository, ParticipantRepository) {
    let api = AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "https://attend.test")!, session: RollCallServer.session())
    let participants = ParticipantRepository(api: api, cache: cache)
    let scans = ScanRepository(api: api, cache: cache, participants: participants, scanTimeout: scanTimeout)
    return (RollCallStore(cache: cache, scans: scans, participants: participants), scans, participants)
}

@MainActor
@Suite(.serialized) struct RollCallStoreTests {
    let directory = FileManager.default.temporaryDirectory.appending(path: "rollcall-tests-\(UUID().uuidString)")

    @Test func survivesARestartAndEnds() async {
        let cache = JsonCache(directory: directory, cipher: IdentityCipher())
        let (store, _, _) = makeStore(cache)
        var s = RollCallLogic.start(eventId: "e1", participants: roster, expected: .checkedIn, scanPoint: nil, now: Fixtures.now)
        store.start(s)
        store.update("e1") { $0.toggle("pe-zoe", at: at) }
        s.toggle("pe-zoe", at: at)
        await store.waitForWrites()

        // A fresh store (the app relaunched) reads it back.
        let (relaunched, _, _) = makeStore(cache)
        #expect(relaunched.session("e1") == nil)
        let restored = await relaunched.load("e1")
        let other = await relaunched.load("e2")
        #expect(restored == s)
        #expect(other == nil)

        relaunched.end("e1")
        await relaunched.waitForWrites()
        let afterEnd = await makeStore(cache).0.load("e1")
        #expect(afterEnd == nil)
    }

    @Test func updateWithoutASessionDoesNothing() {
        let (store, _, _) = makeStore(JsonCache(directory: directory, cipher: IdentityCipher()))
        store.update("e1") { $0.toggle("pe-zoe", at: at) }
        #expect(store.session("e1") == nil)
        #expect(!store.set("e1", roster[0], accounted: true))
    }

    @Test func withoutEncryptionItLastsOnlyInMemory() async {
        let cache = JsonCache(directory: directory, cipher: nil)
        let (store, _, _) = makeStore(cache)
        let s = RollCallLogic.start(eventId: "e1", participants: roster, expected: .everyone, scanPoint: nil, now: Fixtures.now)
        store.start(s)
        await store.waitForWrites()
        #expect(store.session("e1") == s)
        let fromDisk = await makeStore(cache).0.load("e1")
        #expect(fromDisk == nil)
    }

    @Test func clearForgetsEverything() async {
        let (store, _, _) = makeStore(JsonCache(directory: directory, cipher: IdentityCipher()))
        store.start(RollCallLogic.start(eventId: "e1", participants: roster, expected: .everyone, scanPoint: nil, now: Fixtures.now))
        await store.clear()
        #expect(store.session("e1") == nil)
    }

    @Test func nothingIsWrittenBackAfterSignOutWipesTheCache() async {
        let cache = JsonCache(directory: directory, cipher: IdentityCipher())
        let (store, _, _) = makeStore(cache)
        store.start(RollCallLogic.start(eventId: "e1", participants: roster, expected: .everyone, scanPoint: nil, now: Fixtures.now))
        // Several writes still queued when the user signs out.
        for id in ["pe-zoe", "pe-arjun", "pe-maya"] { store.update("e1") { $0.toggle(id, at: at) } }
        await store.clear()
        await cache.clear()
        // Edits after sign-out go nowhere (there's no session), and nothing queued earlier lands late.
        store.update("e1") { $0.toggle("pe-priya", at: at) }
        await store.waitForWrites()
        try? await Task.sleep(for: .milliseconds(50))
        let fromDisk = await makeStore(cache).0.load("e1")
        #expect(fromDisk == nil)
    }

    @Test func phoneOnlyTicksSendNothing() async {
        let (store, scans, _) = makeStore(JsonCache(directory: directory, cipher: IdentityCipher()))
        store.start(RollCallLogic.start(eventId: "e1", participants: roster, expected: .checkedIn, scanPoint: nil, now: Fixtures.now))
        #expect(store.set("e1", roster[0], accounted: true))
        #expect(!store.set("e1", roster[0], accounted: true), "already ticked")
        #expect(store.set("e1", roster[0], accounted: false))
        await store.waitForScans()
        #expect(scans.log.isEmpty)
        #expect(store.session("e1")?.scans.isEmpty == true)
    }
}

// MARK: - Recording ticks as scans

/// A small stateful Attend for roll call scans: counts scans per person and scan point like
/// upstream's ScanRecorder (every scan is a new row; the first is "scanned", later ones
/// "already_scanned"), and DELETE removes all of them, like upstream's undo.
final class RollCallServer: URLProtocol {
    struct State {
        var people: [String: Participant] = [:]
        /// "participantEventId|scanContextId" -> scan count
        var counts: [String: Int] = [:]
        var seen: Set<String> = []
        var offline = false
        /// Seconds before a scan POST is answered (it's recorded straight away, like a slow server).
        var scanDelay: TimeInterval = 0
        var scanStatus = 200
        var requests: [String] = []
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var state = State()

    static func reset(_ people: [Participant] = []) {
        lock.withLock {
            state = State()
            for p in people { state.people[p.participantEventId] = p }
        }
    }

    static func update(_ change: (inout State) -> Void) { lock.withLock { change(&state) } }
    static func recorded() -> [String] { lock.withLock { state.requests } }
    static func count(_ pe: String, _ ctx: String) -> Int { lock.withLock { state.counts["\(pe)|\(ctx)"] ?? 0 } }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RollCallServer.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    private let stopped = NSLock()
    nonisolated(unsafe) private var isStopped = false

    override func stopLoading() { stopped.withLock { isStopped = true } }

    override func startLoading() {
        let url = request.url!
        let path = url.path.replacingOccurrences(of: "/api/v1", with: "")
        let method = request.httpMethod ?? "GET"
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let body = Self.body(of: request)
        let (status, data, delay, offline) = Self.lock.withLock { () -> (Int, Data, TimeInterval, Bool) in
            Self.state.requests.append("\(method) \(path)\(url.query.map { "?\($0)" } ?? "")")
            if Self.state.offline { return (0, Data(), 0, true) }
            return Self.handle(method: method, path: path, query: query, body: body)
        }
        nonisolated(unsafe) let proto = self
        let deliver: @Sendable () -> Void = {
            guard !proto.stopped.withLock({ proto.isStopped }) else { return }
            if offline {
                proto.client?.urlProtocol(proto, didFailWithError: URLError(.notConnectedToInternet))
                return
            }
            let res = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            proto.client?.urlProtocol(proto, didReceive: res, cacheStoragePolicy: .notAllowed)
            proto.client?.urlProtocol(proto, didLoad: data)
            proto.client?.urlProtocolDidFinishLoading(proto)
        }
        if delay > 0 { DispatchQueue.global().asyncAfter(deadline: .now() + delay, execute: deliver) } else { deliver() }
    }

    /// Called with the lock held.
    private static func handle(method: String, path: String, query: [URLQueryItem], body: [String: Any]) -> (Int, Data, TimeInterval, Bool) {
        let parts = path.split(separator: "/").map(String.init)
        func encode<T: Encodable>(_ value: T, status: Int = 200, delay: TimeInterval = 0) -> (Int, Data, TimeInterval, Bool) {
            (status, (try? AttendJSON.encoder().encode(value)) ?? Data(), delay, false)
        }
        func withScans(_ p: Participant) -> Participant {
            var p = p
            p.scansByContext = state.counts.compactMap { key, n in
                let k = key.split(separator: "|").map(String.init)
                guard k[0] == p.participantEventId, n > 0 else { return nil }
                return ContextScanSummary(scanContextId: k[1], scanContextName: k[1], scanCount: n, firstScannedAt: "2026-10-03T00:00:00Z")
            }
            return p
        }
        let notFound: (Int, Data, TimeInterval, Bool) = (404, Data(#"{"error":"Participant not found"}"#.utf8), 0, false)
        // POST /events/e1/scans
        if method == "POST", parts.count == 3, parts[2] == "scans" {
            guard state.scanStatus == 200 else { return (state.scanStatus, Data(#"{"error":"Invalid scan context"}"#.utf8), 0, false) }
            let pe = (body["participant_id"] as? String) ?? ""
            let ctx = (body["scan_context_id"] as? String) ?? ""
            guard let p = state.people[pe] else { return notFound }
            let key = "\(pe)|\(ctx)"
            let clientId = body["client_scan_id"] as? String
            let deduplicated = clientId.map { !state.seen.insert($0).inserted } ?? false
            if !deduplicated { state.counts[key, default: 0] += 1 }
            let first = state.counts[key] == 1
            let result = ScanResult(outcome: first ? "scanned" : "already_scanned", firstScanInContext: first,
                                    firstScannedAt: "2026-10-03T00:00:00Z", deduplicated: deduplicated,
                                    scan: Scan(id: UUID().uuidString, scannedAt: "2026-10-03T00:00:00Z"),
                                    scanContext: ScanContextRef(id: ctx, name: ctx), participant: withScans(p))
            return encode(result, delay: state.scanDelay)
        }
        // GET /events/e1/participants/<pe>
        if method == "GET", parts.count == 4, parts[2] == "participants" {
            guard let p = state.people[parts[3]] else { return notFound }
            return encode(ParticipantResponse(participant: withScans(p)))
        }
        // DELETE /events/e1/scans/<pe>?scan_context_id=<ctx>: every scan they have there
        if method == "DELETE", parts.count == 4, parts[2] == "scans" {
            let ctx = query.first { $0.name == "scan_context_id" }?.value ?? ""
            let key = "\(parts[3])|\(ctx)"
            let deleted = state.counts[key] ?? 0
            state.counts[key] = nil
            return encode(UndoResult(deletedScans: deleted, participantEventId: parts[3], scanContextId: ctx))
        }
        return notFound
    }

    private static func body(of request: URLRequest) -> [String: Any] {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                data.append(buffer, count: n)
            }
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }
}

/// Ticks recorded as scans at a scan point, against `RollCallServer`. The rule under test: unticking
/// never deletes a scan the roll call didn't make (upstream's undo deletes every scan at the point).
@MainActor
@Suite(.serialized) struct RollCallScanTests {
    let directory = FileManager.default.temporaryDirectory.appending(path: "rollcall-scan-tests-\(UUID().uuidString)")
    let cache: JsonCache
    let point = RollCallScanPoint(id: "c9", name: "Evening roll call", checksIn: false)
    let zoe = roster[0]

    init() {
        cache = JsonCache(directory: directory, cipher: IdentityCipher())
        RollCallServer.reset(roster)
    }

    /// Seeds the cached roster (optionally with scans already at the scan point) and starts a roll call.
    private func begin(timeout: Duration = .milliseconds(400), scannedHere: [String] = []) async
        -> (RollCallStore, ScanRepository, ParticipantRepository) {
        let people = roster.map { p in
            var p = p
            if scannedHere.contains(p.participantEventId) {
                p.scansByContext = [ContextScanSummary(scanContextId: point.id, scanCount: 1, firstScannedAt: "2026-10-02T09:00:00Z")]
            }
            return p
        }
        let synced = Time.iso(Date().addingTimeInterval(-60))
        await cache.write("roster_e1", Roster(eventId: "e1", participants: people, syncedAt: synced, lastSyncAt: synced))
        let (store, scans, participants) = makeStore(cache, scanTimeout: timeout)
        await participants.load("e1")
        store.start(RollCallLogic.start(eventId: "e1", participants: roster, expected: .checkedIn, scanPoint: point, now: Fixtures.now))
        return (store, scans, participants)
    }

    private var deletes: [String] { RollCallServer.recorded().filter { $0.hasPrefix("DELETE") } }

    /// Waits (up to 3 s) until Attend has received a scan for someone, i.e. the tick is in flight.
    private func waitUntilAttendHasAScan(_ id: String) async {
        for _ in 0..<150 where RollCallServer.count(id, point.id) == 0 {
            try? await Task.sleep(for: .milliseconds(20))
        }
    }
    private var posts: [String] { RollCallServer.recorded().filter { $0.hasPrefix("POST") } }

    private func entry(_ store: RollCallStore, _ id: String) -> RollCallEntry? {
        store.session("e1").flatMap { RollCallLogic.entries($0, roster: roster).first { $0.id == id } }
    }

    @Test func untickTakesBackTheRollCallsOwnOnlyScan() async {
        let (store, _, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .recorded)
        #expect(RollCallServer.count(zoe.id, point.id) == 1)

        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(RollCallServer.recorded().contains("GET /events/e1/participants/\(zoe.id)"), "checked Attend first")
        #expect(deletes == ["DELETE /events/e1/scans/\(zoe.id)?scan_context_id=\(point.id)"])
        #expect(RollCallServer.count(zoe.id, point.id) == 0)
        #expect(store.session("e1")?.scanRecord(zoe.id) == nil)
        #expect(entry(store, zoe.id)?.stillRecorded == false)
    }

    @Test func untickAfterAlreadyScannedDeletesNothing() async {
        // Attend already has a scan there that the cached roster doesn't know about.
        RollCallServer.update { $0.counts["\(zoe.id)|c9"] = 1 }
        let (store, _, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .preExisting)

        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(deletes.isEmpty)
        #expect(RollCallServer.count(zoe.id, point.id) == 2, "their scan and the roll call's both stand")
        #expect(entry(store, zoe.id)?.stillRecorded == true)
    }

    @Test func untickWhenTheRosterAlreadyHadAScanThereDeletesNothing() async {
        // The roster says they were scanned there before; even if Attend answers "scanned", it's left alone.
        let (store, _, _) = await begin(scannedHere: [zoe.id])
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(posts.count == 1, "the tick is still recorded")
        #expect(store.session("e1")?.scanState(zoe.id) == .preExisting)

        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(deletes.isEmpty)
        #expect(RollCallServer.count(zoe.id, point.id) == 1)
        #expect(entry(store, zoe.id)?.stillRecorded == true)

        // Ticking them again doesn't make that scan the roll call's.
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .preExisting)
    }

    @Test func untickOfAConfirmedFirstScanKeepsItWhenAttendNowShowsTwo() async {
        let (store, _, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .recorded)
        // Someone else scanned them there since.
        RollCallServer.update { $0.counts["\(zoe.id)|c9", default: 0] += 1 }

        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(deletes.isEmpty)
        #expect(RollCallServer.count(zoe.id, point.id) == 2)
        #expect(store.session("e1")?.scanState(zoe.id) == .kept)
        #expect(entry(store, zoe.id)?.stillRecorded == true)
        #expect(store.notice?.message == RollCallText.keptOthers(zoe.name, at: point.name))
    }

    @Test func untickWhileOfflineKeepsTheScanAndSaysSo() async {
        let (store, _, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        RollCallServer.update { $0.offline = true }
        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(deletes.isEmpty)
        #expect(store.session("e1")?.scanState(zoe.id) == .kept)
        #expect(store.notice?.message == RollCallText.keptOffline(zoe.name, at: point.name))
        #expect(store.notice?.isError == false)
    }

    @Test func queuedTickIsDiscardedNeverDeleted() async {
        RollCallServer.update { $0.offline = true }
        let (store, scans, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .queued)
        #expect(scans.pending.count == 1)

        RollCallServer.update { $0.offline = false }
        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(scans.pending.isEmpty, "dropped from the queue")
        #expect(store.session("e1")?.scanRecord(zoe.id) == nil)
        #expect(await scans.flush() == 0)
        #expect(deletes.isEmpty)
        #expect(posts.count == 1, "only the attempt that never got through")
        #expect(RollCallServer.count(zoe.id, point.id) == 0)
        #expect(entry(store, zoe.id)?.stillRecorded == false)
    }

    @Test func queuedTickThatSyncedMeanwhileStays() async {
        RollCallServer.update { $0.offline = true }
        let (store, scans, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        RollCallServer.update { $0.offline = false }
        #expect(await scans.flush() == 0)
        #expect(RollCallServer.count(zoe.id, point.id) == 1)

        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(deletes.isEmpty)
        #expect(store.session("e1")?.scanState(zoe.id) == .kept)
        #expect(entry(store, zoe.id)?.stillRecorded == true)
    }

    @Test func slowSubmitThenUntickIsNeverSilentlySynced() async {
        // Attend records the scan but answers after the timeout, so the tick is queued.
        RollCallServer.update { $0.scanDelay = 1.5 }
        let (store, scans, _) = await begin(timeout: .milliseconds(200))
        store.set("e1", zoe, accounted: true)
        // Unticked while the scan is still in flight: the untick waits for it.
        await waitUntilAttendHasAScan(zoe.id)
        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(scans.pending.isEmpty, "the queued copy is dropped, so it can't sync later")
        #expect(deletes.isEmpty)
        // The slow request did land, so the row says the scan stays.
        #expect(RollCallServer.count(zoe.id, point.id) == 1)
        #expect(store.session("e1")?.scanState(zoe.id) == .kept)
        #expect(entry(store, zoe.id)?.stillRecorded == true)
        #expect(store.notice?.message == RollCallText.keptTimedOut(zoe.name, at: point.name))
        RollCallServer.update { $0.scanDelay = 0 }
        #expect(await scans.flush() == 0)
        #expect(RollCallServer.count(zoe.id, point.id) == 1)
    }

    @Test func tickThenUntickBeforeItSendsRecordsNothing() async {
        RollCallServer.update { $0.scanDelay = 0.3 }
        let (store, _, _) = await begin(timeout: .seconds(2))
        let arjun = roster[1]
        // Arjun's send holds nothing up for Zoe, but Zoe's own tick and untick queue behind each other.
        store.set("e1", arjun, accounted: true)
        store.set("e1", zoe, accounted: true)
        store.set("e1", zoe, accounted: false)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(arjun.id) == .recorded)
        #expect(store.session("e1")?.scanRecord(zoe.id) == nil)
        #expect(RollCallServer.count(zoe.id, point.id) == 0, "never sent")
        #expect(deletes.isEmpty)
    }

    @Test func failedRecordingIsShownUntilRetried() async {
        RollCallServer.update { $0.scanStatus = 422 }
        let (store, _, _) = await begin()
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .notRecorded)
        #expect(entry(store, zoe.id)?.notRecorded == true)
        #expect(store.notice?.isError == true)
        #expect(store.notice?.message == RollCallText.notRecorded(zoe.name, at: point.name, reason: "Invalid scan context"))

        // Untick and tick again to retry.
        RollCallServer.update { $0.scanStatus = 200 }
        store.set("e1", zoe, accounted: false)
        #expect(store.session("e1")?.scanRecord(zoe.id) == nil)
        store.set("e1", zoe, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .recorded)
        #expect(entry(store, zoe.id)?.notRecorded == false)
        #expect(deletes.isEmpty)
    }

    @Test func offlineTickIgnoresAdmissionRulesLikeOnline() async {
        // No waiver, at a scan point that checks people in: online that's recorded, so offline it's queued too.
        var unsigned = zoe
        unsigned.waiverSigned = false
        RollCallServer.reset([unsigned] + roster.dropFirst())
        RollCallServer.update { $0.offline = true }
        let checkIn = RollCallScanPoint(id: "c1", name: "Check-in desk", checksIn: true)
        let synced = Time.iso(Date().addingTimeInterval(-60))
        await cache.write("roster_e1", Roster(eventId: "e1", participants: [unsigned] + roster.dropFirst(), syncedAt: synced, lastSyncAt: synced))
        let (store, scans, participants) = makeStore(cache)
        await participants.load("e1")
        store.start(RollCallLogic.start(eventId: "e1", participants: roster, expected: .checkedIn, scanPoint: checkIn, now: Fixtures.now))
        store.set("e1", unsigned, accounted: true)
        await store.waitForScans()
        #expect(store.session("e1")?.scanState(zoe.id) == .queued)
        #expect(scans.pending.first?.enforceAdmission == false)

        // When it syncs, Attend's record (still no waiver) doesn't get it taken back either.
        RollCallServer.update { $0.offline = false }
        #expect(await scans.flush() == 0)
        #expect(scans.rejections.isEmpty)
        #expect(deletes.isEmpty)
        #expect(RollCallServer.count(zoe.id, "c1") == 1)
    }

    @Test func endingWhileATickIsSendingKeepsItsScan() async {
        RollCallServer.update { $0.scanDelay = 0.3 }
        let (store, _, _) = await begin(timeout: .seconds(2))
        // No screen involved: the store owns the work, so it finishes whatever happens to the UI.
        store.set("e1", zoe, accounted: true)
        await waitUntilAttendHasAScan(zoe.id)
        store.end("e1")
        await store.waitForScans()
        #expect(RollCallServer.count(zoe.id, point.id) == 1)
        #expect(deletes.isEmpty)
        #expect(store.session("e1") == nil)
    }

    @Test func outcomesMapToWhatMayBeTakenBack() {
        let fresh = ScanResult(outcome: "scanned", scan: Scan(id: "s", scannedAt: "t"))
        var dedup = fresh
        dedup.deduplicated = true
        let again = ScanResult(outcome: "already_scanned", firstScanInContext: false, scan: Scan(id: "s", scannedAt: "t"))
        #expect(RollCallStore.state(for: .scanned(clientScanId: "a", result: fresh, participant: nil), hadEarlierScan: false) == (.recorded, "a", false))
        #expect(RollCallStore.state(for: .scanned(clientScanId: "a", result: fresh, participant: nil), hadEarlierScan: true) == (.preExisting, nil, false))
        #expect(RollCallStore.state(for: .scanned(clientScanId: "a", result: dedup, participant: nil), hadEarlierScan: false) == (.preExisting, nil, false))
        #expect(RollCallStore.state(for: .alreadyScanned(clientScanId: "a", result: again, participant: nil), hadEarlierScan: false) == (.preExisting, nil, false))
        #expect(RollCallStore.state(for: .rejected(clientScanId: "a", reason: .alreadyCheckedIn, participant: nil, offline: true), hadEarlierScan: false)
            == (.preExisting, nil, false))
        #expect(RollCallStore.state(for: .rejected(clientScanId: "a", reason: .notRegistered, participant: nil, offline: true), hadEarlierScan: false)
            == (.notRecorded, nil, false))
        #expect(RollCallStore.state(for: .failed(clientScanId: "a", message: "x", participant: nil, notFound: false), hadEarlierScan: false)
            == (.notRecorded, nil, false))
        let pending = PendingScan(clientScanId: "a", eventId: "e1", input: ScanInput(participantId: "p"), scannedAt: "t")
        #expect(RollCallStore.state(for: .queued(clientScanId: "a", pending: pending, participant: nil, reason: "Offline"), hadEarlierScan: false)
            == (.queued, "a", false))
        #expect(RollCallStore.state(for: .queued(clientScanId: "a", pending: pending, participant: nil, reason: ScanRepository.timeoutReason),
                                    hadEarlierScan: false) == (.queued, "a", true))
    }
}

@MainActor
@Suite struct RosterToolLinkTests {
    @Test func rollCallAndFirstAidLinksOpenOnHome() {
        let router = Router()
        router.tab = .people
        #expect(router.handle(URL(string: "attend://rollcall")!, available: AppTab.allCases, selectedEventId: "e1"))
        #expect(router.tab == .home)
        #expect(router.paths[.home] == [.rollCall(eventId: "e1")])

        #expect(router.handle(URL(string: "attend://firstaid")!, available: AppTab.allCases, selectedEventId: "e1"))
        #expect(router.paths[.home] == [.firstAid(eventId: "e1")])
    }

    @Test func linksNeedASelectedEvent() {
        let router = Router()
        #expect(!router.handle(URL(string: "attend://rollcall")!, available: AppTab.allCases, selectedEventId: nil))
        #expect(!router.handle(URL(string: "attend://firstaid")!, available: AppTab.allCases, selectedEventId: nil))
    }
}
