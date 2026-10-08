import Foundation

/// The signed-in user's own tickets, cached so the QR code works with no signal at the door.
@MainActor
@Observable
final class TicketRepository {
    /// nil until the cache has been read.
    private(set) var tickets: [Ticket]?

    @ObservationIgnored private let api: AttendAPI
    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored var onChange: () -> Void = {}

    private static let key = "tickets"

    init(api: AttendAPI, cache: JsonCache) {
        self.api = api
        self.cache = cache
    }

    func loadCache() async {
        guard tickets == nil else { return }
        let cached = await cache.read(Self.key, as: [Ticket].self)
        if tickets == nil { tickets = cached }
    }

    @discardableResult
    func refresh() async throws -> [Ticket] {
        let fresh = try await api.tickets()
        // Keep detail extras (messages, travel) from earlier detail fetches.
        let old = Dictionary((tickets ?? []).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let merged = fresh.map { t -> Ticket in
            guard let o = old[t.id] else { return t }
            var m = t
            if m.messages.isEmpty { m.messages = o.messages }
            m.travelInbound = t.travelInbound ?? o.travelInbound
            return m
        }
        tickets = merged
        await cache.write(Self.key, merged)
        onChange()
        return merged
    }

    @discardableResult
    func refreshTicket(_ id: String) async throws -> Ticket {
        let t = try await api.ticket(id: id)
        var list = tickets ?? []
        if let i = list.firstIndex(where: { $0.id == id }) { list[i] = t } else { list.append(t) }
        tickets = list
        await cache.write(Self.key, list)
        onChange()
        return t
    }

    func ticket(_ id: String) -> Ticket? { tickets?.first { $0.id == id } }

    func walletPass(for ticket: Ticket) async throws -> Data {
        guard let url = ticket.appleWalletUrl else { throw URLError(.fileDoesNotExist) }
        return try await api.walletPass(from: url)
    }

    func clear() { tickets = [] }
}

@MainActor
@Observable
final class TravelRepository {
    private(set) var calendars: [String: TravelCalendar] = [:]

    @ObservationIgnored private let api: AttendAPI
    @ObservationIgnored private let cache: JsonCache
    @ObservationIgnored var onChange: () -> Void = {}
    /// A fresh calendar arrived (pickup reminders reschedule from it).
    @ObservationIgnored var onRefreshed: (_ eventId: String, _ calendar: TravelCalendar) -> Void = { _, _ in }

    init(api: AttendAPI, cache: JsonCache) {
        self.api = api
        self.cache = cache
    }

    @discardableResult
    func load(_ eventId: String) async -> TravelCalendar? {
        if let c = calendars[eventId] { return c }
        guard let cached = await cache.read(key(eventId), as: TravelCalendar.self) else { return nil }
        if calendars[eventId] == nil { calendars[eventId] = cached }
        return calendars[eventId]
    }

    @discardableResult
    func refresh(_ eventId: String) async throws -> TravelCalendar {
        let cal = try await api.travel(eventId: eventId)
        calendars[eventId] = cal
        await cache.write(key(eventId), cal)
        onChange()
        onRefreshed(eventId, cal)
        return cal
    }

    func clear() { calendars = [:] }

    private func key(_ eventId: String) -> String { "travel_\(eventId)" }
}
