import Foundation

struct PendingScan: Codable, Hashable, Sendable, Identifiable {
    var clientScanId: String
    var eventId: String
    var scanContextId: String?
    var scanContextName: String?
    var input: ScanInput
    var scannedAt: String
    var attempts: Int = 0
    var lastError: String?
    /// Best-effort name for display while offline.
    var knownName: String?
    /// The checkpoint checks people in (a missing waiver only blocks there), for the verdict on sync.
    /// Defaulted when decoding, so queues saved by older versions still load.
    @Default<True> var checksIn: Bool = true

    var id: String { clientScanId }
    var displayName: String { knownName ?? (input.badgeToken != nil ? "NFC badge" : "Unknown attendee") }

    func rejection(_ who: Participant?, reason: String) -> ScanRejection {
        ScanRejection(clientScanId: clientScanId, eventId: eventId, participantEventId: who?.participantEventId,
                      name: who?.name ?? displayName, reason: reason, scannedAt: scannedAt, contextName: scanContextName)
    }
}

enum ScanOutcome: Hashable {
    /// Confirmed by the server.
    case scanned(clientScanId: String, result: ScanResult, participant: Participant?)
    case alreadyScanned(clientScanId: String, result: ScanResult, participant: Participant?)
    /// Couldn't reach the server (or it took too long); passed the offline pre-check, saved, and will
    /// sync automatically. `rosterAt` is when the roster it was checked against was synced.
    case queued(clientScanId: String, pending: PendingScan, participant: Participant?, reason: String, rosterAt: String? = nil)
    /// The server refused the scan (`status` is its HTTP status, 0 when it never got that far).
    case failed(clientScanId: String, message: String, participant: Participant?, notFound: Bool, status: Int = 0)
    /// Not admitted. `offline`: the cached roster said no and nothing was queued. Otherwise the
    /// server's own record of the person said no; `reverted` when the scan Attend recorded anyway was taken back.
    /// `stillRecorded`: undoing the scan Attend recorded failed, so it still counts them as scanned.
    case rejected(clientScanId: String, reason: RejectReason, participant: Participant?, detail: String? = nil,
                  offline: Bool = false, reverted: Bool = false, rosterAt: String? = nil, stillRecorded: Bool = false)

    var clientScanId: String {
        switch self {
        case .scanned(let id, _, _), .alreadyScanned(let id, _, _), .queued(let id, _, _, _, _), .failed(let id, _, _, _, _),
             .rejected(let id, _, _, _, _, _, _, _): id
        }
    }

    var participant: Participant? {
        switch self {
        case .scanned(_, _, let p), .alreadyScanned(_, _, let p), .queued(_, _, let p, _, _), .failed(_, _, let p, _, _),
             .rejected(_, _, let p, _, _, _, _, _): p
        }
    }

    /// The server turned this scan down after the app had shown it as going through.
    var isServerRejection: Bool {
        switch self {
        case .failed(_, _, _, _, let status): status != 0 && status != 401
        case .rejected(_, _, _, _, let offline, _, _, _): !offline
        default: false
        }
    }

    /// The banner for a scan the server turned down, or nil if this isn't one.
    func serverRejection(eventId: String, contextName: String?, scannedAt: String) -> ScanRejection? {
        guard isServerRejection else { return nil }
        let reason: String
        var stillRecorded = false
        switch self {
        case .rejected(_, let r, _, _, _, _, _, let recorded):
            reason = r.short
            stillRecorded = recorded
        case .failed(_, let message, _, let notFound, _): reason = notFound ? RejectReason.notRegistered.short : message
        default: return nil
        }
        return ScanRejection(clientScanId: clientScanId, eventId: eventId, participantEventId: participant?.participantEventId,
                             name: participant?.name ?? "Unknown attendee", reason: reason, scannedAt: scannedAt, contextName: contextName,
                             stillRecorded: stillRecorded)
    }
}

/// A scan that didn't let someone in, kept until staff dismiss it: the scanner's interrupt banner, and
/// offline check-ins the server turned down later (persisted, shown on Home).
struct ScanRejection: Codable, Hashable, Sendable, Identifiable {
    var clientScanId: String
    var eventId: String
    var participantEventId: String?
    var name: String
    /// "consent not signed", "not registered for this event", …
    var reason: String
    var scannedAt: String
    var contextName: String?
    /// Undoing the scan Attend recorded failed: it still counts them as scanned.
    @Default<False> var stillRecorded: Bool = false

