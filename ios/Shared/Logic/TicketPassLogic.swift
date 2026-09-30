import Foundation

/// Pure helpers for the My Tickets screens (list cards and the pass). Complements `TicketLogic`,
/// which is shared with the widgets.
enum TicketPassLogic {

    // MARK: List

    /// Large-title subtitle: "2 upcoming events" / "No upcoming events"; nil while there's nothing to count.
    static func subtitle(current: Int, total: Int) -> String? {
        guard total > 0 else { return nil }
        switch current {
        case 0: return "No upcoming events"
        case 1: return "1 upcoming event"
        default: return "\(current) upcoming events"
        }
    }

    /// Tab switches shouldn't hammer the API: refresh automatically at most once a minute.
    static func refreshDue(last: Date?, now: Date = Date(), interval: TimeInterval = 60) -> Bool {
        guard let last else { return true }
        return now.timeIntervalSince(last) >= interval
    }

    /// The calendar block on a ticket card: ("OCT", "3") in the event's own timezone, or ("TBA", "–").
    static func calendarBlock(_ event: TicketEvent, locale: Locale = .current) -> (month: String, day: String) {
        guard let start = Time.parse(event.startsAt) else { return ("TBA", "–") }
        var cal = Time.calendar(Time.zone(event.timezone))
        cal.locale = locale
        let c = cal.dateComponents([.month, .day], from: start)
        guard let m = c.month, let d = c.day else { return ("TBA", "–") }
        let month = cal.shortMonthSymbols[(m - 1) % 12].uppercased(with: locale).replacingOccurrences(of: ".", with: "")
        return (month, String(d))
    }

    /// VoiceOver reads a code character by character: "A 1 B 2 C 3".
    static func spokenCode(_ code: String) -> String {
        code.map(String.init).joined(separator: " ")
    }

    // MARK: Countdown card

    /// "Times shown in Sydney time" when the event's zone differs from the device's at the event start; else nil.
    static func timeZoneNote(_ event: TicketEvent, device: TimeZone = .current) -> String? {
        guard let tz = event.timezone, let zone = TimeZone(identifier: tz) else { return nil }
        let at = Time.parse(event.startsAt) ?? Date()
        guard zone.secondsFromGMT(for: at) != device.secondsFromGMT(for: at) else { return nil }
        let place = event.locationCity?.nonBlank
            ?? zone.identifier.split(separator: "/").last.map { $0.replacingOccurrences(of: "_", with: " ") }
            ?? zone.identifier
        return "Times shown in \(place) time"
    }

    // MARK: Travel

    /// "MEL → SYD" and "Departs Sat 3 Oct, 7:00 AM · arrives 8:30 AM" for one flight leg.
    static func legLines(_ leg: TicketTravelLeg, tz: String?, now: Date = Date()) -> (route: String, times: String?) {
        let route = "\(leg.departureAirport?.nonBlank ?? "?") → \(leg.arrivalAirport?.nonBlank ?? "?")"
        return (route, timesLine(leg.departureTime, leg.arrivalTime, tz: tz, now: now))
    }

    /// Title, route and times for travel without legs: ("Qantas QF401", "Melbourne → Sydney", "Departs …").
    static func travelLines(_ t: TicketTravel, tz: String?, now: Date = Date()) -> (title: String, route: String?, times: String?) {
        let title = [t.carrier?.nonBlank, t.flightNumber?.nonBlank].compactMap { $0 }.joined(separator: " ").nonBlank
            ?? t.mode?.nonBlank?.capitalized
            ?? "Travel"
        let route = [t.departureCity?.nonBlank, t.arrivalCity?.nonBlank].compactMap { $0 }.joined(separator: " → ").nonBlank
        return (title, route, timesLine(t.departureTime, t.arrivalTime, tz: tz, now: now))
    }

    private static func timesLine(_ dep: String?, _ arr: String?, tz: String?, now: Date) -> String? {
        [Time.dayTime(dep, tz: tz, now: now).map { "Departs \($0)" }, Time.time(arr, tz: tz).map { "arrives \($0)" }]
            .compactMap { $0 }.joined(separator: " · ").nonBlank
    }

    /// SF Symbol for a travel mode.
    static func modeSymbol(_ mode: String?) -> String {
        switch mode?.lowercased() {
        case "train": "tram.fill"
        case "car": "car.fill"
        case "bus": "bus.fill"
        default: "airplane"
        }
    }

    // MARK: Messages

    /// "Orpheus · 40 min ago"
    static func messageMeta(_ m: TicketMessage, now: Date = Date()) -> String {
        [m.senderName?.nonBlank ?? "Event team", Time.ago(m.deliveredAt, now: now)].compactMap { $0 }.joined(separator: " · ")
    }

    /// Organiser messages arrive as HTML. This turns them into Markdown for `AttributedString(markdown:)`
    /// (bold, italics and links survive; everything else becomes plain text). No WebView, no remote content.
    static func messageMarkdown(_ html: String?) -> String {
        convert(html, markdown: true)
    }

    /// The same, as plain text (previews, accessibility, copying).
    static func messageText(_ html: String?) -> String {
        convert(html, markdown: false)
    }

