import Foundation

@MainActor
@Observable
final class EventRepository {
    /// nil until the cache has been read.
    private(set) var events: [Event]?
    private(set) var contexts: [String: [ScanContext]] = [:]

    @ObservationIgnored private let api: AttendAPI
    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored private let settings: SettingsStore
    @ObservationIgnored var onChange: () -> Void = {}

    init(api: AttendAPI, cache: JsonCache, settings: SettingsStore) {
        self.api = api
        self.cache = cache
        self.settings = settings
    }

    /// The event the organizer is working on: the saved choice, else whatever is live or next.
    var selectedEvent: Event? {
        guard let events else { return nil }
        return events.first { $0.id == settings.selectedEventId } ?? EventLogic.suggestEvent(events)
    }

    /// Loads cached events. With nothing cached, `events` stays nil ("still loading") until the
    /// first refresh answers, so screens never claim "no events" before the server has.
    func loadCache() async {
        guard events == nil else { return }
        if let cached = await cache.read("events", as: [Event].self), events == nil { events = cached }
    }

    @discardableResult
    func refresh() async throws -> [Event] {
        let list: [Event]
        do {
            list = try await api.events()
        } catch {
            // Nothing cached and the server unreachable: stop "loading" so screens can show the error.
            if events == nil, !error.isCancellation { events = [] }
            throw error
        }
        events = list
        await cache.write("events", list)
        onChange()
        return list
    }

    func select(_ eventId: String) {
        settings.selectedEventId = eventId
        onChange()
    }

    func cachedContexts(_ eventId: String) -> [ScanContext]? { contexts[eventId] }

    func loadContexts(_ eventId: String) async {
        guard contexts[eventId] == nil else { return }
        if let cached = await cache.read("contexts_\(eventId)", as: [ScanContext].self), contexts[eventId] == nil {
            contexts[eventId] = cached
        }
    }

    @discardableResult
    func refreshContexts(_ eventId: String) async throws -> [ScanContext] {
        let list = try await api.scanContexts(eventId: eventId)
        contexts[eventId] = list
        await cache.write("contexts_\(eventId)", list)
        onChange()
        return list
    }

    func clear() {
        events = []
        contexts = [:]
    }
}
