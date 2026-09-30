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

    var id: String { clientScanId }
    var displayName: String { knownName ?? (input.badgeToken != nil ? "NFC badge" : "Unknown attendee") }
}

enum ScanOutcome: Hashable {
    case scanned(clientScanId: String, result: ScanResult, participant: Participant?)
    case alreadyScanned(clientScanId: String, result: ScanResult, participant: Participant?)
    /// Couldn't reach the server; saved and will sync automatically.
    case queued(clientScanId: String, pending: PendingScan, participant: Participant?, reason: String)
    case failed(clientScanId: String, message: String, participant: Participant?, notFound: Bool)

    var clientScanId: String {
        switch self {
        case .scanned(let id, _, _), .alreadyScanned(let id, _, _), .queued(let id, _, _, _), .failed(let id, _, _, _): id
        }
    }

    var participant: Participant? {
        switch self {
        case .scanned(_, _, let p), .alreadyScanned(_, _, let p), .queued(_, _, let p, _), .failed(_, _, let p, _): p
        }
    }
}

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

    private static let queueKey = "scan_queue"

    init(api: AttendAPI, cache: JsonCache, participants: ParticipantRepository) {
        self.api = api
        self.cache = cache
        self.participants = participants
    }

    /// Reads the persisted queue once, merging anything queued before it loaded.
    func loadQueue() async {
        guard !queueLoaded else { return }
        let saved = await cache.read(Self.queueKey, as: [PendingScan].self) ?? []
        guard !queueLoaded else { return }
        queueLoaded = true
        pending = saved + pending.filter { c in !saved.contains { $0.clientScanId == c.clientScanId } }
    }

    func submit(eventId: String, input: ScanInput, scanContextId: String?, scanContextName: String?) async -> ScanOutcome {
        let clientScanId = UUID().uuidString.lowercased()
        let scannedAt = Time.nowISO()
        let known = await participants.load(eventId).flatMap { r in (input.badgeToken ?? input.participantId).flatMap(r.find) }
        let outcome: ScanOutcome
        do {
            let result = try await api.createScan(
                eventId: eventId,
                participantId: input.participantId,
                badgeToken: input.badgeToken,
                scanContextId: scanContextId,
                source: input.source == "manual" ? "manual" : nil,
                clientScanId: clientScanId,
                scannedAt: scannedAt
            )
            let p = result.participant.map { Roster.mergeKeepingDetail(known, $0) } ?? known
            if result.participant != nil, let p { await participants.upsert(eventId, p) }
            outcome = result.isAlreadyScanned
                ? .alreadyScanned(clientScanId: clientScanId, result: result, participant: p)
                : .scanned(clientScanId: clientScanId, result: result, participant: p)
        } catch {
            if error.isTransient {
                let item = PendingScan(clientScanId: clientScanId, eventId: eventId, scanContextId: scanContextId, scanContextName: scanContextName,
                                       input: input, scannedAt: scannedAt, attempts: 1, lastError: error.friendlyMessage, knownName: known?.name)
                await enqueue(item)
                outcome = .queued(clientScanId: clientScanId, pending: item, participant: known, reason: error.friendlyMessage)
            } else {
                let notFound = (error as? APIError)?.isNotFound == true
                outcome = .failed(clientScanId: clientScanId, message: notFound ? "Not registered for this event" : error.friendlyMessage,
                                  participant: known, notFound: notFound)
            }
        }
        log = Array(([ScanLogEntry(outcome: outcome, contextName: scanContextName, at: scannedAt)] + log).prefix(100))
        return outcome
    }

    private func enqueue(_ p: PendingScan) async {
        await loadQueue()
        await queueMutex.withLock {
            pending.append(p)
            await cache.write(Self.queueKey, pending)
            onQueued()
        }
    }

    /// Sends queued scans oldest first. `client_scan_id` makes retries idempotent. Returns the number
    /// still pending; stops early on a transient failure so we don't burn the rate limit.
    @discardableResult
    func flush() async -> Int {
        await loadQueue()
        return await queueMutex.withLock {
            var remaining = pending
            var i = 0
            while i < remaining.count {
                let p = remaining[i]
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
                    remaining.remove(at: i)
                } catch {
                    // Offline/5xx/429, cancelled, or an auth problem the user can fix by signing in again: keep it.
                    let api = error as? APIError
                    if error.isTransient || error.isCancellation || api?.isUnauthorized == true || api?.isForbidden == true { break }
                    // Permanent failure (e.g. not registered): drop it but keep a record in the log.
                    remaining.remove(at: i)
                    let failed = ScanOutcome.failed(clientScanId: p.clientScanId, message: "Offline scan rejected: \(error.friendlyMessage)",
                                                    participant: nil, notFound: false)
                    log.insert(ScanLogEntry(outcome: failed, contextName: p.scanContextName, at: Time.nowISO()), at: 0)
                }
            }
            // Anything queued while we were sending was appended to `pending`; keep it.
            let sentOrDropped = Set(pending.map(\.clientScanId)).subtracting(remaining.map(\.clientScanId))
            pending = pending.filter { !sentOrDropped.contains($0.clientScanId) }
            await cache.write(Self.queueKey, pending)
            return pending.count
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
        await cache.remove(Self.queueKey)
    }
}
