import Foundation
import UserNotifications

/// The alerts organizers turn on in Settings → Notifications: signups and withdrawals at their event,
/// and pickup reminders. Each batch is its own notification, grouped per event so earlier ones aren't
/// lost. All identifiers start with `prefix` so sign-out can clear them (they name participants).
enum AlertNotifier {
    static let prefix = "alerts-"

    enum RosterKind: String, Sendable {
        case signups, withdrawals
    }

    /// Asks for permission when an alert is turned on. False if notifications are (or stay) off.
    static func requestPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        case .notDetermined: return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        default: return false
        }
    }

    /// The user has refused or switched off notifications for the app.
    static func isBlocked() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .denied
    }

    static func notify(_ kind: RosterKind, eventId: String, eventName: String?, people: [Participant]) {
        guard !people.isEmpty else { return }
        let content = UNMutableNotificationContent()
        content.title = switch kind {
        case .signups: RosterAlerts.signupTitle(count: people.count, eventName: eventName)
        case .withdrawals: RosterAlerts.withdrawalTitle(count: people.count, eventName: eventName)
        }
        content.body = RosterAlerts.summary(people.map(\.name))
        content.sound = .default
        content.interruptionLevel = .active
        content.threadIdentifier = "\(prefix)\(kind.rawValue)-\(eventId)"
        content.userInfo = ["url": DeepLink.people.absoluteString]
        add(content, kind: kind.rawValue, eventId: eventId)
    }

    fileprivate static func add(_ content: UNMutableNotificationContent, kind: String, eventId: String, trigger: UNNotificationTrigger? = nil,
                                identifier: String? = nil) {
        let id = identifier ?? "\(prefix)\(kind)-\(eventId)-\(UUID().uuidString)"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }

    /// Clears every alert, delivered or still to come (sign-out).
    static func removeAll() {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let ids = delivered.map(\.request.identifier).filter { $0.hasPrefix(prefix) }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        }
        ArrivalReminders.removePending()
    }
}

/// Pickup reminders, scheduled on the device from the cached travel calendar so they go off on time
/// even offline. Rescheduled whenever the calendar refreshes: collected people drop out, new times move
/// the reminder, and one already scheduled that moves by 15+ minutes gets a "time changed" notice.
/// Only the selected event's arrivals are scheduled.
@MainActor
enum ArrivalReminders {
    private static let stateKey = "arrival_reminders"
    nonisolated private static let pendingPrefix = AlertNotifier.prefix + "arrival-reminder-"

    static func reschedule(eventId: String, eventName: String?, calendar: TravelCalendar, defaults: UserDefaults, now: Date = Date()) {
        let plan = ArrivalReminderPlan(previous: state(defaults), eventId: eventId, calendar: calendar, now: now)
        let tz = calendar.eventTimezone

        if !plan.changes.isEmpty {
            let content = UNMutableNotificationContent()
            content.title = ArrivalAlerts.changeTitle(count: plan.changes.count)
            content.body = plan.changes.prefix(6).map { ArrivalAlerts.changeLine($0, tz: tz) }.joined(separator: "\n")
            content.subtitle = eventName ?? ""
            content.sound = .default
            content.threadIdentifier = "\(AlertNotifier.prefix)arrivals-\(eventId)"
            content.userInfo = ["url": DeepLink.travel.absoluteString]
            AlertNotifier.add(content, kind: "arrival-change", eventId: eventId)
        }

        // Removed by identifier rather than by listing what's pending: that's asynchronous, and could
        // land after the new requests below are added.
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: plan.obsolete.map { pendingPrefix + $0 })
        for (slot, pending) in zip(plan.slots, plan.state.pending) {
            let content = UNMutableNotificationContent()
            content.title = ArrivalAlerts.reminderTitle(slot, tz: tz)
            content.body = ArrivalAlerts.reminderText(slot)
            content.subtitle = eventName ?? ""
            content.sound = .default
            content.interruptionLevel = .active
            content.threadIdentifier = "\(AlertNotifier.prefix)arrivals-\(eventId)"
            content.userInfo = ["url": DeepLink.travel.absoluteString]
            let wait = slot.remindAt.timeIntervalSince(now)
            // Already due: deliver now rather than as a pending request the next reschedule would remove.
            let trigger = wait >= 1 ? UNTimeIntervalNotificationTrigger(timeInterval: wait, repeats: false) : nil
            AlertNotifier.add(content, kind: "arrival-reminder", eventId: eventId, trigger: trigger,
                              identifier: trigger == nil ? nil : pendingPrefix + pending.id)
        }
        save(plan.state, defaults)
    }

    /// Turned off, signed out, or switched to an event without travel.
    static func cancelAll(defaults: UserDefaults) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: state(defaults).pending.map { pendingPrefix + $0.id })
        defaults.removeObject(forKey: stateKey)
    }

    /// The event reminders are scheduled for, if any.
    static func scheduledEvent(_ defaults: UserDefaults) -> String? { state(defaults).eventId }

    /// Every pending reminder, whatever its event (sign-out).
    nonisolated static func removePending() {
        UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
            let ids = requests.map(\.identifier).filter { $0.hasPrefix(pendingPrefix) }
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
        }
    }

    private static func state(_ defaults: UserDefaults) -> ArrivalReminderState {
        defaults.data(forKey: stateKey).flatMap { try? JSONDecoder().decode(ArrivalReminderState.self, from: $0) } ?? ArrivalReminderState()
    }

    private static func save(_ state: ArrivalReminderState, _ defaults: UserDefaults) {
        defaults.set(try? JSONEncoder().encode(state), forKey: stateKey)
    }
}
