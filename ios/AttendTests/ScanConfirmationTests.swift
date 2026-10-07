import CryptoKit
import Foundation
import Testing
@testable import Attend

// Server-confirmed scans: the offline pre-check, the server's verdict, the 5 s timeout, flush
// rejections and the fail-closed encrypted cache (port of the Android ScanAdmission/ScanRepository/
// SecureBox tests).

/// Stores bytes as they are. Test-only on purpose: the app has no plaintext path, so tests that want a
/// working cache inject this explicitly.
struct IdentityCipher: CacheCipher {
    func seal(_ plain: Data) -> Data? { plain }
    func open(_ sealed: Data) -> Data? { sealed }
}

private func person(_ n: Int, status: String = "complete", waiver: Bool = true, scannedAt: [String] = []) -> Participant {
    var p = Fixtures.participants[0]
    p.participantId = "aaaaaaa\(n)-0000-4000-8000-000000000000"
    p.participantEventId = "bbbbbbb\(n)-0000-4000-8000-000000000000"
    p.displayName = "Person \(n)"
    p.fullName = nil
    p.status = status
    p.waiverSigned = waiver
    p.nfcBadgeToken = "badge-\(n)"
    p.scansByContext = scannedAt.map {
        ContextScanSummary(scanContextId: $0, checksIn: $0 == "desk", scanCount: 1, firstScannedAt: "2026-10-04T00:10:00Z")
    }
    return p
}

// MARK: - Offline pre-check

@Suite struct ScanAdmissionTests {
    let ok = person(1)
    let withdrawn = person(2, status: "withdrawn")
    let rejected = person(3, status: "rejected")
    let noWaiver = person(4, waiver: false)
    let inAlready = person(5, scannedAt: ["desk"])
    let roster: Roster
    let otherEvent = Roster(eventId: "e2", participants: [person(9)], syncedAt: "2026-10-03T00:00:00Z")

    init() {
        roster = Roster(eventId: "e1", participants: [ok, withdrawn, rejected, noWaiver, inAlready],
                        syncedAt: "2026-10-04T00:46:00Z", lastSyncAt: "2026-10-04T00:46:00Z")
    }

    private func qr(_ p: Participant) -> ScanInput { ScanInput(participantId: p.participantId) }

    private func check(_ input: ScanInput, roster r: Roster?? = .none, contextId: String? = "desk", checksIn: Bool = true,
                       pending: [PendingScan] = []) -> Precheck {
        ScanAdmission.precheck(input, roster: r ?? roster, contextId: contextId, checksIn: checksIn, pending: pending,
                               otherRosters: ["Campfire Melbourne": otherEvent])
    }

    @Test func passesWhenRegisteredAndClear() {
        #expect(check(qr(ok)) == .pass(ok))
        #expect(check(ScanInput(participantId: ok.participantEventId)) == .pass(ok))
        #expect(check(ScanInput(badgeToken: "badge-1", source: "nfc")) == .pass(ok))
    }

    @Test func blocksWithdrawnAndRejected() {
        #expect(check(qr(withdrawn)) == .block(.withdrawn, withdrawn))
        #expect(check(qr(rejected)) == .block(.registrationRejected, rejected))
    }

    @Test func blocksMissingConsentOnlyWhereItChecksIn() {
        #expect(check(qr(noWaiver)) == .block(.consentMissing, noWaiver))
        #expect(check(qr(noWaiver), contextId: "lunch", checksIn: false) == .pass(noWaiver))
    }

    @Test func blocksAlreadyCheckedInHere() {
        #expect(check(qr(inAlready)) == .block(.alreadyCheckedIn, inAlready, detail: "2026-10-04T00:10:00Z"))
        #expect(check(qr(inAlready), contextId: "lunch", checksIn: false) == .pass(inAlready))
    }

