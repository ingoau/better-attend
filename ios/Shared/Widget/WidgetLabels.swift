import Foundation

/// Kinds shared by the widget extension (configurations) and the app (reloads, previews).
enum WidgetKind {
    static let checkIn = "au.ingo.betterattend.CheckIn"
    static let arrivals = "au.ingo.betterattend.Arrivals"
    static let quickScan = "au.ingo.betterattend.QuickScan"
    static let ticket = "au.ingo.betterattend.Ticket"
    static let scanControl = "au.ingo.betterattend.ScanControl"
}

/// Text the widgets show, kept pure so it can be unit tested (ported from the Android widgets).
enum WidgetLabels {

    /// "Updated just now", "Updated 10 min ago", or "Not synced yet".
    static func updated(_ iso: String?, now: Date = Date()) -> String {
        guard let ago = Time.ago(iso, now: now) else { return "Not synced yet" }
        return "Updated \(ago)"
    }

    /// Compact: "Next 11:40 AM · Sam". Full: "Next: Sam (minor) · 11:40 AM · QF401".
    static func nextArrival(_ t: TravelWidgetData, tz: String?, compact: Bool) -> String {
        guard let at = Time.time(t.nextArrivalAt, tz: tz) else { return "No more arrivals scheduled" }
        let who = t.nextArrivalName ?? "Someone"
        if compact { return "Next \(at) · \(who)" }
        let minor = t.nextArrivalMinor ? " (minor)" : ""
        return (["Next: \(who)\(minor) · \(at)"] + [t.nextArrivalReference].compactMap { $0 }).joined(separator: " · ")
    }

    /// The line under the big check-in number.
    static func checkInStatus(_ org: OrganizerWidgetData, compact: Bool) -> String {
        if org.nobodyYet { return compact ? "expected" : "No one checked in yet" }
        if org.notArrived == 0 { return "Everyone's here" }
        return "\(org.notArrived) not here yet"
    }

    /// Spoken summary of the check-in count.
    static func checkInAccessibility(_ org: OrganizerWidgetData) -> String {
        org.nobodyYet ? "\(org.expected) expected, no one checked in yet" : "\(org.checkedIn) of \(org.expected) checked in"
    }

    /// Short relative time for arrivals: "now", "in 25 min", "in 2 h", "10 min ago".
    static func relative(_ date: Date, now: Date = Date()) -> String {
        let minutes = Int((date.timeIntervalSince(now) / 60).rounded())
        switch minutes {
        case -1...1: return "now"
        case 2..<60: return "in \(minutes) min"
        case 60...: return "in \(minutes / 60) h"
        case -59 ..< -1: return "\(-minutes) min ago"
        default: return "\(-minutes / 60) h ago"
        }
    }

    /// The label a ticket countdown chip shows (same wording as the tickets list).
    static func ticketCountdown(_ t: TicketWidgetData, now: Date = Date()) -> String {
        TicketLogic.relativeLabel(start: t.startsAt, end: t.endsAt, tz: t.timezone, now: now)
    }

    /// Empty-state copy for the ticket widget: participants are told their next ticket will show up;
    /// organizers without a participant role learn what the widget is for.
    static func noTicket(isParticipant: Bool) -> (title: String, body: String) {
        isParticipant
            ? ("No upcoming events", "Your next ticket will show up here")
            : ("No tickets", "Register for a Hack Club event to get a pass")
    }

    /// "A1B2 C3D4": short codes read more easily in two groups.
    static func grouped(_ code: String) -> String {
        guard code.count == 8 else { return code }
        return "\(code.prefix(4)) \(code.suffix(4))"
    }
}

/// When widget timelines should re-render, so time-dependent labels stay right without spending
/// the reload budget (the app reloads timelines itself whenever the snapshot changes).
enum WidgetSchedule {
    /// Organizer widgets: re-render every `step` until `horizon` ("Updated 5 min ago", "in 10 min"),
    /// then reload to pick up anything the app wrote meanwhile.
    static let step: TimeInterval = 5 * 60
    static let horizon: TimeInterval = 30 * 60

    static func stepDates(now: Date, step: TimeInterval = step, horizon: TimeInterval = horizon) -> [Date] {
        stride(from: 0, to: horizon, by: step).map { now.addingTimeInterval($0) }
    }

    /// Moments the ticket widget's label changes: each local midnight ("in 3 days" → "in 2 days"),
    /// an hour before doors (the countdown starts), doors, and the end. Sorted, after `now`,
    /// within `days`.
    static func ticketBoundaries(_ t: TicketWidgetData, now: Date, days: Int = 7) -> [Date] {
        let zone = Time.zone(t.timezone)
        let limit = now.addingTimeInterval(TimeInterval(days) * 86_400)
        let today = CalendarDay(now, in: zone)
        var dates = (1...days).map { today.adding(days: $0).start(in: zone) }
        if let start = Time.parse(t.startsAt) {
            let end = Time.parse(t.endsAt) ?? start.addingTimeInterval(86_400)
            dates += [start.addingTimeInterval(-3600), start, end]
        }
        return Array(Set(dates.filter { $0 > now && $0 <= limit })).sorted()
    }
}
