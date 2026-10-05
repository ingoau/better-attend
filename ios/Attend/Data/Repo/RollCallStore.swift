import Foundation

/// The roll call in progress for each event (at most one per event), kept in memory and in the
/// encrypted cache so it survives restarts. When the cache fails closed (no encryption key) writes are
/// dropped, so a roll call then lasts until the app closes, like everything else.
///
/// When a roll call records ticks as scans, the scans (and their undos) run here, one queue per
/// person, for the life of the app: leaving the screen never drops a tick's scan or an untick's undo.
@MainActor
@Observable
final class RollCallStore {
    private(set) var sessions: [String: RollCallSession] = [:]
    /// The latest thing the background work wants to tell staff (shown as a toast by the screen).
    private(set) var notice: RollCallNotice?

    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored private let scans: ScanRepository
    @ObservationIgnored private let participants: ParticipantRepository
    @ObservationIgnored private var loaded: Set<String> = []
    /// Writes run one after another, so an older snapshot never lands after a newer one.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?
    /// Bumped on sign-out: writes queued before it are dropped, so nothing is saved after the wipe.
    @ObservationIgnored private var generation = 0
    /// One queue of scan / undo work per person ("eventId|participantEventId"), so a quick
    /// tick-untick-tick lands in order and an untick always sees what its tick did.
    @ObservationIgnored private var jobs: [String: (id: UUID, task: Task<Void, Never>)] = [:]
    /// Ticks made since launch whose scan hasn't started sending yet. A `.sending` record that isn't
    /// here was left by a send the app didn't live to see answered, so it's uncertain.
    @ObservationIgnored private var unsent: Set<String> = []

    private static func key(_ eventId: String, _ id: String) -> String { "\(eventId)|\(id)" }

    init(cache: JsonCache, scans: ScanRepository, participants: ParticipantRepository) {
        self.cache = cache
        self.scans = scans
        self.participants = participants
    }

    func session(_ eventId: String) -> RollCallSession? { sessions[eventId] }

    /// The event's roll call from memory, else from the disk cache.
    @discardableResult
    func load(_ eventId: String) async -> RollCallSession? {
        if loaded.contains(eventId) { return sessions[eventId] }
        let gen = generation
        let saved = await cache.read(RollCallLogic.cacheKey(eventId), as: RollCallSession.self)
        if !loaded.contains(eventId), gen == generation {
            loaded.insert(eventId)
            if sessions[eventId] == nil, let saved { sessions[eventId] = saved }
        }
        return sessions[eventId]
    }

    /// Starts (or replaces) the event's roll call.
    func start(_ session: RollCallSession) {
        loaded.insert(session.eventId)
        sessions[session.eventId] = session
        persist(session.eventId)
    }

    /// Changes the event's roll call in place and saves it. No-op when there isn't one.
    func update(_ eventId: String, _ change: (inout RollCallSession) -> Void) {
        guard var s = sessions[eventId] else { return }
        change(&s)
        guard s != sessions[eventId] else { return }
        sessions[eventId] = s
        persist(eventId)
    }

    /// Ends the roll call and forgets it. Scans already recorded stay recorded.
    func end(_ eventId: String) {
        loaded.insert(eventId)
        sessions[eventId] = nil
        persist(eventId)
    }

    /// Forgets everything (sign-out): stops the background work and waits for disk writes already
    /// under way, dropping any still queued, so no roll call file is written after the cache is wiped.
    /// Call before wiping the cache. The files go with the rest of the cache.
    func clear() async {
        generation += 1
        for job in jobs.values { job.task.cancel() }
        jobs = [:]
        unsent = []
        sessions = [:]
        loaded = []
        notice = nil
        await lastWrite?.value
        lastWrite = nil
    }

    /// Waits for pending disk writes (tests).
    func waitForWrites() async {
        await lastWrite?.value
    }

