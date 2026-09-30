import Foundation

enum EventLogic {
    /// Live event first, then the next upcoming one, else the most recent.
    static func suggestEvent(_ events: [Event], now: Date = Date()) -> Event? {
        guard !events.isEmpty else { return nil }
        if let live = events.first(where: { Time.phase($0.startsAt, $0.endsAt, now: now) == .live }) { return live }
        let upcoming = events.filter { Time.phase($0.startsAt, $0.endsAt, now: now) == .upcoming }
        if let next = upcoming.min(by: { (Time.parse($0.startsAt) ?? .distantFuture) < (Time.parse($1.startsAt) ?? .distantFuture) }) {
            return next
        }
        return events.max { (Time.parse($0.startsAt) ?? .distantPast) < (Time.parse($1.startsAt) ?? .distantPast) }
    }

    /// Picks the context whose window contains now, else the first check-in context, else the first.
    static func defaultContext(_ contexts: [ScanContext], now: Date = Date()) -> ScanContext? {
        contexts.first { isLive($0, now: now) } ?? contexts.first(where: \.checksIn) ?? contexts.first
    }

    /// True when the context has a time window and `now` is inside it.
    static func isLive(_ c: ScanContext, now: Date = Date()) -> Bool {
        guard let s = Time.parse(c.startsAt), let e = Time.parse(c.endsAt) else { return false }
        return now >= s && now <= e
    }

    /// "Global admin", "Event admin", "Ops"…
    static func roleLabel(_ role: String?) -> String? {
        switch role {
        case nil: nil
        case "global_admin": "Global admin"
        case "series_member": "Series member"
        case "event_admin": "Event admin"
        case "safeguarding_lead": "Safeguarding lead"
        case "ops": "Ops"
        case "limited": "Limited"
        case "read_only": "Read only"
        case let other?: other.replacingOccurrences(of: "_", with: " ").capitalizedFirst
        }
    }
}

extension String {
    /// "hello world" → "Hello world".
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