    private static func convert(_ html: String?, markdown: Bool) -> String {
        guard let html, !html.isBlank else { return "" }
        var out = ""
        var links: [String?] = []
        var i = html.startIndex
        // Whitespace in HTML collapses; explicit breaks come from tags.
        var pendingSpace = false
        func emit(_ s: String) {
            if pendingSpace, !out.isEmpty, !out.hasSuffix("\n"), !out.hasSuffix(" ") { out += " " }
            pendingSpace = false
            out += s
        }
        func newline(_ n: Int) {
            pendingSpace = false
            while out.hasSuffix(" ") { out.removeLast() }
            guard !out.isEmpty else { return }
            var have = 0
            for c in out.reversed() { if c == "\n" { have += 1 } else { break } }
            if have < n { out += String(repeating: "\n", count: n - have) }
        }
        while i < html.endIndex {
            let c = html[i]
            if c == "<", let close = html[i...].firstIndex(of: ">") {
                let raw = html[html.index(after: i)..<close]
                i = html.index(after: close)
                let isEnd = raw.hasPrefix("/")
                let body = raw.drop(while: { $0 == "/" })
                let name = body.prefix(while: { $0.isLetter || $0.isNumber }).lowercased()
                switch name {
                case "br": newline(1)
                case "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "table", "tr":
                    newline(2)
                case "li":
                    if isEnd { newline(1) } else { newline(1); emit("• ") }
                case "b", "strong", "i", "em":
                    guard markdown else { break }
                    let mark = name == "i" || name == "em" ? "*" : "**"
                    // Closing marks hug the text ("**bold** text", never "**bold **text").
                    if isEnd { out += mark } else { emit(mark) }
                case "a":
                    guard markdown else { break }
                    if isEnd {
                        if let href = links.popLast() ?? nil {
                            let safe = href.replacingOccurrences(of: " ", with: "%20")
                                .replacingOccurrences(of: "(", with: "%28").replacingOccurrences(of: ")", with: "%29")
                            out += "](\(safe))"
                        }
                    } else {
                        let href = attribute("href", in: String(body)).flatMap { h -> String? in
                            let lower = h.lowercased()
                            return lower.hasPrefix("https://") || lower.hasPrefix("http://") || lower.hasPrefix("mailto:") ? h : nil
                        }
                        links.append(href)
                        if href != nil { emit("[") }
                    }
                case "script", "style":
                    // Skip the element's contents entirely.
                    if !isEnd, let end = html.range(of: "</\(name)", options: .caseInsensitive, range: i..<html.endIndex) {
                        i = html[end.upperBound...].firstIndex(of: ">").map { html.index(after: $0) } ?? html.endIndex
                    }
                default: break
                }
                continue
            }
            if c == "&", let semi = html[i...].prefix(10).firstIndex(of: ";") {
                let entity = String(html[html.index(after: i)..<semi])
                if let decoded = decodeEntity(entity) {
                    emit(markdown ? escape(decoded) : decoded)
                    i = html.index(after: semi)
                    continue
                }
            }
            if c.isWhitespace {
                pendingSpace = true
            } else {
                emit(markdown ? escape(String(c)) : String(c))
            }
            i = html.index(after: i)
        }
        // Tidy: no trailing spaces on lines, at most one blank line in a row.
        let lines = out.split(separator: "\n", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        var tidy: [String] = []
        for line in lines where !(line.isEmpty && (tidy.last?.isEmpty ?? true)) { tidy.append(line) }
        while tidy.last?.isEmpty == true { tidy.removeLast() }
        return tidy.joined(separator: "\n")
    }

    private static func attribute(_ name: String, in tag: String) -> String? {
        guard let r = tag.range(of: "\(name)=", options: .caseInsensitive) else { return nil }
        var rest = tag[r.upperBound...]
        guard let q = rest.first else { return nil }
        if q == "\"" || q == "'" {
            rest = rest.dropFirst()
            return rest.prefix(while: { $0 != q }).nonBlankString
        }
        return rest.prefix(while: { !$0.isWhitespace }).nonBlankString
    }

    private static func decodeEntity(_ e: String) -> String? {
        switch e.lowercased() {
        case "amp": return "&"
        case "lt": return "<"
        case "gt": return ">"
        case "quot": return "\""
        case "apos", "#39": return "'"
        case "nbsp": return "\u{00A0}"
        case "ndash": return "–"
        case "mdash": return "—"
        case "hellip": return "…"
        case "rsquo": return "’"
        case "lsquo": return "‘"
        case "rdquo": return "”"
        case "ldquo": return "“"
        default:
            guard e.hasPrefix("#") else { return nil }
            let num = e.dropFirst()
            let value = num.lowercased().hasPrefix("x") ? UInt32(num.dropFirst(), radix: 16) : UInt32(num)
            return value.flatMap(Unicode.Scalar.init).map { String(Character($0)) }
        }
    }

    /// Backslash-escapes characters Markdown would otherwise interpret.
    private static func escape(_ s: String) -> String {
        var r = ""
        for ch in s {
            if "\\`*_[]#<>".contains(ch) { r.append("\\") }
            r.append(ch)
        }
        return r
    }

    // MARK: Venue

    /// A web link that opens Apple Maps directions to the venue, for when MapKit can't resolve it:
    /// coordinates when known, otherwise the address as typed. nil when there's no venue at all.
    static func appleMapsURL(_ e: TicketEvent) -> URL? {
        var c = URLComponents(string: "https://maps.apple.com/")!
        if let lat = e.locationLatitude, let lon = e.locationLongitude {
            c.queryItems = [URLQueryItem(name: "daddr", value: "\(lat),\(lon)"), URLQueryItem(name: "q", value: e.name)]
        } else if let q = TicketLogic.venueQuery(e) {
            c.queryItems = [URLQueryItem(name: "daddr", value: q)]
        } else {
            return nil
        }
        return c.url
    }
}

private extension Substring {
    var nonBlankString: String? { String(self).nonBlank }
}