    private func persist(_ eventId: String) {
        let key = RollCallLogic.cacheKey(eventId)
        let snapshot = sessions[eventId]
        let cache = self.cache
        let previous = lastWrite
        let gen = generation
        lastWrite = Task {
            await previous?.value
            // Signed out since: the cache is being wiped, so don't put the roll call back.
            guard gen == self.generation else { return }
            if let snapshot {
                await cache.write(key, snapshot)
            } else {
                await cache.remove(key)
            }
        }
    }

    // MARK: Ticks

    /// Ticks or unticks someone straight away (saved on this phone). When the roll call records
    /// scans, the scan (or taking it back) follows in the background. Returns true if anything changed.
    @discardableResult
    func set(_ eventId: String, _ p: Participant, accounted: Bool, at: String = Time.nowISO()) -> Bool {
        let id = p.participantEventId
        guard let session = sessions[eventId] else { return false }
        // Judged now, from the roster as it is when they're ticked.
        let hadEarlierScan = session.scanPoint.map { RollCallLogic.hasScan(participants.roster(eventId)?.byEventId[id], at: $0.id) } ?? false
        var changed = false
        update(eventId) { s in
            if accounted && !s.isOnList(id) {
                s.add(id, at: at)
                changed = true
            } else {
                changed = s.set(id, accounted: accounted, at: at)
            }
            guard changed, s.scanPoint != nil else { return }
            if accounted {
                s.noteTicked(id, hadEarlierScan: hadEarlierScan)
            } else {
                s.noteUnticked(id)
            }
        }
        guard changed, sessions[eventId]?.scanPoint != nil else { return changed }
        let before = session.scanState(id)
        if accounted, before == nil || before == .notRecorded {
            unsent.insert(Self.key(eventId, id))
        }
        enqueue(eventId, id) { [self] in
            await reconcile(eventId, p)
        }
        return changed
    }

    /// Clears the notice once it's been shown.
    func clearNotice(_ id: RollCallNotice.ID? = nil) {
        if id == nil || notice?.id == id { notice = nil }
    }

    /// Waits until every person's scan work has finished (tests).
    func waitForScans() async {
        // Each person's last job removes itself when it's done.
        while let job = jobs.values.first { await job.task.value }
    }

    private func enqueue(_ eventId: String, _ id: String, _ work: @escaping @MainActor () async -> Void) {
        let key = Self.key(eventId, id)
        let previous = jobs[key]?.task
        let gen = generation
        let token = UUID()
        let task = Task { [self] in
            await previous?.value
            if gen == generation, !Task.isCancelled { await work() }
            if jobs[key]?.id == token { jobs[key] = nil }
        }
        jobs[key] = (token, task)
    }

    private func post(_ eventId: String, _ message: String, error: Bool = false) {
        notice = RollCallNotice(eventId: eventId, message: message, isError: error)
    }

    /// Brings someone's scan in line with their tick, deciding from the state *now* (not when they
    /// were tapped): records a scan for a tick that hasn't got one, and takes back the roll call's
    /// own scan for someone who's been unticked. Never removes a scan the roll call didn't make.
    private func reconcile(_ eventId: String, _ p: Participant) async {
        let id = p.participantEventId
        guard let session = sessions[eventId], let point = session.scanPoint else { return }
        let current = session.scanRecord(id)
        if session.isAccounted(id) {
            switch current?.state {
            case nil, .sending:
                await record(eventId, p, at: point, hadEarlierScan: current?.hadEarlierScan
                    ?? RollCallLogic.hasScan(participants.roster(eventId)?.byEventId[id], at: point.id))
            case .kept:
                update(eventId) { $0.noteScan(id, .preExisting) }
            case .queued, .recorded, .preExisting, .notRecorded:
                // Already done, or it failed (untick and tick again to retry).
                break
            }
        } else {
            switch current?.state {
            case nil, .preExisting, .kept:
                break
            case .notRecorded:
                update(eventId) { $0.noteScan(id, nil) }
            case .sending:
                if unsent.remove(Self.key(eventId, id)) != nil {
                    // Unticked before its scan was sent: there's nothing to take back.
                    update(eventId) { $0.noteScan(id, nil) }
                } else {
                    // Left over from a send the app didn't live to see answered: nobody knows whether it landed.
                    await takeBackUncertain(eventId, p, at: point)
                }
            case .queued:
                await takeBackQueued(eventId, p, at: point, clientScanId: current?.clientScanId, timedOut: current?.timedOut ?? false)
            case .recorded:
                await takeBackRecorded(eventId, p, at: point)
            }
        }
    }

