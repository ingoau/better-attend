import Foundation


/// A calendar date without a time or zone ("2026-10-03"), like java.time.LocalDate.
struct CalendarDay: Hashable, Comparable, Sendable, CustomStringConvertible, Codable {
    var year: Int
    var month: Int
    var day: Int

    init(year: Int, month: Int, day: Int) {
        self.year = year; self.month = month; self.day = day
    }

    /// Parses "yyyy-MM-dd" (anything after the date, e.g. a time, is ignored).
    init?(iso: String?) {
        guard let iso else { return nil }
        let parts = iso.prefix(10).split(separator: "-")
        guard parts.count == 3, let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        self.init(year: y, month: m, day: d)
    }

    /// The date of `date` on the wall calendar of `zone`.
    init(_ date: Date, in zone: TimeZone) {
        let c = Time.calendar(zone).dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// Midnight at the start of this day in `zone`.
    func start(in zone: TimeZone) -> Date {
        Time.calendar(zone).date(from: DateComponents(year: year, month: month, day: day)) ?? .distantPast
    }

    func adding(days: Int) -> CalendarDay {
        let utc = TimeZone(identifier: "UTC")!
        let d = Time.calendar(utc).date(byAdding: .day, value: days, to: start(in: utc)) ?? start(in: utc)
        return CalendarDay(d, in: utc)
    }

    /// Whole calendar days from `self` to `other` (positive when `other` is later).
    func days(until other: CalendarDay) -> Int {
        let utc = TimeZone(identifier: "UTC")!
        return Time.calendar(utc).dateComponents([.day], from: start(in: utc), to: other.start(in: utc)).day ?? 0
    }

    static func < (a: CalendarDay, b: CalendarDay) -> Bool {
        (a.year, a.month, a.day) < (b.year, b.month, b.day)
    }

    var description: String { String(format: "%04d-%02d-%02d", year, month, day) }

    init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = CalendarDay(iso: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Bad date \(s)"))
        }
        self = d
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(description)
    }
}

/// Date/time helpers. Server timestamps are ISO-8601; event-local display uses the event's IANA zone.
enum Time {
    enum Phase: Sendable { case upcoming, live, past, unknown }

    // MARK: Parsing

    /// Parses ISO-8601 timestamps as Attend sends them: `2026-10-03T01:30:00Z`,
    /// `2026-10-03T01:30:00.123456Z`, `2026-10-04T12:00:00+10:00`. A missing offset means UTC.
    static func parse(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        let b = Array(iso.utf8)
        var i = 0
        func int(_ n: Int) -> Int? {
            guard i + n <= b.count else { return nil }
            var v = 0
            for k in i..<(i + n) {
                let c = b[k]
                guard c >= 48, c <= 57 else { return nil }
                v = v * 10 + Int(c - 48)
            }
            i += n
            return v
        }
        func expect(_ ch: UInt8) -> Bool {
            guard i < b.count, b[i] == ch else { return false }
            i += 1
            return true
        }
        guard let year = int(4), expect(45), let month = int(2), expect(45), let day = int(2) else { return nil }
        var hour = 0, minute = 0, second = 0
        var nanos = 0
        var offset = 0
        if i < b.count, b[i] == 84 || b[i] == 116 || b[i] == 32 { // T, t or space
            i += 1
            guard let h = int(2), expect(58), let m = int(2) else { return nil }
            hour = h; minute = m
            if expect(58) {
                guard let s = int(2) else { return nil }
                second = s
                if i < b.count, b[i] == 46 || b[i] == 44 { // fractional seconds
                    i += 1
                    var digits = 0
                    while i < b.count, b[i] >= 48, b[i] <= 57 {
                        if digits < 9 { nanos = nanos * 10 + Int(b[i] - 48); digits += 1 }
                        i += 1
                    }
                    while digits < 9 { nanos *= 10; digits += 1 }
                }
            }
            if i < b.count {
                if b[i] == 90 || b[i] == 122 { // Z
                    i += 1
                } else if b[i] == 43 || b[i] == 45 { // + or -
                    let sign = b[i] == 45 ? -1 : 1
                    i += 1
                    guard let oh = int(2) else { return nil }
                    _ = expect(58)
                    let om = int(2) ?? 0
                    offset = sign * (oh * 3600 + om * 60)
                } else {
                    return nil
                }
            }
        }
        guard i == b.count else { return nil }
        var c = DateComponents()
        c.year = year; c.month = month; c.day = day
        c.hour = hour; c.minute = minute; c.second = second
        guard let base = calendar(TimeZone(secondsFromGMT: 0)!).date(from: c) else { return nil }
        return base.addingTimeInterval(TimeInterval(-offset) + TimeInterval(nanos) / 1_000_000_000)
    }