    @Test func blocksATicketAlreadyQueuedOffline() {
        let queued = PendingScan(clientScanId: "q1", eventId: "e1", scanContextId: "desk", scanContextName: "Check-in desk",
                                 input: ScanInput(badgeToken: "badge-1"), scannedAt: "2026-10-04T00:55:00Z")
        #expect(check(qr(ok), pending: [queued]) == .block(.alreadyCheckedIn, ok, detail: "2026-10-04T00:55:00Z"))
        var elsewhere = queued
        elsewhere.scanContextId = "lunch"
        #expect(check(qr(ok), pending: [elsewhere]) == .pass(ok))
    }

    @Test func blocksWrongEventAndNotRegistered() {
        let other = otherEvent.participants[0]
        #expect(check(qr(other)) == .block(.wrongEvent, other, detail: "Campfire Melbourne"))
        #expect(check(ScanInput(participantId: "ccccccc0-0000-4000-8000-000000000000")) == .block(.notRegistered, nil))
    }

    @Test func deliberateCheckInsSkipOnlyTheAdmissionRules() {
        // A check-in from someone's page or a roll call tick: as online, only what Attend itself would
        // refuse, or a duplicate, stops it offline.
        func deliberate(_ input: ScanInput) -> Precheck {
            ScanAdmission.precheck(input, roster: roster, contextId: "desk", checksIn: true,
                                   otherRosters: ["Campfire Melbourne": otherEvent], enforceAdmission: false)
        }
        #expect(deliberate(qr(withdrawn)) == .pass(withdrawn))
        #expect(deliberate(qr(rejected)) == .pass(rejected))
        #expect(deliberate(qr(noWaiver)) == .pass(noWaiver))
        #expect(deliberate(qr(inAlready)) == .block(.alreadyCheckedIn, inAlready, detail: "2026-10-04T00:10:00Z"))
        #expect(deliberate(ScanInput(participantId: "ccccccc0-0000-4000-8000-000000000000")) == .block(.notRegistered, nil))
        let other = otherEvent.participants[0]
        #expect(deliberate(qr(other)) == .block(.wrongEvent, other, detail: "Campfire Melbourne"))
    }

    @Test func cantProveAbsenceWithoutAFullRosterOrForBadges() {
        let stranger = ScanInput(participantId: "ccccccc0-0000-4000-8000-000000000000")
        #expect(check(stranger, roster: .some(nil)) == .pass(nil))
        var partial = roster
        partial.syncedAt = nil
        #expect(check(stranger, roster: partial) == .pass(nil))
        #expect(check(ScanInput(badgeToken: "new-badge", source: "nfc")) == .pass(nil))
    }

    @Test func serverRecordRules() {
        #expect(ScanAdmission.problem(ok, checksIn: true) == nil)
        #expect(ScanAdmission.problem(withdrawn, checksIn: false) == .withdrawn)
        #expect(ScanAdmission.problem(noWaiver, checksIn: true) == .consentMissing)
        #expect(ScanAdmission.problem(noWaiver, checksIn: false) == nil)
    }

    @Test func rosterAge() {
        let now = Time.parse("2026-10-04T01:00:00Z")!
        #expect(ScanAdmission.rosterAgeLabel(Time.iso(now.addingTimeInterval(-14 * 60)), now: now) == "Roster from 14 min ago")
        #expect(ScanAdmission.rosterAgeLabel(Time.iso(now), now: now) == "Roster synced just now")
        #expect(!ScanAdmission.isRosterStale(Time.iso(now.addingTimeInterval(-14 * 60)), now: now))
        #expect(ScanAdmission.isRosterStale(Time.iso(now.addingTimeInterval(-75 * 60)), now: now))
        #expect(ScanAdmission.isRosterStale(nil, now: now))
        #expect(ScanAdmission.rosterTime(roster) == "2026-10-04T00:46:00Z")
    }

    @Test func kioskNeverSaysWhy() {
        let card = ScanCard(key: "k", kind: .rejected, title: RejectReason.withdrawn.title)
        #expect(KioskLogic.message(for: card).title == "Please see a staff member")
        #expect(KioskLogic.message(for: ScanCard(key: "k", kind: .confirming, title: "Confirming…")).body == "Checking your ticket. Hold steady.")
        #expect(!ResultKind.confirming.isFinal)
        #expect(ResultKind.confirming.feedback == nil)
    }
}

