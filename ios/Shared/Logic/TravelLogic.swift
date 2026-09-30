import Foundation

/// The status filter chips on the Travel screen.
enum TravelFilter: String, CaseIterable, Identifiable, Sendable {
    case all, arrivals, departures, awaitingPickup, pickedUp, minors

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: "All"
        case .arrivals: "Arrivals"
        case .departures: "Departures"
        case .awaitingPickup: "Awaiting pickup"
        case .pickedUp: "Picked up"
        case .minors: "Unaccompanied minors"
        }
    }

    /// Shorter chip text ("UMs" instead of "Unaccompanied minors").
    var chipLabel: String { self == .minors ? "UMs" : label }

    var systemImage: String? {
        switch self {
        case .all: nil
        case .arrivals: "airplane.arrival"
        case .departures: "airplane.departure"
        case .awaitingPickup: "clock"
        case .pickedUp: "checkmark.circle"
        case .minors: "exclamationmark.triangle"
        }
    }
}

/// Transport modes in display order. Entries with a missing or unknown mode count as `.other`.
enum TravelMode: String, CaseIterable, Identifiable, Sendable {
    case plane, train, bus, car, other

    var id: String { rawValue }

    var label: String {
        switch self {
        case .plane: "Plane"
        case .train: "Train"
        case .bus: "Bus"
        case .car: "Car"
        case .other: "Other"
        }
    }

    static func of(_ entry: TravelEntry) -> TravelMode {
        entry.mode.flatMap(TravelMode.init(rawValue:)) ?? .other
    }

    /// SF Symbol for the mode; planes show landing / take-off by direction.
    static func systemImage(mode: String?, direction: String?) -> String {
        switch mode {
        case "plane":
            switch direction {
            case "inbound": "airplane.arrival"
            case "outbound": "airplane.departure"
            default: "airplane"
            }
        case "train": "tram.fill"
        case "bus": "bus.fill"
        case "car": "car.fill"
        default: "suitcase.rolling.fill"
        }
    }
}

/// How a journey's pickup is going, for the row pill.
struct TravelPickup: Hashable, Sendable {
    var label: String
    var systemImage: String
    var tone: Tone
}

/// One pinned-header section of the travel list. `date` is nil for "Unscheduled".
struct TravelSection: Identifiable, Hashable, Sendable {
    var key: String
    var date: CalendarDay?
    /// "Today", "Tomorrow", "Yesterday", "Saturday 3 October" or "Unscheduled".
    var title: String
    /// The full date next to a relative title ("Saturday 3 October"), else nil.
    var subtitle: String?
    var entries: [TravelEntry]

    var id: String { key }
}

/// Filtering, grouping and labels for the Travel screen (a port of Android's `TravelLogic`).
enum TravelLogic {
    static func isArrival(_ e: TravelEntry) -> Bool { e.direction == "inbound" }
    static func isDeparture(_ e: TravelEntry) -> Bool { e.direction == "outbound" }
    static func isPickedUp(_ e: TravelEntry) -> Bool { e.pickupState == "collected" || e.pickupState == "checked_in" }

    static func matches(_ e: TravelEntry, _ filter: TravelFilter) -> Bool {
        switch filter {
        case .all: true
        case .arrivals: isArrival(e)
        case .departures: isDeparture(e)
        case .awaitingPickup: e.pickupState == "awaiting_pickup"
        case .pickedUp: isPickedUp(e)
        case .minors: e.isUnaccompaniedMinor
        }
    }

    /// Every whitespace-separated term must appear in the name, preferred name, route, reference or notes.
    static func matchesQuery(_ e: TravelEntry, _ query: String) -> Bool {
        let terms = query.lowercased().split(whereSeparator: \.isWhitespace)
        if terms.isEmpty { return true }
        let haystack = [e.participantName, e.participantPreferredName, e.route, e.reference, e.details]
            .compactMap { $0 }
            .joined(separator: " \u{0} ")
            .lowercased()
        return terms.allSatisfy { haystack.contains($0) }
    }

    static func filter(_ entries: [TravelEntry], query: String, filter: TravelFilter, mode: TravelMode?) -> [TravelEntry] {
        entries.filter { matchesQuery($0, query) && matches($0, filter) && (mode == nil || TravelMode.of($0) == mode) }
    }

    /// Status chip counts, reflecting the current search and mode (so the numbers match what you'd see).
    static func filterCounts(_ entries: [TravelEntry], query: String, mode: TravelMode?) -> [TravelFilter: Int] {
        let base = entries.filter { matchesQuery($0, query) && (mode == nil || TravelMode.of($0) == mode) }
        return Dictionary(uniqueKeysWithValues: TravelFilter.allCases.map { f in (f, base.count { matches($0, f) }) })
    }

    /// Mode chip counts, reflecting the current search and status filter. Only modes that occur are returned.
    static func modeCounts(_ entries: [TravelEntry], query: String, filter: TravelFilter) -> [TravelMode: Int] {
        let base = entries.filter { matchesQuery($0, query) && matches($0, filter) }
        var out: [TravelMode: Int] = [:]
        for e in base { out[TravelMode.of(e), default: 0] += 1 }
        return out
    }

    /// Distinct transport modes present at all (the mode row only shows when there's more than one).
    static func modesPresent(_ entries: [TravelEntry]) -> Set<TravelMode> {
        Set(entries.map(TravelMode.of))
    }