    /// Records a tick as a scan at the roll call's scan point (queued offline like any scan), and
    /// always notes how it went, so a later untick knows what's there.
    private func record(_ eventId: String, _ p: Participant, at point: RollCallScanPoint, hadEarlierScan: Bool) async {
        let id = p.participantEventId
        unsent.remove(Self.key(eventId, id))
        let outcome = await scans.submit(
            eventId: eventId,
            input: ScanInput(participantId: id, source: "manual"),
            scanContextId: point.id,
            scanContextName: point.name,
            checksIn: point.checksIn,
            // Staff can see them standing there: don't second-guess it like a ticket scan.
            enforceAdmission: false
        )
        let (state, clientScanId, timedOut) = Self.state(for: outcome, hadEarlierScan: hadEarlierScan)
        update(eventId) { $0.noteScan(id, state, clientScanId: clientScanId, timedOut: timedOut) }
        if state == .notRecorded {
            let reason: String
            switch outcome {
            case let .rejected(_, r, _, _, offline, _, _, _): reason = r.short + (offline ? " (checked offline)" : "")
            case let .failed(_, message, _, _, _): reason = message
            default: reason = "Attend didn't record it"
            }
            post(eventId, RollCallText.notRecorded(p.name, at: point.name, reason: reason), error: true)
        }
    }

    /// What a tick's scan outcome means for taking it back later. Only a brand-new first scan at the
    /// scan point, confirmed by Attend, belongs to the roll call; anything already there is left alone.
    /// Also returns the scan's client_scan_id (ours, recorded or queued) and whether it was queued
    /// after a timeout (the request may have landed anyway).
    static func state(for outcome: ScanOutcome, hadEarlierScan: Bool) -> (RollCallScanState, String?, Bool) {
        switch outcome {
        case let .scanned(clientScanId, result, _):
            let fresh = !result.isAlreadyScanned && !result.deduplicated && !hadEarlierScan
            return fresh ? (.recorded, clientScanId, false) : (.preExisting, nil, false)
        case .alreadyScanned, .rejected(_, .alreadyCheckedIn, _, _, _, _, _, _):
            return (.preExisting, nil, false)
        case let .queued(clientScanId, _, _, reason, _):
            return (.queued, clientScanId, reason == ScanRepository.timeoutReason)
        case .rejected, .failed:
            return (.notRecorded, nil, false)
        }
    }

    /// Unticked while their scan waits in the offline queue (looked up now, not when they were
    /// tapped): dropping it from the queue is the whole undo, and never a DELETE. If it synced in the
    /// meantime, it's left alone.
    private func takeBackQueued(_ eventId: String, _ p: Participant, at point: RollCallScanPoint, clientScanId: String?,
                                timedOut: Bool) async {
        let id = p.participantEventId
        if let clientScanId {
            if await scans.discardPending(clientScanId) {
                if timedOut {
                    await settleAfterTimeout(eventId, p, at: point)
                } else {
                    // It never left this phone.
                    update(eventId) { $0.noteScan(id, nil) }
                }
                return
            }
            // Sent, but Attend turned it down: nothing was recorded.
            if scans.rejections.contains(where: { $0.clientScanId == clientScanId && !$0.stillRecorded }) {
                update(eventId) { $0.noteScan(id, nil) }
                return
            }
        }
        update(eventId) { $0.noteScan(id, .kept) }
        post(eventId, RollCallText.keptSynced(p.name, at: point.name))
    }

