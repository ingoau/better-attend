import Foundation

/// One number that can go on a share card, e.g. "Checked in 84 of 120".
struct ShareStat: Hashable, Sendable, Identifiable {
    var id: String
    var label: String
    var value: Int
    /// SF Symbol.
    var icon: String
    /// Shown as "of 120" with a progress bar, when the card's totals are on.
    var total: Int?
    var prefix = ""
    var suffix = ""

    var display: String { prefix + value.formatted() + suffix }
    var fraction: Double? {
        guard let total, total > 0 else { return nil }
        return min(max(Double(value) / Double(total), 0), 1)
    }
}

/// Every number Home shows, in Home's order, so a long-press on any of them can open the share sheet.
enum ShareStats {
    static let checkedIn = "checked_in"
    static let notHere = "not_here"
    static let lastHour = "last_hour"
    static let registrations = "registrations"
    static let registered = "registered"
    static let confirmed = "confirmed"
    static let notComplete = "not_complete"
    static let withdrawn = "withdrawn"
    static let toCollect = "to_collect"
    static let pickedUp = "picked_up"
    static let arrived = "arrived"
    static let scansToday = "scans_today"
    static let peopleScanned = "people_scanned"

    /// At most this many numbers fit on one card.
    static let maxOnCard = 6

    static func context(_ contextId: String) -> String { "context:\(contextId)" }

    /// The id Home's hero number shares: registrations before the event, check-ins once it's on.
    static func heroId(_ event: Event, now: Date) -> String {
        Time.phase(event.startsAt, event.endsAt, now: now) == .upcoming ? registrations : checkedIn
    }

    static func available(
        event: Event,
        stats: EventStats?,
        contexts: [ContextProgress],
        arrivals: ArrivalsSummary?,
        feed: ScanFeedSummary?,
        now: Date
    ) -> [ShareStat] {
        var out: [ShareStat] = []
        let phase = Time.phase(event.startsAt, event.endsAt, now: now)
        if let stats {
            if phase == .upcoming {
                out.append(ShareStat(id: registrations, label: "Registrations complete", value: stats.confirmed, icon: "checkmark.seal", total: stats.registered))
            } else {
                out.append(ShareStat(id: checkedIn, label: "Checked in", value: DashboardLogic.checkedInConfirmed(stats),
                                     icon: "person.crop.circle.badge.checkmark", total: stats.expected))
                out.append(ShareStat(id: notHere, label: "Not here yet", value: stats.notArrived, icon: "person.fill.questionmark"))
                if phase != .past {
                    out.append(ShareStat(id: lastHour, label: "In the last hour", value: stats.checkedInLastHour, icon: "chart.line.uptrend.xyaxis", prefix: "+"))
                }
            }
            out.append(ShareStat(id: registered, label: "Registered", value: stats.registered, icon: "person.3"))
            out.append(ShareStat(id: confirmed, label: "Confirmed", value: stats.confirmed, icon: "checkmark.seal"))
            out.append(ShareStat(id: notComplete, label: "Not complete", value: DashboardLogic.notComplete(stats), icon: "hourglass"))
            out.append(ShareStat(id: withdrawn, label: "Withdrawn", value: stats.withdrawn, icon: "person.slash"))
            for row in contexts {
                out.append(ShareStat(id: context(row.context.id), label: row.context.name, value: row.count,
                                     icon: DashboardIcons.context(row.context), total: row.total))
            }
        }
        if let arrivals {
            out.append(ShareStat(id: toCollect, label: "To collect", value: arrivals.awaitingPickup, icon: "airplane.arrival"))
            out.append(ShareStat(id: pickedUp, label: "Picked up", value: arrivals.collected, icon: "car"))
            out.append(ShareStat(id: arrived, label: "Arrived", value: arrivals.checkedIn, icon: "checkmark.circle"))
        }
        if let feed {
            out.append(ShareStat(id: scansToday, label: "Scans today", value: feed.today, icon: "qrcode.viewfinder", suffix: feed.capped ? "+" : ""))
            out.append(ShareStat(id: peopleScanned, label: "People scanned", value: feed.uniquePeopleToday, icon: "person.2"))
        }
        return out
    }

    /// Adds or removes `id`, keeping the pick order, never emptying the card and never going past `maxOnCard`.
    static func toggle(_ selected: [String], _ id: String) -> [String] {
        if selected.contains(id) { return selected.count > 1 ? selected.filter { $0 != id } : selected }
        return selected.count >= maxOnCard ? selected : selected + [id]
    }

    /// "Campfire Sydney 2026-10-03 1130.png", in the event's timezone, without characters file systems reject.
    static func fileName(_ event: Event, now: Date) -> String {
        let c = Time.calendar(Time.zone(event.timezone)).dateComponents([.year, .month, .day, .hour, .minute], from: now)
        let stamp = String(format: "%04d-%02d-%02d %02d%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0, c.hour ?? 0, c.minute ?? 0)
        let name = event.name
            .replacing(/[\\\/:*?"<>|]/, with: " ")
            .replacing(/\s+/, with: " ")
            .trimmingCharacters(in: .whitespaces)
        return "\(name.isEmpty ? "Event" : name) \(stamp).png"
    }
}

// MARK: - Card options

/// Printed at the bottom of every card.
let shareCardLink = "inw.sh/better-attend"

enum ShareCardColour: String, CaseIterable, Identifiable, Sendable {
    case red, orange, green, blue, purple

    var id: Self { self }
    var label: String { rawValue.capitalized }
    var seed: UInt32 {
        switch self {
        case .red: 0xEC3750
        case .orange: 0xFF8C37
        case .green: 0x33D6A6
        case .blue: 0x338EDA
        case .purple: 0xA633D6
        }
    }
}

enum ShareCardStyle: String, CaseIterable, Identifiable, Sendable {
    case tonal, bold, playful, outline

    var id: Self { self }
    var label: String { rawValue.capitalized }
}

enum ShareCardLayout: String, CaseIterable, Identifiable, Sendable {
    case row, grid, list

    var id: Self { self }
    var label: String { rawValue.capitalized }
    var icon: String {
        switch self {
        case .row: "rectangle.split.3x1"
        case .grid: "square.grid.2x2"
        case .list: "list.bullet.rectangle"
        }
    }

    /// Tiles per row for `count` numbers; a row never holds more than three.
    func columns(_ count: Int) -> Int {
        let n = switch self {
        case .row: count <= 3 ? count : count == 4 ? 2 : 3
        case .grid: count == 1 ? 1 : 2
        case .list: 1
        }
        return max(n, 1)
    }
}

struct ShareCardOptions: Hashable, Sendable {
    var colour = ShareCardColour.red
    /// nil follows the app's light / dark appearance.
    var dark: Bool?
    var style = ShareCardStyle.tonal
    var layout = ShareCardLayout.row
    var showIcons = true
    /// Event dates, city and the "as of" time.
    var showDetails = true
    /// "of 120" and a progress bar for numbers that have a total.
    var showTotals = true
}
