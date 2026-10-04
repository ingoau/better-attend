import CryptoKit
import Foundation

// Pure scanner / kiosk presentation logic (a port of Android's `ScanModels.kt` and the kiosk
// helpers). The mapping from a repository `ScanOutcome` to a card lives app-side in
// `ScanCard+Outcome.swift`, because `ScanOutcome` isn't part of the shared module.

/// What the result card is showing. `checking`: sent, nothing known yet. `confirming`: the cached
/// roster says they're fine and we're waiting for the server to agree (a muted tick, not a success).
enum ResultKind: String, Hashable, Sendable, CaseIterable {
    case checking, confirming, scanned, alreadyScanned, savedOffline, rejected, undone

    /// The sound + haptic for a final outcome (nil while checking, and for staff undo).
    var feedback: FeedbackKind? {
        switch self {
        case .checking, .confirming, .undone: nil
        case .scanned: .success
        case .alreadyScanned: .warning
        case .savedOffline: .info
        case .rejected: .reject
        }
    }

    var tone: Tone {
        switch self {
        case .scanned: .success
        case .alreadyScanned: .warning
        case .checking, .savedOffline: .info
        case .rejected: .danger
        case .undone, .confirming: .neutral
        }
    }

    var systemImage: String {
        switch self {
        case .checking: "hourglass"
        case .scanned, .confirming: "checkmark"
        case .alreadyScanned: "clock.arrow.circlepath"
        case .savedOffline: "icloud.and.arrow.up"
        case .rejected: "xmark"
        case .undone: "arrow.uturn.backward"
        }
    }

    /// True once the server (or the offline queue) has answered.
    var isFinal: Bool { self != .checking && self != .confirming }
}

/// One scan attempt as shown on the result card. The next scan replaces it.
struct ScanCard: Hashable, Sendable, Identifiable {
    var key: String
    var kind: ResultKind
    var title: String
    var message: String?
    var participant: Participant?
    var contextName: String?
    /// Server scan context id, used for undo.
    var contextId: String?
    var retryable = false
    var canUndo = false
    /// Undo in flight.
    var busy = false
    /// Same-code gate key, released when the card is dismissed.
    var gateKey: String?
    var input: ScanInput?
    /// Offline only: how old the roster it was checked against is, e.g. "Roster from 14 min ago".
    var rosterNote: String?
    /// The roster is missing or over an hour old: `rosterNote` is shown as a warning.
    var rosterStale = false

    var id: String { key }

    /// Spoken by VoiceOver when the outcome arrives.
    var accessibilityText: String {
        [title, participant?.name, contextName, message, rosterNote].compactMap { $0?.nonBlank }.joined(separator: ". ")
    }

    // MARK: Cards that never reach the server

    static func notAttendCode(key: String, gateKey: String?) -> ScanCard {
        ScanCard(key: key, kind: .rejected, title: notAttendCodeTitle, message: "This QR code isn't an Attend ticket or badge.", gateKey: gateKey)
    }

    static func notAttendBadge(key: String, message: String) -> ScanCard {
        ScanCard(key: key, kind: .rejected, title: notAttendBadgeTitle, message: message)
    }

    static func stillLoading(key: String, input: ScanInput) -> ScanCard {
        ScanCard(key: key, kind: .rejected, title: stillLoadingTitle, message: "Checkpoints are still loading. Try again in a moment.",
                 retryable: true, input: input)
    }

    static let notAttendCodeTitle = "Not an Attend code"
    static let notAttendBadgeTitle = "Not an Attend badge"
    static let stillLoadingTitle = "Still loading"
}

/// The camera's availability, from permission and hardware.
enum CameraAccess: Hashable, Sendable {
    /// No camera on this device (e.g. the simulator).
    case unavailable
    case notDetermined
    case denied
    case granted
}

enum ScanLogic {
    /// "9:41 AM" today (in the event's zone), else "Fri 9:41 AM".
    static func firstScanLabel(_ iso: String, tz: String?, now: Date = Date()) -> String {
        guard let date = Time.parse(iso) else { return iso }
        let zone = Time.zone(tz)
        let time = Time.time(date, zone: zone)
        if CalendarDay(date, in: zone) == CalendarDay(now, in: zone) { return time }
        return [Time.weekday(iso, tz: tz), time].compactMap { $0 }.joined(separator: " ")
    }

