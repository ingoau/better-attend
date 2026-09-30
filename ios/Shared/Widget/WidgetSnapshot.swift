import Foundation

/// Everything the home-screen widgets show, precomputed by the app from its local caches so the
/// widget extension never touches the network. A missing snapshot means "signed out".
struct WidgetSnapshot: Codable, Hashable, Sendable {
    var signedIn = false
    /// The organizer's selected event, when the user is an organizer.
    var organizer: OrganizerWidgetData?
    /// The participant's next ticket, when they have one.
    var ticket: TicketWidgetData?
    /// Whether the user has the participant role at all (drives the ticket widget's empty copy).
    var isParticipant = false
    var builtAt: String?

    /// Equality without the build timestamp, to skip redundant widget reloads.
    func sameContent(as other: WidgetSnapshot?) -> Bool {
        guard var other else { return false }
        var me = self
        me.builtAt = nil
        other.builtAt = nil
        return me == other
    }

    static let signedOut = WidgetSnapshot()
}

struct OrganizerWidgetData: Codable, Hashable, Sendable {
    var eventId: String
    var eventName: String
    var timezone: String?
    /// False for roles that can't read the roster (read_only), or before the first sync.
    var hasCounts = false
    var checkedIn = 0
    var expected = 0
    var notArrived = 0
    var lastHour = 0
    var contexts: [ContextCount] = []
    /// When the roster was last synced (ISO).
    var updatedAt: String?
    var travelEnabled = false
    var travel: TravelWidgetData?

    var progress: Double { expected == 0 ? 0 : Double(min(checkedIn, expected)) / Double(expected) }

    /// People are expected but check-in hasn't started: the widget leads with the expected count.
    var nobodyYet: Bool { checkedIn == 0 && expected > 0 }
}

struct ContextCount: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var count: Int
    var checksIn = false
    var isTravel = false
}

struct TravelWidgetData: Codable, Hashable, Sendable {
    var awaitingPickup = 0
    var collected = 0
    var checkedIn = 0
    var total = 0
    var nextArrivalName: String?
    var nextArrivalAt: String?
    var nextArrivalRoute: String?
    var nextArrivalReference: String?
    var nextArrivalMinor = false
}

struct TicketWidgetData: Codable, Hashable, Sendable {
    var id: String
    var eventName: String
    var startsAt: String?
    var endsAt: String?
    var timezone: String?
    var city: String?
    var confirmed = false
    var checkedIn = false
    var shortCode: String?
    /// e.g. "Ready", "Checked in", "Waiting on guardian".
    var statusLabel = ""
}

/// Builds `WidgetSnapshot`s from repository state. Pure: no I/O.
enum WidgetSnapshots {

    static func build(
        user: User?,
        event: Event?,
        roster: Roster?,
        contexts: [ScanContext]?,
        travel: TravelCalendar?,
        tickets: [Ticket]?,
        isOrganizer: Bool,
        now: Date = Date()
    ) -> WidgetSnapshot {
        guard let user else { return .signedOut }
        return WidgetSnapshot(
            signedIn: true,
            organizer: isOrganizer ? event.map { organizer($0, roster: roster, contexts: contexts, travel: travel, now: now) } : nil,
            ticket: tickets.flatMap { ticket($0, now: now) },
            isParticipant: user.isParticipant || !(tickets ?? []).isEmpty,
            builtAt: Time.iso(now)
        )
    }

    static func organizer(_ event: Event, roster: Roster?, contexts: [ScanContext]?, travel: TravelCalendar?, now: Date = Date()) -> OrganizerWidgetData {
        let usable = roster.flatMap { event.canViewParticipants && $0.syncedAt != nil ? $0 : nil }
        let stats = usable.map { EventStats.from($0.participants, now: now) }
        return OrganizerWidgetData(
            eventId: event.id,
            eventName: event.name,
            timezone: event.timezone,
            hasCounts: stats != nil,
            checkedIn: stats?.checkedIn ?? 0,
            expected: stats?.expected ?? 0,
            notArrived: stats?.notArrived ?? 0,
            lastHour: stats?.checkedInLastHour ?? 0,
            contexts: stats.flatMap { s in usable.map { contextCounts(s, roster: $0, contexts: contexts) } } ?? [],
            updatedAt: usable?.lastSyncAt,
            travelEnabled: event.travelEnabled,
            travel: event.travelEnabled ? travel.map { self.travel($0, now: now) } : nil
        )
    }