    var id: String { clientScanId }
    /// "Mia Chen: consent not signed"
    var headline: String { "\(name): \(reason)" }
}

/// A live scan took longer than `ScanRepository.scanTimeout`.
struct ScanTimeout: Error {}

/// Log line for the scanner's "recent scans" sheet (kept in memory for this app session).
struct ScanLogEntry: Hashable, Identifiable {
    var outcome: ScanOutcome
    var contextName: String?
    var at: String
    var id: String { outcome.clientScanId }
}

@MainActor
@Observable
final class ScanRepository {
    private(set) var pending: [PendingScan] = []
    private(set) var log: [ScanLogEntry] = []
    /// Offline check-ins the server turned down when they were sent, until dismissed.
    private(set) var rejections: [ScanRejection] = []
    /// Scans that timed out and were then turned away by the offline pre-check. Attend may still have
    /// recorded them, so each is replayed (same client_scan_id, deduplicated by the server) and undone.
    /// Kept apart from `pending`: they aren't check-ins waiting to sync.
    private(set) var undos: [PendingScan] = []

    /// Anything left to send: queued check-ins or scans to take back.
    var hasQueuedWork: Bool { !pending.isEmpty || !undos.isEmpty }

    @ObservationIgnored private let api: AttendAPI
    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored private let participants: ParticipantRepository
    @ObservationIgnored private let queueMutex = AsyncMutex()
    @ObservationIgnored private var queueLoaded = false

    /// Resolves a scan context for scans queued before contexts could load (the server rejects a
    /// context-less scan when an event has several).
    @ObservationIgnored var fallbackContext: (String) async -> String? = { _ in nil }
    /// Called when something is queued, so the app can arrange a retry.
    @ObservationIgnored var onQueued: () -> Void = {}
    /// Called when queued scans are turned down on sync, so the app can raise a notification.
    @ObservationIgnored var onRejected: ([ScanRejection]) -> Void = { _ in }
    /// Cached rosters of the user's other events, keyed by event name, for the offline "wrong event" check.
    @ObservationIgnored var otherRosters: (String) async -> [String: Roster] = { _ in [:] }

    /// How long a live scan may take before it's treated as offline and queued.
    @ObservationIgnored let scanTimeout: Duration

    static let defaultScanTimeout: Duration = .seconds(5)
    private static let queueKey = "scan_queue"
    private static let rejectionsKey = "scan_rejections"
    private static let undosKey = "scan_undos"
    /// Pre-check reasons where Attend would have recorded the scan had it arrived (it 404s the others).
    private static let recordedAnyway: Set<RejectReason> = [.withdrawn, .registrationRejected, .consentMissing]

    init(api: AttendAPI, cache: JsonCache, participants: ParticipantRepository, scanTimeout: Duration = ScanRepository.defaultScanTimeout) {
        self.api = api
        self.cache = cache
        self.participants = participants
        self.scanTimeout = scanTimeout
    }

    /// Reads the persisted queue once, merging anything queued before it loaded.
    func loadQueue() async {
        guard !queueLoaded else { return }
        let saved = await cache.read(Self.queueKey, as: [PendingScan].self) ?? []
        let rejected = await cache.read(Self.rejectionsKey, as: [ScanRejection].self) ?? []
        let savedUndos = await cache.read(Self.undosKey, as: [PendingScan].self) ?? []
        guard !queueLoaded else { return }
        queueLoaded = true
        pending = saved + pending.filter { c in !saved.contains { $0.clientScanId == c.clientScanId } }
        rejections = rejected + rejections.filter { c in !rejected.contains { $0.clientScanId == c.clientScanId } }
        undos = savedUndos + undos.filter { c in !savedUndos.contains { $0.clientScanId == c.clientScanId } }
    }