    /// The scanner's status chip: what the camera is doing right now.
    static func status(inFlight: Int, ready: Bool, camera: CameraAccess) -> (label: String, tone: Tone) {
        if inFlight > 0 { return ("Checking…", .info) }
        if !ready { return ("Loading…", .neutral) }
        if camera != .granted { return ("Camera off", .neutral) }
        return ("Ready to scan", .success)
    }

    /// "Scan" or "Check In" for the find-person sheet, depending on the checkpoint.
    static func actionLabel(checksIn: Bool) -> String { checksIn ? "Check In" : "Scan" }

    /// Status line for a person in the find-person sheet.
    static func personStatus(_ p: Participant, selectedContextId: String?, tz: String?, now: Date = Date()) -> (text: String, tone: Tone) {
        if !p.isActive { return ((p.status ?? "inactive").replacingOccurrences(of: "_", with: " ").capitalizedFirst, .danger) }
        if let here = p.scansByContext.first(where: { $0.scanContextId == selectedContextId }) {
            let when = here.firstScannedAt.map { firstScanLabel($0, tz: tz, now: now) }
            return (["Scanned here", when].compactMap { $0 }.joined(separator: " "), .success)
        }
        if p.isCheckedIn { return ("Checked in", .success) }
        return ("Not checked in", .neutral)
    }

    /// Title for the card's person row: the full name when it extends the display name.
    static func headline(_ p: Participant) -> (title: String, subtitle: String?) {
        let full = p.fullName?.nonBlank
        let title = full.map { $0.hasPrefix(p.name) ? $0 : p.name } ?? p.name
        return (title, full.flatMap { $0 == title ? nil : $0 })
    }
}

extension ScanContext {
    var systemImage: String {
        if isTravelPickup || isAirport { return "airplane.arrival" }
        if checksIn { return "person.crop.circle.badge.checkmark" }
        return "mappin.and.ellipse"
    }

    /// "Checks people in" / "Airport pickup", for pickers.
    var purpose: String? {
        if checksIn { return "Checks people in" }
        if isTravelPickup { return "Airport pickup" }
        return nil
    }

    func isLive(now: Date = Date()) -> Bool { EventLogic.isLive(self, now: now) }

    /// A compact time window like "12:00 – 1:30 PM" today, or "Sun 12:00 – 1:30 PM" on other days;
    /// nil when the context has no window.
    func windowLabel(tz: String?, now: Date = Date()) -> String? {
        guard let s = Time.parse(startsAt) else { return nil }
        let zone = Time.zone(tz)
        let day = CalendarDay(s, in: zone) == CalendarDay(now, in: zone) ? "" : Time.weekday(startsAt, tz: tz).map { "\($0) " } ?? ""
        let start = Time.time(s, zone: zone)
        if let e = Time.parse(endsAt) { return day + "\(start) – \(Time.time(e, zone: zone))" }
        return day + "from \(start)"
    }

    /// "Now" while the window is open, else the window label.
    func whenLabel(tz: String?, now: Date = Date()) -> String? {
        isLive(now: now) ? "Now" : windowLabel(tz: tz, now: now)
    }
}

// MARK: - Kiosk

enum KioskLogic {
    /// How long a final result stays up before it hides itself (privacy).
    static let resultSeconds: Double = 3
    static let pinLength = 4
    static let maxPinTries = 3
    static let lockoutSeconds: TimeInterval = 30

    struct Message: Hashable, Sendable {
        var title: String
        var body: String
    }

    /// First name only: kiosks are public, so we show as little as possible.
    static func firstName(_ p: Participant) -> String {
        let name = (p.displayName?.nonBlank ?? p.fullName ?? "").trimmingCharacters(in: .whitespaces)
        let first = name.split(separator: " ").first.map(String.init) ?? ""
        return first.isEmpty ? "there" : first
    }