// MARK: - Repository against a stub Attend

/// Canned Attend for the scan pipeline; records every request (with its JSON body).
final class ScanStubProtocol: URLProtocol, @unchecked Sendable {
    struct Reply: Sendable {
        var status = 200
        var body = "{}"
        var delay: TimeInterval = 0
    }

    nonisolated(unsafe) static var scan: @Sendable () -> Reply = { Reply() }
    nonisolated(unsafe) static var requests: [(line: String, body: String)] = []
    private static let lock = NSLock()

    nonisolated(unsafe) static var undo: @Sendable () -> Reply = { Reply(body: #"{"success":true,"deleted_scans":1}"#) }

    static func recorded() -> [(line: String, body: String)] { lock.withLock { requests } }

    /// Back to defaults between tests, under the lock that delayed deliveries also take.
    static func reset() {
        lock.withLock {
            requests = []
            scan = { Reply() }
            undo = { Reply(body: #"{"success":true,"deleted_scans":1}"#) }
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    private let cancelled = Locked(false)

    override func stopLoading() { cancelled.set(true) }

    override func startLoading() {
        let url = request.url!
        let path = url.path.replacingOccurrences(of: "/api/v1", with: "")
        let line = "\(request.httpMethod ?? "GET") \(path)\(url.query.map { "?\($0)" } ?? "")"
        Self.lock.withLock { Self.requests.append((line, Self.body(of: request))) }
        var reply = Reply(status: 404, body: #"{"error":"Not found"}"#)
        if request.httpMethod == "POST", path.hasSuffix("/scans") { reply = Self.lock.withLock { Self.scan }() }
        if request.httpMethod == "DELETE" { reply = Self.lock.withLock { Self.undo }() }
        nonisolated(unsafe) let proto = self
        let final = reply
        let deliver: @Sendable () -> Void = {
            guard !proto.cancelled.get() else { return }
            let res = HTTPURLResponse(url: url, statusCode: final.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            proto.client?.urlProtocol(proto, didReceive: res, cacheStoragePolicy: .notAllowed)
            proto.client?.urlProtocol(proto, didLoad: Data(final.body.utf8))
            proto.client?.urlProtocolDidFinishLoading(proto)
        }
        // A slow server, without blocking the loading thread (so cancelling the request still works).
        if final.delay > 0 { DispatchQueue.global().asyncAfter(deadline: .now() + final.delay, execute: deliver) } else { deliver() }
    }

    private static func body(of request: URLRequest) -> String {
        if let data = request.httpBody { return String(decoding: data, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return String(decoding: data, as: UTF8.self)
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ScanStubProtocol.self]
        return URLSession(configuration: config)
    }
}

/// A lock-protected value.
final class Locked<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T
    init(_ value: T) { self.value = value }
    func get() -> T { lock.withLock { value } }
    func set(_ new: T) { lock.withLock { value = new } }
}

@MainActor
@Suite(.serialized) struct ScanConfirmationTests {
    let mia: Participant
    let desk = ScanContextRef(id: "desk", name: "Check-in desk", checksIn: true)
    let directory = FileManager.default.temporaryDirectory.appending(path: "scan-tests-\(UUID().uuidString)")
    let cache: JsonCache
    var api = AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "https://attend.test")!, session: ScanStubProtocol.session())
    let participants: ParticipantRepository

    init() async {
        var p = person(1)
        p.displayName = "Mia"
        p.fullName = "Mia Chen"
        mia = p
        cache = JsonCache(directory: directory, cipher: IdentityCipher())
        participants = ParticipantRepository(api: api, cache: cache)
        ScanStubProtocol.reset()
    }

    private func scanned(_ p: Participant, outcome: String = "scanned", context: ScanContextRef? = nil) -> String {
        let result = ScanResult(outcome: outcome, firstScannedAt: "2026-10-04T00:00:00Z",
                                scan: Scan(id: "s1", scannedAt: "2026-10-04T00:00:00Z"), scanContext: context ?? desk, participant: p)
        return String(decoding: try! AttendJSON.encoder().encode(result), as: UTF8.self)
    }

    private func seedRoster(_ people: [Participant], age: TimeInterval = 14 * 60) async {
        let at = Time.iso(Date().addingTimeInterval(-age))
        await cache.write("roster_e1", Roster(eventId: "e1", participants: people, syncedAt: at, lastSyncAt: at))
    }

    private func repo(timeout: Duration = .milliseconds(400), api: AttendAPI? = nil) -> ScanRepository {
        ScanRepository(api: api ?? self.api, cache: cache, participants: participants, scanTimeout: timeout)
    }

    /// An API that can't reach Attend (connection refused).
    private var offlineAPI: AttendAPI { AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "http://127.0.0.1:1")!) }

    private var qrMia: ScanInput { ScanInput(participantId: mia.participantId) }

    // MARK: Online

    @Test func serverConfirmsIsFullSuccess() async {
        await seedRoster([mia])
        let body = scanned(mia)
        ScanStubProtocol.scan = { .init(body: body) }
        let outcome = await repo().submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Check-in desk")
        guard case .scanned = outcome else { Issue.record("expected scanned, got \(outcome)"); return }
        #expect(!outcome.isServerRejection)
        #expect(outcome.card(key: "k", context: nil, tz: nil, gateKey: nil, input: qrMia).kind == .scanned)
    }

    @Test func serverRecordSaysWithdrawnRejectsAndTakesTheScanBack() async {
        await seedRoster([mia])
        var gone = mia
        gone.status = "withdrawn"
        let body = scanned(gone)
        ScanStubProtocol.scan = { .init(body: body) }
        let outcome = await repo().submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Check-in desk")
        guard case let .rejected(_, reason, _, _, offline, reverted, _, _) = outcome else { Issue.record("expected rejected"); return }
        #expect(reason == .withdrawn)
        #expect(!offline)
        #expect(reverted)
        #expect(ScanStubProtocol.recorded().contains { $0.line == "DELETE /events/e1/scans/\(mia.participantEventId)?scan_context_id=desk" })
        #expect(outcome.isServerRejection)
        let alert = outcome.serverRejection(eventId: "e1", contextName: "Check-in desk", scannedAt: "t")
        #expect(alert?.headline == "Mia: registration withdrawn")
        #expect(alert?.participantEventId == mia.participantEventId)
        let card = outcome.card(key: "k", context: nil, tz: nil, gateKey: nil, input: qrMia)
        #expect(card.kind == .rejected)
        #expect(card.title == "Withdrawn")
    }

    @Test func missingConsentIsRejectedOnlyAtCheckIn() async {
        await seedRoster([mia])
        var unsigned = mia
        unsigned.waiverSigned = false
        let atDesk = scanned(unsigned)
        ScanStubProtocol.scan = { .init(body: atDesk) }
        let desk = await repo().submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk", checksIn: true)
        guard case .rejected(_, .consentMissing, _, _, _, _, _, _) = desk else { Issue.record("expected consent rejection"); return }

        let atLunch = scanned(unsigned, context: ScanContextRef(id: "lunch", name: "Lunch"))
        ScanStubProtocol.scan = { .init(body: atLunch) }
        let lunch = await repo().submit(eventId: "e1", input: qrMia, scanContextId: "lunch", scanContextName: "Lunch", checksIn: false)
        guard case .scanned = lunch else { Issue.record("lunch should go through"); return }
    }

    @Test func serverRefusalIsARejectionToInterruptFor() async {
        await seedRoster([mia])
        ScanStubProtocol.scan = { .init(status: 422, body: #"{"error":"Invalid scan context"}"#) }
        let scans = repo()
        let outcome = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
        guard case .failed(_, _, _, _, 422) = outcome else { Issue.record("expected 422 failure, got \(outcome)"); return }
        #expect(outcome.isServerRejection)
        #expect(outcome.serverRejection(eventId: "e1", contextName: nil, scannedAt: "t")?.headline == "Mia: Invalid scan context")
        #expect(scans.pending.isEmpty)
    }

    @Test func timeoutIsTreatedAsOfflineAndKeepsTheScanTime() async {
        await seedRoster([mia])
        let body = scanned(mia)
        ScanStubProtocol.scan = { .init(body: body, delay: 8) }
        let scans = repo(timeout: .milliseconds(300))
        let started = Date()
        let outcome = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Check-in desk")
        #expect(Date().timeIntervalSince(started) < 6, "gave up at the timeout")
        guard case let .queued(id, pending, _, reason, rosterAt) = outcome else { Issue.record("expected queued, got \(outcome)"); return }
        #expect(reason == "Attend took too long to respond.")
        #expect(rosterAt != nil)
        let card = outcome.card(key: "k", context: nil, tz: nil, gateKey: nil, input: qrMia)
        #expect(card.message == "Offline, will confirm later")
        #expect(card.rosterNote == "Roster from 14 min ago")
        #expect(!card.rosterStale)

        // Back online: the flush sends the original scan time.
        ScanStubProtocol.scan = { .init(body: body) }
        #expect(await scans.flush() == 0)
        let sent = ScanStubProtocol.recorded().last { $0.line == "POST /events/e1/scans" }!.body
        let json = try! JSONSerialization.jsonObject(with: Data(sent.utf8)) as! [String: Any]
        #expect(json["scanned_at"] as? String == pending.scannedAt)
        #expect(json["client_scan_id"] as? String == id)
    }

    @Test func timeoutButRosterSaysNoRejectsLocallyWithoutQueueing() async {
        var gone = mia
        gone.status = "withdrawn"
        await seedRoster([gone])
        let body = scanned(mia)
        ScanStubProtocol.scan = { .init(body: body, delay: 8) }
        let scans = repo(timeout: .milliseconds(300))
        let outcome = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
        guard case .rejected(_, .withdrawn, _, _, true, _, _, _) = outcome else { Issue.record("expected offline rejection, got \(outcome)"); return }
        #expect(!outcome.isServerRejection)
        #expect(scans.pending.isEmpty, "not a check-in waiting to sync")
        #expect(scans.hasQueuedWork, "but the timed-out request may have landed")

        // The next flush replays it (same client_scan_id, so Attend finds the original) and undoes it,
        // without a second alert.
        var still = mia
        still.status = "withdrawn"
        let recorded = scanned(still)
        ScanStubProtocol.scan = { .init(body: recorded) }
        #expect(await scans.flush() == 0)
        #expect(ScanStubProtocol.recorded().contains { $0.line == "DELETE /events/e1/scans/\(mia.participantEventId)?scan_context_id=desk" })
        #expect(scans.rejections.isEmpty)
        #expect(!scans.hasQueuedWork)
    }

    @Test func offlineRejectionWithoutATimeoutNeedsNoUndo() async {
        var gone = mia
        gone.status = "withdrawn"
        await seedRoster([gone])
        // A generous timeout: on a loaded CI simulator the refusal itself can take longer than 400 ms,
        // which would turn this into the timeout case the test isn't about.
        let scans = repo(timeout: .seconds(10), api: offlineAPI)
        _ = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
        #expect(!scans.hasQueuedWork, "connection refused: the request never left")
    }

    @Test func checkInFromTheirPageOverridesTheAdmissionRules() async {
        await seedRoster([mia])
        var unsigned = mia
        unsigned.waiverSigned = false
        let body = scanned(unsigned)
        ScanStubProtocol.scan = { .init(body: body) }
        let outcome = await repo().submit(eventId: "e1", input: ScanInput(participantId: mia.participantEventId, source: "manual"),
                                          scanContextId: "desk", scanContextName: "Desk", enforceAdmission: false)
        guard case .scanned = outcome else { Issue.record("expected scanned, got \(outcome)"); return }
        #expect(!ScanStubProtocol.recorded().contains { $0.line.hasPrefix("DELETE") })
    }

    @Test func offlineCheckInFromTheirPageIsQueuedAndNotRevertedOnSync() async {
        var unsigned = mia
        unsigned.waiverSigned = false
        await seedRoster([unsigned])
        let input = ScanInput(participantId: mia.participantEventId, source: "manual")
        guard case let .queued(_, pending, _, _, _) = await repo(api: offlineAPI).submit(
            eventId: "e1", input: input, scanContextId: "desk", scanContextName: "Desk", enforceAdmission: false) else {
            Issue.record("expected queued"); return
        }
        #expect(!pending.enforceAdmission)
        let body = scanned(unsigned)
        ScanStubProtocol.scan = { .init(body: body) }
        let scans = repo()
        #expect(await scans.flush() == 0)
        #expect(scans.rejections.isEmpty)
        #expect(!ScanStubProtocol.recorded().contains { $0.line.hasPrefix("DELETE") })
    }

    @Test func discardSaysWhetherTheScanWasStillQueued() async {
        await seedRoster([mia])
        let scans = repo(api: offlineAPI)
        guard case let .queued(id, _, _, _, _) = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk") else {
            Issue.record("expected queued"); return
        }
        let first = await scans.discardPending(id)
        let second = await scans.discardPending(id)
        #expect(first)
        #expect(!second, "already gone")
        #expect(scans.pending.isEmpty)
    }

    @Test func verdictUsesTheServersRecordNeverTheCache() async {
        var stale = mia
        stale.status = "withdrawn"
        await seedRoster([stale])
        let result = ScanResult(outcome: "scanned", scan: Scan(id: "s1", scannedAt: "2026-10-04T00:00:00Z"), scanContext: desk)
        let body = String(decoding: try! AttendJSON.encoder().encode(result), as: UTF8.self)
        ScanStubProtocol.scan = { .init(body: body) }
        let outcome = await repo().submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
        guard case .scanned = outcome else { Issue.record("expected scanned, got \(outcome)"); return }
        #expect(!ScanStubProtocol.recorded().contains { $0.line.hasPrefix("DELETE") })
    }

    @Test func failedUndoSaysTheScanIsStillRecorded() async {
        await seedRoster([mia])
        var gone = mia
        gone.status = "withdrawn"
        let body = scanned(gone)
        ScanStubProtocol.scan = { .init(body: body) }
        ScanStubProtocol.undo = { .init(status: 500, body: #"{"error":"boom"}"#) }
        let outcome = await repo().submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
        guard case .rejected(_, _, _, _, _, false, _, true) = outcome else { Issue.record("expected still recorded, got \(outcome)"); return }
        #expect(outcome.serverRejection(eventId: "e1", contextName: "Desk", scannedAt: "t")?.stillRecorded == true)
        #expect(outcome.card(key: "k", context: nil, tz: nil, gateKey: nil, input: qrMia).message?.contains("Still recorded on Attend") == true)
    }

    @Test func flushUsesTheCheckpointsOwnCheckInFlag() async {
        var unsigned = mia
        unsigned.waiverSigned = false
        await seedRoster([unsigned])
        guard case .queued = await repo(api: offlineAPI).submit(eventId: "e1", input: qrMia, scanContextId: "lunch", scanContextName: "Lunch", checksIn: false) else {
            Issue.record("expected queued"); return
        }
        let result = ScanResult(outcome: "scanned", scan: Scan(id: "s1", scannedAt: "2026-10-04T00:00:00Z"), participant: unsigned)
        let body = String(decoding: try! AttendJSON.encoder().encode(result), as: UTF8.self)
        ScanStubProtocol.scan = { .init(body: body) }
        let scans = repo()
        #expect(await scans.flush() == 0)
        #expect(scans.rejections.isEmpty)
        #expect(!ScanStubProtocol.recorded().contains { $0.line.hasPrefix("DELETE") })
    }

    // MARK: Offline

    @Test func offlinePreChecksRejectLocally() async {
        var withdrawn = person(7, status: "withdrawn")
        withdrawn.displayName = "Ollie"
        let unsigned = person(8, waiver: false)
        await seedRoster([mia, withdrawn, unsigned])
        let scans = repo(api: offlineAPI)
        var elsewhere = mia
        elsewhere.participantId = "ddddddd1-0000-4000-8000-000000000000"
        scans.otherRosters = { _ in ["Campfire Melbourne": Roster(eventId: "e2", participants: [elsewhere], syncedAt: "x")] }

        func reason(_ id: String) async -> RejectReason? {
            if case .rejected(_, let r, _, _, _, _, _, _) = await scans.submit(eventId: "e1", input: ScanInput(participantId: id), scanContextId: "desk", scanContextName: "Desk") { return r }
            return nil
        }
        #expect(await reason(withdrawn.participantId) == .withdrawn)
        #expect(await reason(unsigned.participantId) == .consentMissing)
        #expect(await reason(elsewhere.participantId) == .wrongEvent)
        #expect(await reason("eeeeeee1-0000-4000-8000-000000000000") == .notRegistered)
        #expect(scans.pending.isEmpty)

        guard case .queued = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk") else {
            Issue.record("a clear scan should queue"); return
        }
        let again = await scans.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
        guard case .rejected(_, .alreadyCheckedIn, _, _, _, _, _, _) = again else { Issue.record("expected already checked in"); return }
        #expect(again.card(key: "k", context: nil, tz: nil, gateKey: nil, input: qrMia).kind == .alreadyScanned)
        #expect(scans.pending.count == 1)
    }

    @Test func oldRosterMakesTheWarningProminent() async {
        await seedRoster([mia], age: 3 * 3600)
        let card = await repo(api: offlineAPI).submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk")
            .card(key: "k", context: nil, tz: nil, gateKey: nil, input: qrMia)
        #expect(card.kind == .savedOffline)
        #expect(card.rosterNote == "Roster from 3 h ago")
        #expect(card.rosterStale)
    }

    // MARK: Flush

    @Test func flushRejectionIsSurfacedNotJustLogged() async {
        await seedRoster([mia])
        let offline = repo(api: offlineAPI)
        guard case .queued = await offline.submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Check-in desk") else {
            Issue.record("expected queued"); return
        }

        // Same saved queue, now online: Attend says no.
        ScanStubProtocol.scan = { .init(status: 404, body: #"{"error":"Participant not found for this event"}"#) }
        let scans = repo()
        var notified: [ScanRejection] = []
        scans.onRejected = { notified += $0 }
        #expect(await scans.flush() == 0)
        let r = scans.rejections.first
        #expect(scans.rejections.count == 1)
        #expect(r?.headline == "Mia: not registered for this event")
        #expect(r?.participantEventId == mia.participantEventId)
        #expect(r?.contextName == "Check-in desk")
        #expect(notified == scans.rejections)

        // Persisted until dismissed.
        let restarted = repo()
        await restarted.loadQueue()
        #expect(restarted.rejections == scans.rejections)
        await restarted.dismissRejections()
        #expect(restarted.rejections.isEmpty)
        let again = repo()
        await again.loadQueue()
        #expect(again.rejections.isEmpty)
    }

    @Test func flushAcceptedButWithdrawnSinceIsRejectedAndUndone() async {
        await seedRoster([mia])
        guard case .queued = await repo(api: offlineAPI).submit(eventId: "e1", input: qrMia, scanContextId: "desk", scanContextName: "Desk") else {
            Issue.record("expected queued"); return
        }
        var gone = mia
        gone.status = "withdrawn"
        let body = scanned(gone)
        ScanStubProtocol.scan = { .init(body: body) }
        let scans = repo()
        #expect(await scans.flush() == 0)
        #expect(scans.rejections.first?.headline == "Mia: registration withdrawn")
        #expect(ScanStubProtocol.recorded().contains { $0.line.hasPrefix("DELETE /events/e1/scans/\(mia.participantEventId)") })
    }
}

// MARK: - Fail-closed cache

@Suite struct EncryptedCacheTests {
    let directory = FileManager.default.temporaryDirectory.appending(path: "cache-tests-\(UUID().uuidString)")
    let secret = "Mia Chen, anaphylaxis, room 204"

    private func files() -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
    }

    @Test func withoutAKeyNothingIsWrittenOrRead() async {
        // Written while a key existed…
        let plain = JsonCache(directory: directory, cipher: IdentityCipher())
        await plain.write("old", secret)
        #expect(files().count == 1)

        // …but with no key, nothing is read back and nothing new is written.
        let closed = JsonCache(directory: directory, cipher: nil)
        #expect(!closed.isEncrypted)
        #expect(await closed.read("old", as: String.self) == nil)
        await closed.write("new", secret)
        #expect(files().map(\.lastPathComponent) == ["old.bin"])
    }

    @Test func queuesSavedByOlderVersionsStillLoad() throws {
        // Saved before `checksIn` / `stillRecorded` existed: those keys are simply missing.
        let item = PendingScan(clientScanId: "q1", eventId: "e1", scanContextId: "desk", scanContextName: "Desk",
                               input: ScanInput(participantId: "x"), scannedAt: "2026-10-04T00:00:00Z", checksIn: false)
        var json = try #require(JSONSerialization.jsonObject(with: AttendJSON.encoder().encode([item])) as? [[String: Any]])
        json[0] = json[0].filter { !$0.key.lowercased().contains("checks") }
        let decoded = try AttendJSON.decoder().decode([PendingScan].self, from: JSONSerialization.data(withJSONObject: json))
        #expect(decoded.first?.clientScanId == "q1")
        #expect(decoded.first?.checksIn == true)

        let rejection = #"[{"client_scan_id":"r1","event_id":"e1","name":"Mia","reason":"x","scanned_at":"t"}]"#
        let rejections = try AttendJSON.decoder().decode([ScanRejection].self, from: Data(rejection.utf8))
        #expect(rejections.first?.stillRecorded == false)
    }

    @Test func withAKeyTheDiskHoldsCiphertextOnly() async throws {
        let cache = JsonCache(directory: directory, cipher: AESGCMCipher(key: SymmetricKey(size: .bits256)))
        #expect(cache.isEncrypted)
        await cache.write("roster_e1", secret)
        let raw = try Data(contentsOf: try #require(files().first))
        #expect(!String(decoding: raw, as: UTF8.self).contains("Mia Chen"))
        #expect(await cache.read("roster_e1", as: String.self) == secret)
    }

    @Test func aKeychainThatWontStoreTheKeyFailsClosed() {
        // A service no one else uses; if this Keychain works at all, the key is created and read back.
        let keychain = Keychain(service: "au.ingo.betterattend.tests.\(UUID().uuidString)")
        defer { keychain.set(nil as Data?, for: "cache_key") }
        if let key = JsonCache.keychainKey(keychain) {
            #expect(keychain.data("cache_key") == key.withUnsafeBytes { Data($0) }, "only used once it's really stored")
        } else {
            // Unsigned simulator builds have no Keychain: then there's no key, not a plaintext fallback.
            #expect(JsonCache.keychainKey(keychain) == nil)
        }
    }

    @MainActor @Test func scanningKeepsWorkingWithoutAKey() async {
        let cache = JsonCache(directory: directory, cipher: nil)
        let api = AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "http://127.0.0.1:1")!)
        let scans = ScanRepository(api: api, cache: cache, participants: ParticipantRepository(api: api, cache: cache))
        let outcome = await scans.submit(eventId: "e1", input: ScanInput(participantId: "aaaaaaa1-0000-4000-8000-000000000000"),
                                         scanContextId: "desk", scanContextName: "Desk")
        guard case .queued = outcome else { Issue.record("expected queued, got \(outcome)"); return }
        #expect(scans.pending.count == 1, "queued in memory")
        #expect(files().isEmpty, "but not on disk")
    }
}