    /// Sends a scan, giving up after `scanTimeout`. The server's answer is checked against
    /// `ScanAdmission`; if it can't be reached in time, the scan is pre-checked against the cached
    /// roster and queued (keeping its original time) only if nothing there says no.
    /// - Parameters:
    ///   - checksIn: the checkpoint checks people in (a missing waiver only blocks there).
    ///   - enforceAdmission: false for a deliberate check-in from someone's page: staff have looked at
    ///     them (e.g. a paper waiver signed at the desk), so the server's record isn't second-guessed.
    func submit(eventId: String, input: ScanInput, scanContextId: String?, scanContextName: String?, checksIn: Bool = true,
                enforceAdmission: Bool = true) async -> ScanOutcome {
        let clientScanId = UUID().uuidString.lowercased()
        let scannedAt = Time.nowISO()
        let roster = await participants.load(eventId)
        let known = (input.badgeToken ?? input.participantId).flatMap { roster?.find($0) }
        let outcome: ScanOutcome
        do {
            let result = try await createScanWithTimeout(eventId: eventId, input: input, scanContextId: scanContextId,
                                                         clientScanId: clientScanId, scannedAt: scannedAt)
            let p = result.participant.map { Roster.mergeKeepingDetail(known, $0) } ?? known
            if result.participant != nil, let p { await participants.upsert(eventId, p) }
            outcome = await verdict(eventId: eventId, clientScanId: clientScanId, result: result, participant: p,
                                    scanContextId: scanContextId, checksIn: checksIn, enforceAdmission: enforceAdmission)
        } catch is ScanTimeout {
            outcome = await offline(eventId: eventId, clientScanId: clientScanId, input: input, scanContextId: scanContextId,
                                    scanContextName: scanContextName, checksIn: checksIn, scannedAt: scannedAt, roster: roster,
                                    known: known, reason: "Attend took too long to respond.", timedOut: true)
        } catch {
            if error.isTransient {
                outcome = await offline(eventId: eventId, clientScanId: clientScanId, input: input, scanContextId: scanContextId,
                                        scanContextName: scanContextName, checksIn: checksIn, scannedAt: scannedAt, roster: roster,
                                        known: known, reason: error.friendlyMessage)
            } else {
                let api = error as? APIError
                let notFound = api?.isNotFound == true
                outcome = .failed(clientScanId: clientScanId, message: notFound ? "Not registered for this event" : error.friendlyMessage,
                                  participant: known, notFound: notFound, status: api?.status ?? 0)
            }
        }
        log = Array(([ScanLogEntry(outcome: outcome, contextName: scanContextName, at: scannedAt)] + log).prefix(100))
        return outcome
    }

    /// The live request, cancelled (and reported as `ScanTimeout`) if it takes longer than `scanTimeout`.
    private func createScanWithTimeout(eventId: String, input: ScanInput, scanContextId: String?,
                                       clientScanId: String, scannedAt: String) async throws -> ScanResult {
        let api = self.api
        let timeout = scanTimeout
        let request = Task { @MainActor in
            try await api.createScan(
                eventId: eventId,
                participantId: input.participantId,
                badgeToken: input.badgeToken,
                scanContextId: scanContextId,
                source: input.source == "manual" ? "manual" : nil,
                clientScanId: clientScanId,
                scannedAt: scannedAt
            )
        }
        let timer = Task {
            try await Task.sleep(for: timeout)
            request.cancel()
        }
        defer { timer.cancel() }
        do {
            return try await withTaskCancellationHandler { try await request.value } onCancel: { request.cancel() }
        } catch {
            // Cancelled by the timer rather than by our caller: that's a timeout.
            if request.isCancelled, !Task.isCancelled { throw ScanTimeout() }
            throw error
        }
    }

    /// Turns a scan the server accepted into an outcome. Attend records scans for withdrawn people and
    /// missing waivers too, so its fresh record of the person (never our cached copy) gets the final say;
    /// a brand-new scan of someone who isn't admitted is taken back so they don't count as here.
    private func verdict(eventId: String, clientScanId: String, result: ScanResult, participant p: Participant?,
                         scanContextId: String?, checksIn: Bool, enforceAdmission: Bool) async -> ScanOutcome {
        if enforceAdmission, let fresh = result.participant,
           let problem = ScanAdmission.problem(fresh, checksIn: result.scanContext?.checksIn ?? checksIn) {
            let undo = await revert(eventId: eventId, result: result, participantEventId: fresh.participantEventId, scanContextId: scanContextId)
            return .rejected(clientScanId: clientScanId, reason: problem, participant: p, reverted: undo == .removed, stillRecorded: undo == .failed)
        }
        return result.isAlreadyScanned
            ? .alreadyScanned(clientScanId: clientScanId, result: result, participant: p)
            : .scanned(clientScanId: clientScanId, result: result, participant: p)
    }

    private enum Revert { case removed, notNeeded, failed }