    static func zone(_ tz: String?) -> TimeZone {
        tz.flatMap(TimeZone.init(identifier:)) ?? .current
    }

    static func calendar(_ zone: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        c.locale = Locale.current
        return c
    }

    /// Now as an ISO-8601 UTC timestamp with milliseconds.
    static func nowISO(_ now: Date = Date()) -> String { iso(now) }

    static func iso(_ date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true, timeZone: TimeZone(secondsFromGMT: 0)!))
    }

    // MARK: Formatting

    /// "9:41 AM" in the given zone.
    static func time(_ iso: String?, tz: String? = nil) -> String? {
        parse(iso).map { time($0, zone: zone(tz)) }
    }

    static func time(_ date: Date, zone: TimeZone) -> String {
        formatter(key: "time", zone: zone) { $0.timeStyle = .short; $0.dateStyle = .none }.string(from: date)
    }

    /// "Sat 4 Oct" (year added when not the current year).
    static func day(_ iso: String?, tz: String? = nil, now: Date = Date()) -> String? {
        guard let d = parse(iso) else { return nil }
        return day(d, zone: zone(tz), now: now)
    }

    static func day(_ date: Date, zone: TimeZone, now: Date = Date()) -> String {
        let sameYear = calendar(zone).component(.year, from: date) == calendar(zone).component(.year, from: now)
        let template = sameYear ? "EEEdMMM" : "EEEdMMMyyyy"
        return formatter(key: template, zone: zone) { $0.setLocalizedDateFormatFromTemplate(template) }.string(from: date)
    }

    /// "Sat" in the given zone.
    static func weekday(_ iso: String?, tz: String? = nil) -> String? {
        guard let d = parse(iso) else { return nil }
        let z = zone(tz)
        return formatter(key: "EEE", zone: z) { $0.setLocalizedDateFormatFromTemplate("EEE") }.string(from: d)
    }

    /// "Saturday 4 October".
    static func longDay(_ day: CalendarDay) -> String {
        let utc = TimeZone(identifier: "UTC")!
        return formatter(key: "EEEEdMMMM", zone: utc) { $0.setLocalizedDateFormatFromTemplate("EEEEdMMMM") }
            .string(from: day.start(in: utc))
    }

    /// "Sat 4 Oct, 9:41 AM".
    static func dayTime(_ iso: String?, tz: String? = nil, now: Date = Date()) -> String? {
        guard let d = parse(iso) else { return nil }
        let z = zone(tz)
        return "\(day(d, zone: z, now: now)), \(time(d, zone: z))"
    }

    /// "Sat 4 – Mon 6 Oct" style range for event cards; a single day when both ends match.
    static func range(_ startISO: String?, _ endISO: String?, tz: String?, now: Date = Date()) -> String? {
        guard let s = parse(startISO) else { return nil }
        let z = zone(tz)
        guard let e = parse(endISO), CalendarDay(s, in: z) != CalendarDay(e, in: z) else {
            return day(s, zone: z, now: now)
        }
        let f = DateIntervalFormatter()
        f.timeZone = z
        f.locale = .current
        let sameYear = calendar(z).component(.year, from: s) == calendar(z).component(.year, from: now)
        f.dateTemplate = sameYear ? "EEEdMMM" : "EEEdMMMyyyy"
        return f.string(from: s, to: e)
    }

    /// "just now", "5 min ago", "2 h ago", "3 d ago".
    static func ago(_ iso: String?, now: Date = Date()) -> String? {
        parse(iso).map { ago($0, now: now) }
    }

    static func ago(_ date: Date, now: Date = Date()) -> String {
        let s = now.timeIntervalSince(date)
        switch s {
        case ..<45: return "just now"
        case ..<3600: return "\(Int(s / 60)) min ago"
        case ..<86_400: return "\(Int(s / 3600)) h ago"
        default: return "\(Int(s / 86_400)) d ago"
        }
    }

    static func phase(_ startISO: String?, _ endISO: String?, now: Date = Date()) -> Phase {
        guard let s = parse(startISO) else { return .unknown }
        let e = parse(endISO) ?? s.addingTimeInterval(86_400)
        if now < s { return .upcoming }
        if now > e { return .past }
        return .live
    }

    // MARK: Formatter cache

    // DateFormatter is thread-safe for formatting; the lock only guards the dictionary.
    private static let lock = NSLock()
    nonisolated(unsafe) private static var formatters: [String: DateFormatter] = [:]

    private static func formatter(key: String, zone: TimeZone, configure: (DateFormatter) -> Void) -> DateFormatter {
        let k = "\(key)|\(zone.identifier)|\(Locale.current.identifier)"
        lock.lock()
        defer { lock.unlock() }
        if let f = formatters[k] { return f }
        let f = DateFormatter()
        f.locale = .current
        f.timeZone = zone
        configure(f)
        formatters[k] = f
        return f
    }
}
