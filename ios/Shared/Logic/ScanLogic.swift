import Foundation

/// What was scanned. Exactly one of `participantId` / `badgeToken` is normally set.
struct ScanInput: Codable, Hashable, Sendable {
    /// Raw QR text, participant UUID, or participant_event UUID.
    var participantId: String?
    var badgeToken: String?
    /// "qr" | "nfc" | "manual"
    var source: String = "qr"
}

enum ScanCode {
    private static var uuid: Regex<Substring> { /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/ }

    /// Turns whatever a camera/NFC tag produced into a scan input, or nil if it isn't ours.
    /// Accepts attend://checkin/<id>, attend:P:<id>, bare UUIDs, and Attend URLs containing a UUID.
    static func parse(_ raw: String, source: String = "qr") -> ScanInput? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let lower = text.lowercased()
        if lower.hasPrefix("attend://checkin/") {
            return ScanInput(participantId: text, source: source)
        }
        if lower.hasPrefix("attend:p:") {
            let id = String(text.dropFirst(9))
            return id.isBlank ? nil : ScanInput(participantId: id, source: source)
        }
        if text.wholeMatch(of: uuid) != nil {
            return ScanInput(participantId: text, source: source)
        }
        if lower.hasPrefix("https://attend.hackclub.com") || lower.hasPrefix("attend://") {
            return text.firstMatch(of: uuid).map { ScanInput(participantId: String($0.output), source: source) }
        }
        return nil
    }
}

/// Stops a continuously-running scanner from submitting the same code on every frame.
///
/// A value is accepted the first time it's seen, then ignored until it has been *absent* (not
/// offered) for at least `window`. Every sighting refreshes the timer, so a ticket held in front
/// of the camera for a minute is still one scan. Values are tracked independently, so two tickets
/// in frame at once are each accepted once instead of flip-flopping.
///
/// Not thread-safe: use from one actor (the main actor in the app).
final class SameCodeGate {
    static let defaultWindow: TimeInterval = 2.5

    var window: TimeInterval = SameCodeGate.defaultWindow
    private var lastSeen: [String: Date] = [:]

    init(window: TimeInterval = SameCodeGate.defaultWindow) { self.window = window }

    /// Records a sighting of `value` and returns true if it should be acted on.
    func offer(_ value: String, now: Date = Date()) -> Bool {
        prune(now)
        let previous = lastSeen[value]
        lastSeen[value] = now
        guard let previous else { return true }
        return now.timeIntervalSince(previous) >= window
    }

    /// Forget `value` (e.g. its result was dismissed) so the next sighting is accepted immediately.
    func release(_ value: String) { lastSeen[value] = nil }

    /// Forget everything (event or context changed).
    func reset() { lastSeen.removeAll() }

    private func prune(_ now: Date) {
        guard lastSeen.count >= 32 else { return }
        lastSeen = lastSeen.filter { now.timeIntervalSince($0.value) < window }
    }
}

/// Instant, offline search over the cached roster for the scanner's "Find person" sheet.
enum RosterSearch {
    /// Every whitespace-separated term must match the start of a word in the name/email, or the
    /// short code / id prefix. Name-prefix matches rank first. Withdrawn/rejected people sort last.
    static func filter(_ participants: [Participant], query: String, limit: Int = 50) -> [Participant] {
        let terms = query.lowercased().split(whereSeparator: \.isWhitespace).map(String.init)
        guard !terms.isEmpty else { return [] }
        return participants
            .compactMap { p in score(p, terms).map { (p, $0) } }
            .sorted { a, b in
                if a.0.isActive != b.0.isActive { return a.0.isActive }
                if a.1 != b.1 { return a.1 > b.1 }
                return a.0.name.lowercased() < b.0.name.lowercased()
            }
            .prefix(limit)
            .map(\.0)
    }

    private static func score(_ p: Participant, _ terms: [String]) -> Int? {
        let separators: Set<Character> = [" ", ".", "-", "_", "'"]
        let sources = [p.displayName, p.fullName, p.email.map { String($0.split(separator: "@").first ?? "") }].compactMap { $0 }
        let words = sources.flatMap { $0.lowercased().split(whereSeparator: { separators.contains($0) }).map(String.init) }
        let email = p.email?.lowercased() ?? ""
        let ids = [p.participantId.lowercased(), p.participantEventId.lowercased()]
        let name = p.name.lowercased()
        var total = 0
        for t in terms {
            if words.contains(where: { $0.hasPrefix(t) }) {
                total += name.hasPrefix(t) ? 3 : 2
            } else if t.count >= 3, email.contains(t) {
                total += 1
            } else if t.count >= 4, ids.contains(where: { $0.hasPrefix(t) }) {
                total += 1
            } else {
                return nil
            }
        }
        return total
    }

    /// Typed/pasted text that can be submitted directly (QR payload or UUID), as a manual scan.
    static func directInput(_ query: String) -> ScanInput? {
        ScanCode.parse(query, source: "manual").map { var i = $0; i.source = "manual"; return i }
    }

    /// An 8-character ticket short code (first block of the participant id). Resolved via search.
    static func looksLikeShortCode(_ query: String) -> Bool {
        query.trimmingCharacters(in: .whitespaces).wholeMatch(of: /[A-Za-z0-9]{8}/) != nil
    }
}