    /// Undoes the scan Attend just recorded, if it was the person's first at that checkpoint.
    private func revert(eventId: String, result: ScanResult, participantEventId: String, scanContextId: String?) async -> Revert {
        guard !result.isAlreadyScanned else { return .notNeeded }
        guard let contextId = result.scanContext?.id ?? scanContextId else { return .failed }
        do {
            _ = try await api.undoScans(eventId: eventId, participantEventId: participantEventId, scanContextId: contextId)
            await participants.applyUndo(eventId, participantEventId: participantEventId, scanContextId: contextId, contexts: [])
            return .removed
        } catch {
            return .failed
        }
    }

    /// The offline path: pre-check against the cached roster, then queue only what passes.
    private func offline(eventId: String, clientScanId: String, input: ScanInput, scanContextId: String?, scanContextName: String?,
                         checksIn: Bool, scannedAt: String, roster: Roster?, known: Participant?, reason: String,
                         timedOut: Bool = false) async -> ScanOutcome {
        await loadQueue()
        let code = input.badgeToken ?? input.participantId
        var others: [String: Roster] = [:]
        if let roster, roster.syncedAt != nil, input.badgeToken == nil, let code, roster.find(code) == nil {
            others = await otherRosters(eventId)
        }
        let rosterAt = ScanAdmission.rosterTime(roster)
        switch ScanAdmission.precheck(input, roster: roster, contextId: scanContextId, checksIn: checksIn, pending: pending, otherRosters: others) {
        case let .block(blocked, p, detail):
            // The request that timed out may still have been recorded: make sure it doesn't stand.
            if timedOut, Self.recordedAnyway.contains(blocked) {
                await enqueueUndo(PendingScan(clientScanId: clientScanId, eventId: eventId, scanContextId: scanContextId,
                                              scanContextName: scanContextName, input: input, scannedAt: scannedAt, attempts: 1,
                                              lastError: reason, knownName: known?.name, checksIn: checksIn))
            }
            return .rejected(clientScanId: clientScanId, reason: blocked, participant: p, detail: detail, offline: true, rosterAt: rosterAt)
        case .pass:
            let item = PendingScan(clientScanId: clientScanId, eventId: eventId, scanContextId: scanContextId, scanContextName: scanContextName,
                                   input: input, scannedAt: scannedAt, attempts: 1, lastError: reason, knownName: known?.name, checksIn: checksIn)
            await enqueue(item)
            return .queued(clientScanId: clientScanId, pending: item, participant: known, reason: reason, rosterAt: rosterAt)
        }
    }

    private func enqueue(_ p: PendingScan) async {
        await loadQueue()
        await queueMutex.withLock {
            pending.append(p)
            await cache.write(Self.queueKey, pending)
            onQueued()
        }
    }

    private func enqueueUndo(_ p: PendingScan) async {
        await loadQueue()
        await queueMutex.withLock {
            undos.append(p)
            await cache.write(Self.undosKey, undos)
            onQueued()
        }
    }

    /// Replays each timed-out-then-turned-away scan (deduplicated by client_scan_id, so this finds the
    /// original if it landed) and undoes it. Silent: staff already saw the rejection. Returns how many
    /// are left to retry. Call with the queue lock held.
    private func flushUndos() async -> Int {
        var remaining = undos
        while let p = remaining.first {
            do {
                var contextId = p.scanContextId
                if contextId == nil { contextId = await fallbackContext(p.eventId) }
                let result = try await api.createScan(
                    eventId: p.eventId,
                    participantId: p.input.participantId,
                    badgeToken: p.input.badgeToken,
                    scanContextId: contextId,
                    source: p.input.source == "manual" ? "manual" : nil,
                    clientScanId: p.clientScanId,
                    scannedAt: p.scannedAt
                )
                if let peid = result.participant?.participantEventId ?? result.scan?.participantEventId,
                   await revert(eventId: p.eventId, result: result, participantEventId: peid, scanContextId: contextId) == .failed {
                    break
                }
                remaining.removeFirst()
            } catch {
                let api = error as? APIError
                if error.isTransient || error.isCancellation || api?.isUnauthorized == true || api?.isForbidden == true { break }
                remaining.removeFirst() // e.g. not found: nothing was recorded
            }
        }
        let done = Set(undos.map(\.clientScanId)).subtracting(remaining.map(\.clientScanId))
        undos = undos.filter { !done.contains($0.clientScanId) }
        await cache.write(Self.undosKey, undos)
        return undos.count
    }