    /// Groups entries by agenda date (ascending) with the unscheduled ones last. Within a day entries are
    /// ordered by time, then name. Titles are relative to `today` in the event's timezone.
    static func sections(_ entries: [TravelEntry], today: CalendarDay) -> [TravelSection] {
        var byDate: [CalendarDay: [TravelEntry]] = [:]
        var undated: [TravelEntry] = []
        for e in entries {
            if let d = parseDate(e.agendaDate) { byDate[d, default: []].append(e) } else { undated.append(e) }
        }
        var out = byDate.keys.sorted().map { date in
            let relative = relativeLabel(date, today: today)
            return TravelSection(
                key: date.description,
                date: date,
                title: relative ?? Time.longDay(date),
                subtitle: relative != nil ? Time.longDay(date) : nil,
                entries: sorted(byDate[date] ?? [])
            )
        }
        if !undated.isEmpty {
            out.append(TravelSection(key: "unscheduled", date: nil, title: "Unscheduled", subtitle: nil, entries: sorted(undated)))
        }
        return out
    }

    private static func sorted(_ entries: [TravelEntry]) -> [TravelEntry] {
        let keyed: [(entry: TravelEntry, at: Date, name: String)] = entries.map { e in
            (e, Time.parse(e.primaryTimeAt) ?? Date.distantFuture, e.name.lowercased())
        }
        let ordered = keyed.sorted { a, b in
            if a.at != b.at { return a.at < b.at }
            return a.name < b.name
        }
        return ordered.map(\.entry)
    }

    static func relativeLabel(_ date: CalendarDay, today: CalendarDay) -> String? {
        switch today.days(until: date) {
        case 0: "Today"
        case 1: "Tomorrow"
        case -1: "Yesterday"
        default: nil
        }
    }

    static func parseDate(_ s: String?) -> CalendarDay? {
        guard let s, s.count == 10 else { return nil }
        return CalendarDay(iso: s)
    }

    /// Today's date in the event's zone.
    static func today(tz: String?, now: Date = Date()) -> CalendarDay {
        CalendarDay(now, in: Time.zone(tz))
    }

    /// "Sydney · GMT+10" style label for the timezone chip.
    static func zoneLabel(_ tz: String?, now: Date = Date()) -> String? {
        guard let tz, let zone = TimeZone(identifier: tz) else { return nil }
        let city = (tz.split(separator: "/").last.map(String.init) ?? tz).replacingOccurrences(of: "_", with: " ")
        return "\(city) · \(gmtOffset(zone.secondsFromGMT(for: now)))"
    }

    /// "GMT", "GMT+10", "GMT+5:30", "GMT-7".
    static func gmtOffset(_ seconds: Int) -> String {
        guard seconds != 0 else { return "GMT" }
        let sign = seconds < 0 ? "-" : "+"
        let total = abs(seconds) / 60
        let h = total / 60, m = total % 60
        return m == 0 ? "GMT\(sign)\(h)" : String(format: "GMT%@%d:%02d", sign, h, m)
    }

    /// True when the device's zone shows a different wall-clock time than the event's zone right now.
    static func deviceZoneDiffers(_ tz: String?, now: Date = Date(), device: TimeZone = .current) -> Bool {
        guard let tz, let zone = TimeZone(identifier: tz) else { return false }
        return zone.secondsFromGMT(for: now) != device.secondsFromGMT(for: now)
    }

    /// "Australian Eastern Standard Time".
    static func zoneLongName(_ tz: String?, now: Date = Date()) -> String? {
        guard let tz, let zone = TimeZone(identifier: tz) else { return nil }
        let style: NSTimeZone.NameStyle = zone.isDaylightSavingTime(for: now) ? .daylightSaving : .standard
        return zone.localizedName(for: style, locale: .current)
    }

    static func pickup(_ state: String?) -> TravelPickup? {
        switch state {
        case "awaiting_pickup": TravelPickup(label: "Awaiting pickup", systemImage: "clock.fill", tone: .warning)
        case "collected": TravelPickup(label: "Picked up", systemImage: "checkmark.circle.fill", tone: .success)
        case "checked_in": TravelPickup(label: "Checked in", systemImage: "person.crop.circle.badge.checkmark", tone: .info)
        case "pickup_not_needed": TravelPickup(label: "No pickup", systemImage: "minus.circle", tone: .neutral)
        default: nil
        }
    }

    /// "7:25 AM" → ("7:25", "AM"); "19:25" → ("19:25", nil). Handles the narrow no-break space newer
    /// locales put before AM/PM.
    static func splitTime(_ time: String?) -> (clock: String?, meridiem: String?) {
        guard let time else { return (nil, nil) }
        let separators: Set<Character> = [" ", "\u{202F}", "\u{00A0}", "\t"]
        let trimmed = time.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{202F}\u{00A0}")))
        guard let i = trimmed.firstIndex(where: { separators.contains($0) }) else { return (trimmed, nil) }
        let rest = trimmed[i...].drop(while: { separators.contains($0) })
        return (String(trimmed[..<i]), rest.isEmpty ? nil : String(rest))
    }

    /// VoiceOver description of a row.
    static func accessibilityLabel(_ e: TravelEntry, time: String?) -> String {
        var parts = [e.name]
        if e.isUnaccompaniedMinor { parts.append("unaccompanied minor") }
        parts.append((isArrival(e) ? "arrives " : "departs ") + (time ?? "unscheduled"))
        if let route = e.route?.nonBlank { parts.append(route) }
        if let reference = e.reference?.nonBlank { parts.append(reference) }
        if let pickup = pickup(e.pickupState) { parts.append(pickup.label) }
        if !e.groups.isEmpty { parts.append(e.groups.map(\.name).joined(separator: ", ")) }
        return parts.joined(separator: ", ")
    }
}