    /// The queued copy is gone, but the request that timed out may have reached Attend anyway. If
    /// Attend shows any scan there, it stays (it can't be told apart from anyone else's).
    private func settleAfterTimeout(_ eventId: String, _ p: Participant, at point: RollCallScanPoint) async {
        let id = p.participantEventId
        do {
            let fresh = try await participants.detail(eventId, participantEventId: id)
            if RollCallLogic.scanCount(fresh, at: point.id) == 0 {
                update(eventId) { $0.noteScan(id, nil) }
            } else {
                update(eventId) { $0.noteScan(id, .kept) }
                post(eventId, RollCallText.keptTimedOut(p.name, at: point.name))
            }
        } catch {
            guard !error.isCancellation else { return }
            update(eventId) { $0.noteScan(id, .kept) }
            post(eventId, error.isTransient ? RollCallText.keptOffline(p.name, at: point.name)
                                            : RollCallText.keptUnchecked(p.name, at: point.name, reason: error.friendlyMessage),
                 error: !error.isTransient)
        }
    }

    /// A tick whose scan may or may not have landed (the app closed mid-send): drop it if it's still
    /// queued, otherwise leave whatever is there alone.
    private func takeBackUncertain(_ eventId: String, _ p: Participant, at point: RollCallScanPoint) async {
        let id = p.participantEventId
        let queued = scans.pending.filter { $0.eventId == eventId && $0.scanContextId == point.id && $0.input.participantId == id }
        var dropped = false
        for q in queued {
            if await scans.discardPending(q.clientScanId) { dropped = true }
        }
        if dropped {
            update(eventId) { $0.noteScan(id, nil) }
            return
        }
        update(eventId) { $0.noteScan(id, .kept) }
        post(eventId, RollCallText.keptUncertain(p.name, at: point.name))
    }

    /// Takes back the scan the roll call made, but only after Attend confirms it's still the only scan
    /// at the scan point (its undo deletes every scan there). Otherwise the scan stays.
    private func takeBackRecorded(_ eventId: String, _ p: Participant, at point: RollCallScanPoint) async {
        let id = p.participantEventId
        let fresh: Participant
        do {
            fresh = try await participants.detail(eventId, participantEventId: id)
        } catch {
            guard !error.isCancellation else { return }
            update(eventId) { $0.noteScan(id, .kept) }
            post(eventId, error.isTransient ? RollCallText.keptOffline(p.name, at: point.name)
                                            : RollCallText.keptUnchecked(p.name, at: point.name, reason: error.friendlyMessage),
                 error: !error.isTransient)
            return
        }
        // Ticked again while that was loading: their scan is wanted after all.
        guard let session = sessions[eventId], !session.isAccounted(id), session.scanState(id) == .recorded else { return }
        switch RollCallLogic.scanCount(fresh, at: point.id) {
        case 0:
            // Already gone (taken back elsewhere): nothing left to do.
            update(eventId) { $0.noteScan(id, nil) }
        case 1:
            do {
                _ = try await scans.undo(eventId: eventId, participantEventId: id, scanContextId: point.id)
                await participants.applyUndo(eventId, participantEventId: id, scanContextId: point.id, contexts: [])
                update(eventId) { $0.noteScan(id, nil) }
            } catch {
                guard !error.isCancellation else { return }
                update(eventId) { $0.noteScan(id, .kept) }
                post(eventId, error.isTransient ? RollCallText.keptOffline(p.name, at: point.name)
                                                : RollCallText.keptUndoFailed(p.name, at: point.name, reason: error.friendlyMessage),
                     error: !error.isTransient)
            }
        default:
            update(eventId) { $0.noteScan(id, .kept) }
            post(eventId, RollCallText.keptOthers(p.name, at: point.name))
        }
    }
}