    /// Per-context unique-participant counts, in the event's context order. Falls back to names seen in the roster.
    static func contextCounts(_ stats: EventStats, roster: Roster, contexts: [ScanContext]?) -> [ContextCount] {
        if let contexts, !contexts.isEmpty {
            return contexts.sorted { $0.position < $1.position }.map { c in
                ContextCount(id: c.id, name: c.name, count: stats.perContext[c.id] ?? 0, checksIn: c.checksIn, isTravel: c.isTravelPickup || c.isAirport)
            }
        }
        var seen: [String: ContextCount] = [:]
        var order: [String] = []
        for p in roster.participants {
            for s in p.scansByContext where seen[s.scanContextId] == nil {
                order.append(s.scanContextId)
                seen[s.scanContextId] = ContextCount(
                    id: s.scanContextId, name: s.scanContextName ?? "Scan point",
                    count: stats.perContext[s.scanContextId] ?? 0, checksIn: s.checksIn, isTravel: s.isTravelPickup
                )
            }
        }
        // Stable sort: ties keep first-seen order.
        return order.compactMap { seen[$0] }.enumerated()
            .sorted { $0.element.count != $1.element.count ? $0.element.count > $1.element.count : $0.offset < $1.offset }
            .map(\.element)
    }

    static func travel(_ cal: TravelCalendar, now: Date = Date()) -> TravelWidgetData {
        let grace = now.addingTimeInterval(-30 * 60)
        let next = cal.entries
            .filter { $0.direction == "inbound" && $0.pickupState != "collected" && $0.pickupState != "checked_in" }
            .compactMap { e in Time.parse(e.primaryTimeAt).flatMap { $0 >= grace ? (e, $0) : nil } }
            .min { $0.1 < $1.1 }?.0
        return TravelWidgetData(
            awaitingPickup: cal.counts.awaitingPickup,
            collected: cal.counts.collected,
            checkedIn: cal.counts.checkedIn,
            total: cal.counts.inbound > 0 ? cal.counts.inbound : cal.counts.total,
            nextArrivalName: next?.name,
            nextArrivalAt: next?.primaryTimeAt,
            nextArrivalRoute: next?.route,
            nextArrivalReference: next?.reference,
            nextArrivalMinor: next?.isUnaccompaniedMinor ?? false
        )
    }

    static func ticket(_ tickets: [Ticket], now: Date = Date()) -> TicketWidgetData? {
        guard let t = TicketLogic.next(tickets, now: now) else { return nil }
        return TicketWidgetData(
            id: t.id, eventName: t.event.name, startsAt: t.event.startsAt, endsAt: t.event.endsAt,
            timezone: t.event.timezone, city: t.event.locationCity, confirmed: t.confirmed,
            checkedIn: t.checkedIn, shortCode: t.shortCode, statusLabel: TicketLogic.status(t).label
        )
    }
}

/// Reads and writes the snapshot in the App Group container shared with the widget extension.
enum WidgetSnapshotStore {
    static let appGroup = "group.au.ingo.betterattend"
    private static let fileName = "widget_snapshot.json"

    static var fileURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?.appendingPathComponent(fileName)
    }

    static func read() -> WidgetSnapshot {
        guard let url = fileURL, let data = try? Data(contentsOf: url),
              let snap = try? JSONDecoder().decode(WidgetSnapshot.self, from: data) else { return .signedOut }
        return snap
    }

    static func write(_ snapshot: WidgetSnapshot) {
        guard let url = fileURL else { return }
        if !snapshot.signedIn {
            try? FileManager.default.removeItem(at: url)
            return
        }
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        // Readable after first unlock so the widget can render from the lock screen.
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

/// Deep links shared by the app, widgets, controls and quick actions.
enum DeepLink {
    static let scheme = "attend"
    static let scan = URL(string: "attend://scan")!
    static let people = URL(string: "attend://people")!
    static let travel = URL(string: "attend://travel")!
    static let home = URL(string: "attend://home")!
    static let tickets = URL(string: "attend://tickets")!
    static func ticket(_ id: String) -> URL { URL(string: "attend://ticket/\(id)")! }
}
