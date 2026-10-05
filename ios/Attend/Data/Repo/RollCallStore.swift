import Foundation

/// The roll call in progress for each event (at most one per event), kept in memory and in the
/// encrypted cache so it survives restarts. When the cache fails closed (no encryption key) writes are
/// dropped, so a roll call then lasts until the app closes, like everything else.
@MainActor
@Observable
final class RollCallStore {
    private(set) var sessions: [String: RollCallSession] = [:]

    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored private var loaded: Set<String> = []
    /// Writes run one after another, so an older snapshot never lands after a newer one.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?

    init(cache: JsonCache) {
        self.cache = cache
    }

    func session(_ eventId: String) -> RollCallSession? { sessions[eventId] }

    /// The event's roll call from memory, else from the disk cache.
    @discardableResult
    func load(_ eventId: String) async -> RollCallSession? {
        if loaded.contains(eventId) { return sessions[eventId] }
        let saved = await cache.read(RollCallLogic.cacheKey(eventId), as: RollCallSession.self)
        if !loaded.contains(eventId) {
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

    /// Ends the roll call and forgets it.
    func end(_ eventId: String) {
        loaded.insert(eventId)
        sessions[eventId] = nil
        persist(eventId)
    }

    /// Forgets everything in memory (sign-out). The files go with the rest of the cache.
    func clear() {
        sessions = [:]
        loaded = []
    }

    private func persist(_ eventId: String) {
        let key = RollCallLogic.cacheKey(eventId)
        let snapshot = sessions[eventId]
        let cache = self.cache
        let previous = lastWrite
        lastWrite = Task {
            await previous?.value
            if let snapshot {
                await cache.write(key, snapshot)
            } else {
                await cache.remove(key)
            }
        }
    }
}
