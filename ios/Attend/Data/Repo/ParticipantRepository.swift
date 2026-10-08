import Foundation

@MainActor
@Observable
final class ParticipantRepository {
    static let fullSyncInterval: TimeInterval = 3 * 3600

    private(set) var rosters: [String: Roster] = [:]
    /// Event ids with a sync in flight.
    private(set) var syncing: Set<String> = []

    @ObservationIgnored private let api: AttendAPI
    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored private let mutex = AsyncMutex()
    @ObservationIgnored var onChange: () -> Void = {}
    /// Who signed up or withdrew since the last sync of a roster we already had (see `RosterAlerts`), with
    /// when that previous sync was. Never called for the first download, which would list everyone.
    @ObservationIgnored var onRosterChanges: (_ eventId: String, _ changes: RosterChanges, _ previousSyncAt: String?) -> Void = { _, _, _ in }

    init(api: AttendAPI, cache: JsonCache) {
        self.api = api
        self.cache = cache
    }

    func roster(_ eventId: String) -> Roster? { rosters[eventId] }

    /// The roster from memory, else from the disk cache. nil if we've never synced this event.
    @discardableResult
    func load(_ eventId: String) async -> Roster? {
        if let r = rosters[eventId] { return r }
        guard let cached = await cache.read(key(eventId), as: Roster.self) else { return nil }
        if rosters[eventId] == nil { rosters[eventId] = cached }
        return rosters[eventId]
    }

    /// Full sync the first time (and every few hours to prune deletions), deltas via
    /// `updated_since` otherwise. Throws on failure; the cached roster stays intact.
    @discardableResult
    func sync(_ eventId: String, forceFull: Bool = false) async throws -> Roster {
        try await mutex.withLock {
            syncing.insert(eventId)
            defer { syncing.remove(eventId) }
            let existing = await load(eventId)
            let lastFull = Time.parse(existing?.lastFullSyncAt)
            let needFull = forceFull || existing?.syncedAt == nil || lastFull == nil
                || Date().timeIntervalSince(lastFull!) > Self.fullSyncInterval
            let now = Time.nowISO()
            let roster: Roster
            if needFull || existing == nil {
                let res = try await api.participants(eventId: eventId)
                roster = Roster(eventId: eventId, participants: Roster.sortedByName(res.participants),
                                syncedAt: res.syncedAt, lastFullSyncAt: now, lastSyncAt: now)
            } else {
                let old = existing!
                let res = try await api.participants(eventId: eventId, updatedSince: old.syncedAt)
                var merged = old.byEventId
                for p in res.participants { merged[p.participantEventId] = Roster.mergeKeepingDetail(merged[p.participantEventId], p) }
                var updated = old
                updated.participants = Roster.sortedByName(Array(merged.values))
                updated.syncedAt = res.syncedAt ?? old.syncedAt
                updated.lastSyncAt = now
                roster = updated
            }
            rosters[eventId] = roster
            await cache.write(key(eventId), roster)
            onChange()
            if let existing {
                let changes = RosterAlerts.changes(old: existing, new: roster)
                if !changes.isEmpty { onRosterChanges(eventId, changes, existing.lastSyncAt) }
            }
            return roster
        }
    }

    /// Merge a participant we learned about elsewhere (scan response, detail fetch) into the roster.
    func upsert(_ eventId: String, _ participant: Participant) async {
        // Never invent a roster from a single scan/detail: stats would read "3 of 3 checked in".
        guard var roster = await load(eventId) else { return }
        var merged = roster.byEventId
        merged[participant.participantEventId] = Roster.mergeKeepingDetail(merged[participant.participantEventId], participant)
        roster.participants = Roster.sortedByName(Array(merged.values))
        rosters[eventId] = roster
        await cache.write(key(eventId), roster)
        onChange()
    }

    /// Drops a registration that was deleted on the server. Delta syncs can't report deletions, so
    /// without this the person would linger until the next full sync. Waits for any sync in flight,
    /// so that sync can't write the person back.
    func remove(_ eventId: String, participantEventId: String) async {
        await mutex.withLock {
            guard var roster = await load(eventId) else { return }
            let before = roster.participants.count
            roster.participants.removeAll { $0.participantEventId == participantEventId }
            guard roster.participants.count != before else { return }
            rosters[eventId] = roster
            await cache.write(key(eventId), roster)
            onChange()
        }
    }

    /// Reflects an undo locally so lists update instantly (the next delta sync confirms it).
    func applyUndo(_ eventId: String, participantEventId: String, scanContextId: String?, contexts: [ScanContext]) async {
        guard let roster = await load(eventId), var p = roster.byEventId[participantEventId] else { return }
        let remaining = scanContextId.map { id in p.scansByContext.filter { $0.scanContextId != id } } ?? []
        let checkInIds = Set(contexts.filter(\.checksIn).map(\.id))
        p.scansByContext = remaining
        p.checkedInAt = remaining
            .filter { $0.checksIn || checkInIds.contains($0.scanContextId) }
            .compactMap(\.firstScannedAt)
            .min { (Time.parse($0) ?? .distantFuture) < (Time.parse($1) ?? .distantFuture) }
        await upsert(eventId, p)
    }

    func detail(_ eventId: String, participantEventId: String) async throws -> Participant {
        let full = try await api.participant(eventId: eventId, participantEventId: participantEventId)
        await upsert(eventId, full)
        return full
    }

    func search(_ eventId: String, query: String) async throws -> [Participant] {
        try await api.searchParticipants(eventId: eventId, query: query)
    }

    func clear() async {
        rosters = [:]
        await cache.clear()
    }

    private func key(_ eventId: String) -> String { "roster_\(eventId)" }
}
