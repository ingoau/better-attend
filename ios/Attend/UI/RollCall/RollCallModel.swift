import Foundation

/// Screen state for a roll call. The session itself lives in `app.rollCalls` (persisted); this holds
/// the search, filter and sheets, and records ticks as scans when the roll call has a scan point.
@MainActor
@Observable
final class RollCallModel {
    var query = ""
    var filter: RollCallFilter = .missing
    var toast: Toast?
    var showAdd = false
    var confirmFinish = false
    var showSummary = false

    /// One queue of scan / undo requests per person, so a quick tick-untick-tick lands in order.
    @ObservationIgnored private var chains: [String: Task<Void, Never>] = [:]

    func toggle(_ app: AppModel, event: Event, _ entry: RollCallEntry) {
        set(app, event: event, entry.participant, accounted: !entry.accounted)
    }

    /// "Add someone": marks a person present, adding them to the list if they weren't expected.
    func add(_ app: AppModel, event: Event, _ p: Participant) {
        set(app, event: event, p, accounted: true)
        toast = Toast(message: "\(p.name) is accounted for")
    }

    /// Ticks or unticks someone on this phone straight away; a scan (or its undo) follows in the background.
    func set(_ app: AppModel, event: Event, _ p: Participant, accounted: Bool) {
        let id = p.participantEventId
        guard let session = app.rollCalls.session(event.id) else { return }
        let at = Time.nowISO()
        var changed = false
        app.rollCalls.update(event.id) { s in
            if accounted && !s.isOnList(id) {
                s.add(id, at: at)
                changed = true
            } else {
                changed = s.set(id, accounted: accounted, at: at)
            }
        }
        guard changed else { return }
        if accounted { Haptics.impact() } else { Haptics.tap() }
        guard let point = session.scanPoint else { return }
        let previous = chains[id]
        chains[id] = Task { [weak self] in
            await previous?.value
            guard let self else { return }
            if accounted {
                await record(app, eventId: event.id, p, at: point)
            } else {
                await takeBack(app, eventId: event.id, p, at: point)
            }
        }
    }

    /// Records a tick as a scan at the roll call's scan point (queued offline like any scan).
    private func record(_ app: AppModel, eventId: String, _ p: Participant, at point: RollCallScanPoint) async {
        let id = p.participantEventId
        guard let session = app.rollCalls.session(eventId), session.isAccounted(id) else { return }
        // Unticked offline earlier, so the scan this roll call made is still there.
        if session.stillRecordedIds.contains(id) {
            app.rollCalls.update(eventId) { $0.noteRecorded(id) }
            return
        }
        let outcome = await app.scans.submit(
            eventId: eventId,
            input: ScanInput(participantId: id, source: "manual"),
            scanContextId: point.id,
            scanContextName: point.name,
            checksIn: point.checksIn,
            // Staff can see them standing there: don't second-guess it like a ticket scan.
            enforceAdmission: false
        )
        let notRecorded = "\(p.name) is ticked, but no scan was recorded at \(point.name)"
        switch outcome {
        case .scanned, .queued:
            app.rollCalls.update(eventId) { $0.noteRecorded(id) }
        case .alreadyScanned, .rejected(_, .alreadyCheckedIn, _, _, _, _, _, _):
            // They already had a scan there, so there's nothing of ours to take back later.
            break
        case let .rejected(_, reason, _, _, offline, _, _, _):
            toast = .error("\(notRecorded): \(reason.short)" + (offline ? " (checked offline)" : ""))
        case let .failed(_, message, _, _, _):
            toast = .error("\(notRecorded): \(message)")
        }
    }

    /// Takes back the scan this roll call recorded for someone who's been unticked. Never touches a
    /// scan they already had there before the roll call.
    private func takeBack(_ app: AppModel, eventId: String, _ p: Participant, at point: RollCallScanPoint) async {
        let id = p.participantEventId
        guard let session = app.rollCalls.session(eventId), !session.isAccounted(id), session.recordedIds.contains(id) else { return }
        // Still waiting to sync: dropping it from the queue is the whole undo.
        let queued = app.scans.pending.filter { $0.eventId == eventId && $0.scanContextId == point.id && $0.input.participantId == id }
        if !queued.isEmpty {
            for q in queued { await app.scans.discardPending(q.clientScanId) }
            app.rollCalls.update(eventId) { $0.noteUndone(id) }
            return
        }
        do {
            try await CheckInActions.undo(app, eventId: eventId, participantEventId: id, scanContextId: point.id)
            app.rollCalls.update(eventId) { $0.noteUndone(id) }
        } catch {
            guard !error.isCancellation else { return }
            app.rollCalls.update(eventId) { $0.noteStillRecorded(id) }
            if error.isTransient {
                toast = .info("You're offline, so \(p.name)'s scan at \(point.name) stays recorded.", systemImage: "icloud.slash")
            } else {
                toast = .error("Couldn't take back \(p.name)'s scan at \(point.name): \(error.friendlyMessage)")
            }
        }
    }

    /// Ends the roll call: forgets the session. Scans already recorded stay recorded.
    func end(_ app: AppModel, eventId: String) {
        app.rollCalls.end(eventId)
        query = ""
        filter = .missing
        Haptics.confirm()
    }
}