    /// Sends queued scans oldest first, each with the time it was really scanned (`scanned_at`).
    /// `client_scan_id` makes retries idempotent. Returns the number still pending; stops early on a
    /// transient failure so we don't burn the rate limit. Scans the server turns down are kept in
    /// `rejections` and reported through `onRejected`, not just logged.
    @discardableResult
    func flush() async -> Int {
        await loadQueue()
        return await queueMutex.withLock {
            let undosLeft = await flushUndos()
            var remaining = pending
            var rejected: [ScanRejection] = []
            while let p = remaining.first {
                let known = (p.input.badgeToken ?? p.input.participantId).flatMap { participants.roster(p.eventId)?.find($0) }
                do {
                    var contextId = p.scanContextId
                    if contextId == nil { contextId = await fallbackContext(p.eventId) }
                    let result = try await api.createScan(
                        eventId: p.eventId,
                        participantId: p.input.participantId,
                        badgeToken: p.input.badgeToken,
                        scanContextId: contextId,
                        source: p.input.source == "manual" ? "manual" : nil,
                        clientScanId: p.clientScanId,
                        scannedAt: p.scannedAt
                    )
                    if let participant = result.participant { await participants.upsert(p.eventId, participant) }
                    // Accepted, but the server's record says they shouldn't have been let in (e.g. withdrawn since the roster synced).
                    if let who = result.participant, let problem = ScanAdmission.problem(who, checksIn: result.scanContext?.checksIn ?? p.checksIn) {
                        let undo = await revert(eventId: p.eventId, result: result, participantEventId: who.participantEventId, scanContextId: contextId)
                        var rejection = p.rejection(who, reason: problem.short)
                        rejection.stillRecorded = undo == .failed
                        rejected.append(rejection)
                    }
                    remaining.removeFirst()
                } catch {
                    // Offline/5xx/429, cancelled, or an auth problem the user can fix by signing in again: keep it.
                    let api = error as? APIError
                    if error.isTransient || error.isCancellation || api?.isUnauthorized == true || api?.isForbidden == true { break }
                    // Permanent failure (e.g. not registered): drop it, and tell staff who it was and why.
                    remaining.removeFirst()
                    let reason = api?.isNotFound == true ? RejectReason.notRegistered.short : error.friendlyMessage
                    rejected.append(p.rejection(known, reason: reason))
                    let failed = ScanOutcome.failed(clientScanId: p.clientScanId, message: "Offline scan rejected: \(reason)",
                                                    participant: known, notFound: false)
                    log.insert(ScanLogEntry(outcome: failed, contextName: p.scanContextName, at: Time.nowISO()), at: 0)
                }
            }
            // Anything queued while we were sending was appended to `pending`; keep it.
            let sentOrDropped = Set(pending.map(\.clientScanId)).subtracting(remaining.map(\.clientScanId))
            pending = pending.filter { !sentOrDropped.contains($0.clientScanId) }
            await cache.write(Self.queueKey, pending)
            if !rejected.isEmpty {
                let ids = Set(rejected.map(\.clientScanId))
                rejections = rejected.reversed() + rejections.filter { !ids.contains($0.clientScanId) }
                await cache.write(Self.rejectionsKey, rejections)
                onRejected(rejected)
            }
            return pending.count + undosLeft
        }
    }

    /// Clears rejected offline check-ins from Home: the given ones, or all of them.
    func dismissRejections(_ clientScanIds: Set<String>? = nil) async {
        await loadQueue()
        await queueMutex.withLock {
            if let clientScanIds { rejections.removeAll { clientScanIds.contains($0.clientScanId) } } else { rejections = [] }
            await cache.write(Self.rejectionsKey, rejections)
        }
    }

    func discardPending(_ clientScanId: String) async {
        await queueMutex.withLock {
            pending.removeAll { $0.clientScanId == clientScanId }
            await cache.write(Self.queueKey, pending)
        }
    }

    func undo(eventId: String, participantEventId: String, scanContextId: String?) async throws -> UndoResult {
        try await api.undoScans(eventId: eventId, participantEventId: participantEventId, scanContextId: scanContextId)
    }

    func clear() async {
        pending = []
        log = []
        rejections = []
        undos = []
        await cache.remove(Self.queueKey)
        await cache.remove(Self.rejectionsKey)
        await cache.remove(Self.undosKey)
    }
}
