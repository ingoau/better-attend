import Foundation

/// State for the People list. Filtering itself is `PeopleFilter` (pure, tested); the roster comes
/// straight from `app.participants`, so scans, undo and detail fetches show up here instantly.
@MainActor
@Observable
final class PeopleModel {
    private struct Choice {
        var quick: QuickFilter
        var options: FilterOptions
        var sort: PeopleSort
    }

    /// Chip / filter / sort choices per event, remembered for this app session.
    private static var remembered: [String: Choice] = [:]

    private(set) var eventId: String?
    var query = ""
    var quick: QuickFilter = .all { didSet { remember() } }
    var options = FilterOptions() { didSet { remember() } }
    var sort: PeopleSort = .name { didSet { remember() } }

    private(set) var syncError: String?
    /// Server search results, used only when nothing in the local roster matches.
    private(set) var remoteResults: [Participant]?
    private(set) var remoteSearching = false
    private(set) var remoteError: String?
    /// People with a check-in / undo in flight from a swipe.
    private(set) var busy: Set<String> = []
    var toast: Toast?

    /// Switches to another event, restoring whatever the user last chose there.
    func bind(eventId: String?) {
        guard eventId != self.eventId else { return }
        self.eventId = eventId
        let choice = eventId.flatMap { Self.remembered[$0] }
        quick = choice?.quick ?? .all
        options = choice?.options ?? FilterOptions()
        sort = choice?.sort ?? .name
        syncError = nil
        remoteResults = nil
        remoteError = nil
        remoteSearching = false
    }

    func clearAll() {
        quick = .all
        options = FilterOptions()
        query = ""
    }

    private func remember() {
        guard let eventId else { return }
        Self.remembered[eventId] = Choice(quick: quick, options: options, sort: sort)
    }

    // MARK: Sync

    /// Delta sync (full every few hours, handled by the repository). Safe to call often.
    func sync(_ app: AppModel, event: Event) async {
        guard event.canViewParticipants else { return }
        do {
            try await app.participants.sync(event.id)
            if eventId == event.id { syncError = nil }
        } catch {
            if !error.isCancellation, eventId == event.id { syncError = error.friendlyMessage }
        }
    }

    // MARK: Remote search

    /// Asks the server when the local roster has no match (someone registered since the last sync,
    /// or a partial roster). Debounced by the caller's `.task(id:)`.
    func remoteSearch(_ app: AppModel, eventId: String, roster: [Participant]) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        remoteResults = nil
        remoteError = nil
        guard q.count >= 2, !roster.contains(where: { PeopleFilter.matchesQuery($0, q) }) else {
            remoteSearching = false
            return
        }
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled else { return }
        remoteSearching = true
        defer { if query.trimmingCharacters(in: .whitespacesAndNewlines) == q { remoteSearching = false } }
        do {
            let results = try await app.participants.search(eventId, query: q)
            guard !Task.isCancelled, self.eventId == eventId else { return }
            remoteResults = results
        } catch {
            guard !error.isCancellation, self.eventId == eventId else { return }
            remoteError = error.friendlyMessage
        }
    }

    // MARK: Swipe actions

    func checkIn(_ app: AppModel, event: Event, _ p: Participant) async {
        guard !busy.contains(p.id) else { return }
        busy.insert(p.id)
        defer { busy.remove(p.id) }
        let context = await CheckInActions.defaultContext(app, eventId: event.id)
        let outcome = await CheckInActions.checkIn(app, eventId: event.id, participantEventId: p.participantEventId, context: context)
        toast = CheckInActions.toast(for: outcome, name: p.name, context: context, tz: event.timezone)
    }

    func undoCheckIn(_ app: AppModel, event: Event, _ p: Participant) async {
        guard !busy.contains(p.id) else { return }
        busy.insert(p.id)
        defer { busy.remove(p.id) }
        let contextId = PeopleFilter.checkInScan(p)?.scanContextId
        do {
            let res = try await CheckInActions.undo(app, eventId: event.id, participantEventId: p.participantEventId, scanContextId: contextId)
            Haptics.confirm()
            toast = Toast(message: res.deletedScans == 0 ? "\(p.name) had no check-in to undo" : "Undid \(p.name)'s check-in",
                          systemImage: "arrow.uturn.backward.circle.fill", tone: .neutral)
        } catch {
            guard !error.isCancellation else { return }
            Haptics.reject()
            toast = .error("Couldn't undo: \(error.friendlyMessage)")
        }
    }
}

/// Check-in / undo shared by the list's swipe actions and the detail screen.
@MainActor
enum CheckInActions {
    /// The scan point a manual check-in goes to: the live check-in context, else the first check-in context.
    static func defaultContext(_ app: AppModel, eventId: String) async -> ScanContext? {
        await app.events.loadContexts(eventId)
        var contexts = app.events.cachedContexts(eventId) ?? []
        if contexts.isEmpty { contexts = (try? await app.events.refreshContexts(eventId)) ?? [] }
        let checkIn = contexts.filter(\.checksIn)
        return EventLogic.defaultContext(checkIn.isEmpty ? contexts : checkIn)
    }

    static func checkIn(_ app: AppModel, eventId: String, participantEventId: String, context: ScanContext?) async -> ScanOutcome {
        let outcome = await app.scans.submit(
            eventId: eventId,
            input: ScanInput(participantId: participantEventId, source: "manual"),
            scanContextId: context?.id,
            scanContextName: context?.name
        )
        switch outcome {
        case .scanned, .queued: Haptics.confirm()
        // Already scanned there = nothing changed, so it gets the "didn't happen" cue.
        case .alreadyScanned, .failed: Haptics.reject()
        }
        return outcome
    }

    static func toast(for outcome: ScanOutcome, name: String, context: ScanContext?, tz: String?) -> Toast {
        let at = context.map { " at \($0.name)" } ?? ""
        switch outcome {
        case .scanned:
            return Toast(message: "Checked in \(name)\(at)")
        case .alreadyScanned(_, let result, _):
            let when = Time.time(result.firstScannedAt, tz: tz).map { ", \($0)" } ?? ""
            return .info("Already scanned\(at)\(when)", systemImage: "checkmark.circle")
        case .queued:
            return .info("You're offline. The check-in will sync automatically.", systemImage: "icloud.and.arrow.up")
        case .failed(_, let message, _, _):
            return .error(message)
        }
    }

    /// Deletes scans (`scanContextId` nil = everywhere) and reflects it in the roster straight away.
    @discardableResult
    static func undo(_ app: AppModel, eventId: String, participantEventId: String, scanContextId: String?) async throws -> UndoResult {
        let res = try await app.scans.undo(eventId: eventId, participantEventId: participantEventId, scanContextId: scanContextId)
        await app.events.loadContexts(eventId)
        await app.participants.applyUndo(eventId, participantEventId: participantEventId, scanContextId: scanContextId,
                                         contexts: app.events.cachedContexts(eventId) ?? [])
        return res
    }
}
