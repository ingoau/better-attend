import Foundation

/// Everyone arriving at one time, and when to remind about picking them up.
struct ArrivalSlot: Hashable, Sendable {
    var arrivesAt: Date
    var remindAt: Date
    var entries: [TravelEntry]

    /// Stable for the same people at the same time; changes when either does.
    var key: String { Time.iso(arrivesAt) + ":" + entries.map(\.id).sorted().joined(separator: ",") }
}

/// An arrival awaiting pickup whose time moved since its reminder was scheduled.
struct ArrivalChange: Hashable, Sendable {
    var entry: TravelEntry
    var was: Date
    var now: Date
}

/// Pickup reminders built from the travel calendar, and how they're worded (a port of Android's `ArrivalAlerts`).
enum ArrivalAlerts {
    static let lead: TimeInterval = 30 * 60
    /// Smaller shifts than this are noise (rounding, a gate change), not worth a notification.
    static let changeThreshold: TimeInterval = 15 * 60
    /// Only the next few are scheduled (iOS keeps at most 64 pending); each travel refresh schedules the next ones.
    static let maxSlots = 20

    /// Arrivals still to come that someone has to collect.
    static func awaitingPickup(_ calendar: TravelCalendar, now: Date) -> [(entry: TravelEntry, at: Date)] {
        calendar.entries.compactMap { e in
            guard e.direction == "inbound", e.pickupState == "awaiting_pickup", let at = Time.parse(e.primaryTimeAt), at > now else { return nil }
            return (e, at)
        }
    }

    /// One reminder per arrival time, `lead` before it (or now, if that's already passed), soonest first.
    /// People already reminded about at that same time (`reminded`, see `remindedKey`) are left out.
    static func slots(_ calendar: TravelCalendar, now: Date, reminded: Set<String> = []) -> [ArrivalSlot] {
        let pending = awaitingPickup(calendar, now: now).filter { !reminded.contains(remindedKey($0.entry.id, $0.at)) }
        let byTime = Dictionary(grouping: pending, by: { $0.at })
        let slots = byTime.map { at, items in
            ArrivalSlot(arrivesAt: at, remindAt: max(at.addingTimeInterval(-lead), now),
                        entries: items.map { $0.entry }.sorted { $0.name.lowercased() < $1.name.lowercased() })
        }
        return Array(slots.sorted { $0.arrivesAt < $1.arrivesAt }.prefix(maxSlots))
    }

    static func remindedKey(_ entryId: String, _ at: Date) -> String { "\(entryId)@\(Time.iso(at))" }

    /// Arrivals scheduled before (`previous`: entry id → time) that moved by at least `changeThreshold`.
    static func changes(previous: [String: String], calendar: TravelCalendar, now: Date) -> [ArrivalChange] {
        awaitingPickup(calendar, now: now).compactMap { item in
            guard let was = Time.parse(previous[item.entry.id]), abs(item.at.timeIntervalSince(was)) >= changeThreshold else { return nil }
            return ArrivalChange(entry: item.entry, was: was, now: item.at)
        }
        .sorted { $0.now < $1.now }
    }

    /// "Pickup at 10:45: Mia Chen" / "3 arrivals to collect at 10:45"
    static func reminderTitle(_ slot: ArrivalSlot, tz: String?) -> String {
        let time = Time.time(slot.arrivesAt, zone: Time.zone(tz))
        if slot.entries.count == 1 { return "Pickup at \(time): \(slot.entries[0].name)" }
        return "\(slot.entries.count) arrivals to collect at \(time)"
    }

    /// "QF1471 · SYD → CBR" for one person; their names when there are several.
    static func reminderText(_ slot: ArrivalSlot) -> String {
        if slot.entries.count == 1 { return [slot.entries[0].reference, slot.entries[0].route].compactMap { $0 }.joined(separator: " · ") }
        return RosterAlerts.summary(slot.entries.map(\.name))
    }

    /// "Mia Chen now arrives at 11:30 (45 min later)"
    static func changeLine(_ change: ArrivalChange, tz: String?) -> String {
        let minutes = Int(change.now.timeIntervalSince(change.was) / 60)
        let shift = switch minutes {
        case 120...: "\(minutes / 60) h later"
        case 1...: "\(minutes) min later"
        case ...(-120): "\(-minutes / 60) h earlier"
        default: "\(-minutes) min earlier"
        }
        return "\(change.entry.name) now arrives at \(Time.time(change.now, zone: Time.zone(tz))) (\(shift))"
    }

    static func changeTitle(count: Int) -> String { count == 1 ? "Arrival time changed" : "\(count) arrival times changed" }
}

/// What's scheduled for one event's pickup reminders. Ids and times only: names live in the pending
/// notifications, never on disk.
struct ArrivalReminderState: Codable, Hashable, Sendable {
    var eventId: String?
    /// Entry id → arrival time it was last scheduled for.
    var scheduled: [String: String] = [:]
    /// The reminders scheduled: when each goes off, and who it's about (`ArrivalAlerts.remindedKey`s).
    var pending: [PendingReminder] = []
    /// `ArrivalAlerts.remindedKey`s already notified.
    var reminded: Set<String> = []

    struct PendingReminder: Codable, Hashable, Sendable {
        /// The notification request's identifier, to remove it when rescheduling.
        var id: String
        var remindAt: String
        var keys: [String]
    }
}

/// One reschedule: what to announce, what to schedule, and the state to save.
struct ArrivalReminderPlan: Sendable {
    var changes: [ArrivalChange]
    /// Lines up with `state.pending`.
    var slots: [ArrivalSlot]
    var state: ArrivalReminderState
    /// Requests from the last schedule to remove before adding these.
    var obsolete: [String]

    /// iOS can't run code when a reminder is delivered, so a reminder whose time has passed counts as delivered.
    init(previous: ArrivalReminderState, eventId: String, calendar: TravelCalendar, now: Date) {
        let old = previous.eventId == eventId ? previous : ArrivalReminderState(eventId: eventId)
        let upcoming = ArrivalAlerts.awaitingPickup(calendar, now: now)
        let live = Set(upcoming.map { ArrivalAlerts.remindedKey($0.entry.id, $0.at) })
        let delivered = old.pending.filter { (Time.parse($0.remindAt) ?? .distantFuture) <= now }.flatMap(\.keys)
        let reminded = old.reminded.union(delivered).intersection(live)
        let slots = ArrivalAlerts.slots(calendar, now: now, reminded: reminded)

        self.obsolete = previous.pending.map(\.id)
        self.changes = ArrivalAlerts.changes(previous: old.scheduled, calendar: calendar, now: now)
        self.slots = slots
        self.state = ArrivalReminderState(
            eventId: eventId,
            scheduled: Dictionary(upcoming.map { ($0.entry.id, Time.iso($0.at)) }, uniquingKeysWith: { a, _ in a }),
            pending: slots.map { slot in
                .init(id: UUID().uuidString, remindAt: Time.iso(slot.remindAt),
                      keys: slot.entries.map { ArrivalAlerts.remindedKey($0.id, slot.arrivesAt) })
            },
            reminded: reminded
        )
    }
}