    /// The attendee-facing wording for a card.
    static func message(for card: ScanCard) -> Message {
        let first = card.participant.map(firstName)
        switch card.kind {
        case .checking, .confirming:
            return Message(title: first.map { "Hi \($0)!" } ?? "One moment…", body: "Checking your ticket. Hold steady.")
        case .scanned, .savedOffline:
            return Message(title: first.map { "Welcome, \($0)!" } ?? "Welcome!", body: "You're all set. Enjoy the event!")
        case .alreadyScanned:
            return Message(title: first.map { "You're already in, \($0)" } ?? "Already scanned", body: "No need to scan again. Have fun!")
        case .rejected:
            if card.title == ScanCard.stillLoadingTitle {
                return Message(title: "Just a moment", body: "The kiosk is still getting ready. Try again in a few seconds.")
            }
            if card.title == ScanCard.notAttendCodeTitle || card.title == ScanCard.notAttendBadgeTitle {
                return Message(title: "That's not an Attend ticket", body: "Scan the QR code on your Attend ticket, or ask a staff member.")
            }
            // Withdrawn, missing consent, wrong event…: never say why on a public screen.
            if RejectReason.allCases.contains(where: { $0.title == card.title && $0 != .notRegistered }) {
                return Message(title: "Please see a staff member", body: "They'll help you get checked in.")
            }
            return Message(title: "We couldn't find your ticket", body: "Please see a staff member and they'll sort it out.")
        case .undone:
            return Message(title: "Ready", body: "Scan your ticket")
        }
    }

    /// Salted SHA-256 of the exit PIN: the PIN itself is never kept.
    static func hashPin(_ pin: String) -> String {
        SHA256.hash(data: Data("attend-kiosk:\(pin)".utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Keeps only digits, capped at the PIN length (paste-safe input filter).
    static func sanitizePin(_ text: String) -> String {
        String(text.filter(\.isASCII).filter(\.isNumber).prefix(pinLength))
    }
}

/// Choosing the exit PIN: enter it, then enter it again to confirm.
struct KioskPinSetup: Hashable, Sendable {
    private(set) var first: String?
    private(set) var error: String?

    var confirming: Bool { first != nil }
    var prompt: String { error ?? (confirming ? "Enter the PIN again to confirm" : "Choose a 4-digit exit PIN") }

    /// Feed a complete 4-digit entry. Returns the PIN's hash once both entries match.
    mutating func submit(_ pin: String) -> String? {
        guard pin.count == KioskLogic.pinLength else { return nil }
        guard let first else {
            self.first = pin
            error = nil
            return nil
        }
        if first == pin { return KioskLogic.hashPin(pin) }
        self.first = nil
        error = "PINs don't match. Try again."
        return nil
    }

    /// Go back to choosing (e.g. the user cleared the confirm field and wants to start over).
    mutating func restart() {
        first = nil
        error = nil
    }
}

/// Staff PIN check to leave the kiosk, with a lockout after too many wrong tries.
struct KioskPinLock: Hashable, Sendable {
    let hash: String
    private(set) var wrongTries = 0
    private(set) var lockedUntil: Date?

    init(hash: String) { self.hash = hash }

    enum Attempt: Hashable, Sendable { case unlocked, wrong(triesLeft: Int), lockedOut }

    func isLocked(now: Date = Date()) -> Bool { lockedUntil.map { now < $0 } ?? false }

    func secondsLeft(now: Date = Date()) -> Int {
        guard let lockedUntil, now < lockedUntil else { return 0 }
        return Int(lockedUntil.timeIntervalSince(now).rounded(.up))
    }

    mutating func attempt(_ pin: String, now: Date = Date()) -> Attempt {
        if let until = lockedUntil {
            if now < until { return .lockedOut }
            lockedUntil = nil
            wrongTries = 0
        }
        if KioskLogic.hashPin(pin) == hash {
            wrongTries = 0
            return .unlocked
        }
        wrongTries += 1
        if wrongTries >= KioskLogic.maxPinTries {
            lockedUntil = now.addingTimeInterval(KioskLogic.lockoutSeconds)
            return .lockedOut
        }
        return .wrong(triesLeft: KioskLogic.maxPinTries - wrongTries)
    }

    /// The prompt under the PIN dots.
    func prompt(after last: Attempt?, now: Date = Date()) -> String {
        if isLocked(now: now) { return "Too many wrong tries. Try again in \(secondsLeft(now: now)) s." }
        if case .wrong(let left)? = last { return "Wrong PIN. \(left) \(left == 1 ? "try" : "tries") left." }
        return "Staff: enter the 4-digit PIN."
    }
}
