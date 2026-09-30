import Foundation

/// Pure ticket helpers shared by the Tickets screens and the "My ticket" widget.
enum TicketLogic {

    /// What the attendee needs to know about a registration at a glance.
    enum Status: Hashable, Sendable {
        case ready
        case checkedIn
        /// Registration isn't finished; `reason` explains who needs to act.
        case incomplete(label: String, reason: String)
        case closed(label: String, reason: String)

        var label: String {
            switch self {
            case .ready: "Ready"
            case .checkedIn: "Checked in"
            case .incomplete(let label, _), .closed(let label, _): label
            }
        }

        var isClosed: Bool { if case .closed = self { true } else { false } }
    }

    static func status(_ t: Ticket) -> Status {
        let display = t.displayStatus?.lowercased()
        if t.checkedIn { return .checkedIn }
        if t.confirmed { return .ready }
        if display == "withdrawn" || t.status == "withdrawn" {
            return .closed(label: "Withdrawn", reason: "You've withdrawn from this event. Contact the organisers if that's a mistake.")
        }
        if display == "rejected" || t.status == "rejected" {
            return .closed(label: "Not accepted", reason: "This registration wasn't accepted. The organisers can tell you more.")
        }
        if display == "awaiting parent" || t.status == "awaiting_guardian" {
            return .incomplete(label: "Waiting on guardian", reason: "A parent or guardian still needs to complete their part of the form.")
        }
        return .incomplete(label: "Finish registration", reason: "You still have a few steps left in your registration form.")
    }

    // MARK: Countdown

    enum Countdown: Hashable, Sendable {
        case upcoming(TimeInterval)
        case live
        case ended
        case unknown
    }

    static func countdown(_ event: TicketEvent, now: Date = Date()) -> Countdown {
        guard let start = Time.parse(event.startsAt) else { return .unknown }
        let end = Time.parse(event.endsAt) ?? start.addingTimeInterval(86_400)
        if now < start { return .upcoming(start.timeIntervalSince(now)) }
        if now > end { return .ended }
        return .live
    }

    /// "2d 4h 10m" / "4h 10m 5s" / "10m 5s": the ticking countdown on the ticket detail.
    static func clock(_ interval: TimeInterval) -> String {
        let total = Int(max(0, interval))
        let days = total / 86_400
        let hours = total / 3600 % 24
        let minutes = total / 60 % 60
        let seconds = total % 60
        if days > 0 { return "\(days)d \(hours)h \(minutes)m" }
        if hours > 0 { return "\(hours)h \(minutes)m \(seconds)s" }
        return "\(minutes)m \(seconds)s"
    }

    /// Short relative label for chips and widgets: "Happening now", "Today · doors 9:00 AM",
    /// "Tomorrow · 9:00 AM", "in 3 days", "in 5 weeks", "Ended". Calendar days are counted in the
    /// event's own timezone so "tomorrow" means tomorrow at the venue.
    static func relativeLabel(_ event: TicketEvent, now: Date = Date()) -> String {
        relativeLabel(start: event.startsAt, end: event.endsAt, tz: event.timezone, now: now)
    }

    static func relativeLabel(start startISO: String?, end endISO: String?, tz: String?, now: Date = Date()) -> String {
        guard let start = Time.parse(startISO) else { return "Date to be announced" }
        let end = Time.parse(endISO) ?? start.addingTimeInterval(86_400)
        if now >= start { return now > end ? "Ended" : "Happening now" }
        let zone = Time.zone(tz)
        let days = CalendarDay(now, in: zone).days(until: CalendarDay(start, in: zone))
        let time = Time.time(start, zone: zone)
        switch days {
        case ...0:
            let mins = Int(start.timeIntervalSince(now) / 60)
            return mins < 60 ? "Starts in \(max(mins, 1)) min" : "Today · doors \(time)"
        case 1: return "Tomorrow · \(time)"
        case ..<14: return "in \(days) days"
        case ..<60: return "in \(days / 7) weeks"
        default:
            let months = days / 30
            return months <= 1 ? "in a month" : "in \(months) months"
        }
    }

    /// Upcoming and live first (soonest first), then past events (most recent first).
    static func sorted(_ tickets: [Ticket], now: Date = Date()) -> (current: [Ticket], past: [Ticket]) {
        let past = tickets.filter { countdown($0.event, now: now) == .ended }
        let current = tickets.filter { countdown($0.event, now: now) != .ended }
        return (
            current.sorted { (Time.parse($0.event.startsAt) ?? .distantFuture) < (Time.parse($1.event.startsAt) ?? .distantFuture) },
            past.sorted { (Time.parse($0.event.startsAt) ?? .distantPast) > (Time.parse($1.event.startsAt) ?? .distantPast) }
        )
    }

    /// The passes the detail screen can swipe between: confirmed tickets in the same order as the tickets list,
    /// always including `openedId` (even if it isn't confirmed). Just `openedId` until the list is known or when
    /// it isn't in the list.
    static func pagerIds(_ tickets: [Ticket]?, openedId: String, now: Date = Date()) -> [String] {
        guard let tickets, tickets.contains(where: { $0.id == openedId }) else { return [openedId] }
        let (current, past) = sorted(tickets, now: now)
        var seen = Set<String>()
        return (current + past).filter { $0.confirmed || $0.id == openedId }.map(\.id).filter { seen.insert($0).inserted }
    }

    /// The ticket to feature (e.g. in the widget): live or next upcoming, open registrations only.
    static func next(_ tickets: [Ticket], now: Date = Date()) -> Ticket? {
        let current = sorted(tickets, now: now).current.filter { !status($0).isClosed }
        return current.first { countdown($0.event, now: now) == .live } ?? current.first
    }

    // MARK: Venue

    static func venueLines(_ e: TicketEvent) -> (String, String?) {
        let address = e.locationAddress?.nonBlank
        let cityCountry = [e.locationCity?.nonBlank, e.locationCountry?.nonBlank].compactMap { $0 }.joined(separator: ", ").nonBlank
        if let address { return (address, cityCountry) }
        if let cityCountry { return (cityCountry, nil) }
        return ("Venue to be announced", nil)
    }

    static func hasVenue(_ e: TicketEvent) -> Bool {
        (e.locationLatitude != nil && e.locationLongitude != nil) || e.locationAddress?.nonBlank != nil || e.locationCity?.nonBlank != nil
    }

    /// The free-text query for the venue ("1 Example St, Sydney, AU"), or nil if there's nothing to search.
    static func venueQuery(_ e: TicketEvent) -> String? {
        [e.locationAddress, e.locationCity, e.locationCountry].compactMap { $0?.nonBlank }.joined(separator: ", ").nonBlank
    }

    static let incidentURL = URL(string: "https://hack.club/incident")!
    static let hotlineURL = URL(string: "tel:+18556254225")!
    static let hotlineDisplay = "+1 (855) 625 4225"
}
