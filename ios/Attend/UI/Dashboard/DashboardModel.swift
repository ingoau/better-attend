import Foundation

/// Home's sync bookkeeping: what's in flight, when it last worked, and the scan feed for roles that
/// can't see the roster. The numbers themselves come straight from the repositories.
@MainActor
@Observable
final class DashboardModel {
    /// The event the state below belongs to; switching events starts afresh.
    private(set) var eventId: String?
    /// Any sync in flight (drives the quiet "Syncing…" line).
    private(set) var refreshing = false
    private(set) var error: String?
    private(set) var lastUpdated: Date?
    /// Latest scans; only fetched for roles that can't see participants.
    private(set) var scans: [Scan]?

    @ObservationIgnored private var lastAttempt: Date?
    @ObservationIgnored private var contextsFetched = false
    @ObservationIgnored private var syncTask: Task<Void, Never>?
    @ObservationIgnored private var syncSeq = 0

    /// Polls within this long of the last attempt are skipped, so bouncing between tabs doesn't burn
    /// the shared per-IP rate limit (300 requests / 5 min for the whole venue).
    static let throttle: TimeInterval = 20

    private func reset(for id: String) {
        guard eventId != id else { return }
        eventId = id
        refreshing = false
        error = nil
        lastUpdated = nil
        scans = nil
        lastAttempt = nil
        contextsFetched = false
        syncTask = nil
        syncSeq += 1
    }

    /// Shows cached data immediately, then syncs. The roster sync is a cheap `updated_since` delta after
    /// the first time. `force` (pull to refresh) skips the throttle.
    ///
    /// The work runs in its own task, so a poll cancelled because Home left the screen can't leave
    /// `refreshing` stuck on. Awaiting this waits for that work (the pull-to-refresh spinner).
    func refresh(_ event: Event, app: AppModel, force: Bool) async {
        reset(for: event.id)
        if let running = syncTask {
            await running.value
            return
        }
        if !force, let lastAttempt, Date().timeIntervalSince(lastAttempt) < Self.throttle { return }

        syncSeq += 1
        let seq = syncSeq
        refreshing = true
        lastAttempt = Date()
        let task = Task { [weak self] in
            guard let self else { return }
            await self.sync(event, app: app, force: force)
            if self.syncSeq == seq {
                self.refreshing = false
                self.syncTask = nil
            }
        }
        syncTask = task
        await task.value
    }

    private func sync(_ event: Event, app: AppModel, force: Bool) async {
        let id = event.id
        if event.canViewParticipants { await app.participants.load(id) }
        await app.events.loadContexts(id)
        if event.travelEnabled { await app.travel.load(id) }

        // Independent requests run side by side; each reports its own failure.
        let events = Task { () -> Error? in
            guard force else { return nil }
            do { try await app.events.refresh(); return nil } catch { return error }
        }
        let people = Task { () -> Result<[Scan]?, Error> in
            do {
                if event.canViewParticipants {
                    try await app.participants.sync(id)
                    return .success(nil)
                }
                return .success(try await app.api.scans(eventId: id).scans)
            } catch {
                return .failure(error)
            }
        }
        let needContexts = force || !contextsFetched || app.events.cachedContexts(id) == nil
        let contexts = Task { () -> Bool in
            guard needContexts else { return false }
            return (try? await app.events.refreshContexts(id)) != nil
        }
        let travel = Task { () -> Error? in
            guard event.travelEnabled else { return nil }
            do { try await app.travel.refresh(id); return nil } catch { return error }
        }

        var errors: [Error] = []
        var feed: [Scan]?
        switch await people.value {
        case .success(let scans): feed = scans
        case .failure(let e): errors.append(e)
        }
        if let e = await travel.value { errors.append(e) }
        _ = await events.value // a failed event-list refresh keeps the cached list; not worth a banner
        let gotContexts = await contexts.value

        guard eventId == id else { return }
        errors.removeAll(where: \.isCancellation)
        if gotContexts { contextsFetched = true }
        error = errors.first?.friendlyMessage
        if errors.isEmpty { lastUpdated = Date() }
        if let feed { scans = feed }
    }
}
