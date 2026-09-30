import Foundation

/// One "Saturday lunch 61 / 120" row on Home.
struct ContextProgress: Hashable, Sendable, Identifiable {
    var context: ScanContext
    var count: Int
    var total: Int
    /// The context's time window contains now.
    var active: Bool

    var id: String { context.id }
    var fraction: Double { total <= 0 ? 0 : min(max(Double(count) / Double(total), 0), 1) }
}

/// Arrivals card numbers from the server-computed travel counts, plus who's landing next.
struct ArrivalsSummary: Hashable, Sendable {
    var awaitingPickup: Int
    var collected: Int
    var checkedIn: Int
    var next: [TravelEntry]
}

/// What a role without participant access sees instead of roster stats.
struct ScanFeedSummary: Hashable, Sendable {
    /// Scans made today (event timezone) among the latest feed page.
    var today: Int
    /// True when every scan in the (capped) feed is from today, so the real number may be higher.
    var capped: Bool
    var uniquePeopleToday: Int
    var recent: [Scan]
}

enum DashboardLogic {
    /// The API returns at most this many scans without `since`.
    static let scanFeedCap = 100

    /// "Sat 3 – Sun 4 Oct · Sydney"
    static func subtitle(_ event: Event) -> String? {
        let parts = [Time.range(event.startsAt, event.endsAt, tz: event.timezone), event.locationCity?.nonBlank].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// Checked in among expected participants, so "84 / 120" and "36 not here yet" always add up.
    static func checkedInConfirmed(_ stats: EventStats) -> Int { max(stats.expected - stats.notArrived, 0) }

    /// Registrations still onboarding (invited / in progress / awaiting guardian).
    static func notComplete(_ stats: EventStats) -> Int { max(stats.registered - stats.confirmed, 0) }

    /// Unique participants with a safety flag worth knowing about before they arrive.
    static func needsAttention(_ participants: [Participant]) -> Int {
        participants.count(where: { $0.isActive && ($0.hasAnaphylaxisRisk || $0.highSupportFlag) })
    }

    /// Progress per scan context in the organizer's order; the context whose window contains now is marked active.
    static func contextProgress(_ contexts: [ScanContext], stats: EventStats, now: Date = Date()) -> [ContextProgress] {
        contexts.enumerated()
            .sorted { ($0.element.position, $0.offset) < ($1.element.position, $1.offset) }
            .map { _, c in
                ContextProgress(context: c, count: stats.perContext[c.id] ?? 0, total: stats.expected, active: EventLogic.isLive(c, now: now))
            }
    }

    /// Most recent check-ins first.
    static func recentCheckIns(_ participants: [Participant], limit: Int = 5) -> [Participant] {
        participants
            .filter(\.isActive)
            .compactMap { p in Time.parse(p.checkedInAt).map { (p, $0) } }
            .sorted { $0.1 > $1.1 }
            .prefix(limit)
            .map(\.0)
    }

    /// Next inbound journeys still needing a pickup, soonest first. Arrivals up to 3 h late still count
    /// (they may be waiting at the airport); unscheduled ones are left out.
    static func arrivals(_ calendar: TravelCalendar?, now: Date = Date(), limit: Int = 3) -> ArrivalsSummary? {
        guard let calendar else { return nil }
        let cutoff = now.addingTimeInterval(-3 * 3600)
        let next = calendar.entries
            .filter { $0.direction == "inbound" && $0.pickupState == "awaiting_pickup" }
            .compactMap { e in Time.parse(e.primaryTimeAt).map { (e, $0) } }
            .filter { $0.1 > cutoff }
            .sorted { $0.1 < $1.1 }
            .prefix(limit)
            .map(\.0)
        let c = calendar.counts
        return ArrivalsSummary(awaitingPickup: c.awaitingPickup, collected: c.collected, checkedIn: c.checkedIn, next: next)
    }

    static func scanFeed(_ scans: [Scan], tz: String?, now: Date = Date(), recentLimit: Int = 6) -> ScanFeedSummary {
        let zone = Time.zone(tz)
        let today = CalendarDay(now, in: zone)
        let sorted = scans.compactMap { s in Time.parse(s.scannedAt).map { (s, $0) } }.sorted { $0.1 > $1.1 }
        let todays = sorted.filter { CalendarDay($0.1, in: zone) == today }
        return ScanFeedSummary(
            today: todays.count,
            capped: scans.count >= scanFeedCap && todays.count == sorted.count,
            uniquePeopleToday: Set(todays.compactMap { $0.0.participantEventId ?? $0.0.participantId }).count,
            recent: sorted.prefix(recentLimit).map(\.0)
        )
    }

    /// "Starts in 20 min", "Starts in 5 h", "Starts tomorrow", "Starts in 3 days" (calendar days in the event's zone).
    static func countdown(_ startsAt: String?, tz: String?, now: Date = Date()) -> String? {
        guard let start = Time.parse(startsAt), start > now else { return nil }
        let zone = Time.zone(tz)
        let days = CalendarDay(now, in: zone).days(until: CalendarDay(start, in: zone))
        let seconds = start.timeIntervalSince(now)
        switch days {
        case 0 where seconds < 3600: return "Starts in \(max(Int(seconds / 60), 1)) min"
        case 0: return "Starts in \(Int(seconds / 3600)) h"
        case 1: return "Starts tomorrow"
        default: return "Starts in \(days) days"
        }
    }

    /// "Ended today", "Ended yesterday", "Ended 4 days ago".
    static func ended(_ endsAt: String?, tz: String?, now: Date = Date()) -> String? {
        guard let end = Time.parse(endsAt), end <= now else { return nil }
        let zone = Time.zone(tz)
        switch CalendarDay(end, in: zone).days(until: CalendarDay(now, in: zone)) {
        case 0: return "Ended today"
        case 1: return "Ended yesterday"
        case let days: return "Ended \(days) days ago"
        }
    }

    /// "Updated just now" / "Updated 5 min ago".
    static func updated(_ at: Date?, now: Date = Date()) -> String? {
        at.map { "Updated \(Time.ago($0, now: now))" }
    }
}
