import Foundation
import SwiftUI

enum ThemeMode: String, CaseIterable, Identifiable, Sendable {
    case system, light, dark
    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// App preferences, persisted in UserDefaults.
@MainActor
@Observable
final class SettingsStore {
    @ObservationIgnored private let defaults: UserDefaults

    var themeMode: ThemeMode { didSet { defaults.set(themeMode.rawValue, forKey: Keys.theme) } }
    var sounds: Bool { didSet { defaults.set(sounds, forKey: Keys.sounds) } }
    var haptics: Bool { didSet { defaults.set(haptics, forKey: Keys.haptics) } }
    var keepScreenOn: Bool { didSet { defaults.set(keepScreenOn, forKey: Keys.keepScreenOn) } }
    var selectedEventId: String? {
        didSet {
            defaults.set(selectedEventId, forKey: Keys.selectedEvent)
            if selectedEventId != oldValue { onArrivalAlertsChange() }
        }
    }
    /// Notify when people sign up for the selected event. Off until the organizer turns it on.
    var signupNotifications: Bool {
        didSet {
            defaults.set(signupNotifications, forKey: Keys.signupNotifications)
            if signupNotifications != oldValue { signupNotificationsSince = signupNotifications ? Time.nowISO() : nil }
        }
    }
    /// When they last turned it on (ISO-8601): rosters synced before then don't count as a baseline.
    private(set) var signupNotificationsSince: String? {
        didSet { defaults.set(signupNotificationsSince, forKey: Keys.signupNotificationsSince) }
    }
    /// Notify when people withdraw from the selected event. Off until the organizer turns it on.
    var withdrawalNotifications: Bool {
        didSet {
            defaults.set(withdrawalNotifications, forKey: Keys.withdrawalNotifications)
            if withdrawalNotifications != oldValue { withdrawalNotificationsSince = withdrawalNotifications ? Time.nowISO() : nil }
        }
    }
    private(set) var withdrawalNotificationsSince: String? {
        didSet { defaults.set(withdrawalNotificationsSince, forKey: Keys.withdrawalNotificationsSince) }
    }
    /// Remind before each arrival that's awaiting pickup at the selected event. Off until turned on.
    var arrivalNotifications: Bool {
        didSet {
            defaults.set(arrivalNotifications, forKey: Keys.arrivalNotifications)
            if arrivalNotifications != oldValue { onArrivalAlertsChange() }
        }
    }
    /// Any alert that needs the roster or travel kept fresh in the background.
    var anyEventAlerts: Bool { signupNotifications || withdrawalNotifications || arrivalNotifications }
    /// Pickup reminders follow the selected event and their setting (set by `AppModel`).
    @ObservationIgnored var onArrivalAlertsChange: () -> Void = {}
    /// Where pickup reminders keep what they've scheduled.
    var store: UserDefaults { defaults }
    /// eventId -> scan context id
    var selectedContexts: [String: String] { didSet { defaults.set(selectedContexts, forKey: Keys.contexts) } }

    private enum Keys {
        static let theme = "theme_mode"
        static let sounds = "sounds"
        static let haptics = "haptics"
        static let keepScreenOn = "keep_screen_on"
        static let selectedEvent = "selected_event"
        static let contexts = "selected_contexts"
        static let signupNotifications = "signup_notifications"
        static let signupNotificationsSince = "signup_notifications_since"
        static let withdrawalNotifications = "withdrawal_notifications"
        static let withdrawalNotificationsSince = "withdrawal_notifications_since"
        static let arrivalNotifications = "arrival_notifications"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        themeMode = defaults.string(forKey: Keys.theme).flatMap(ThemeMode.init(rawValue:)) ?? .system
        sounds = defaults.object(forKey: Keys.sounds) as? Bool ?? true
        haptics = defaults.object(forKey: Keys.haptics) as? Bool ?? true
        keepScreenOn = defaults.object(forKey: Keys.keepScreenOn) as? Bool ?? true
        selectedEventId = defaults.string(forKey: Keys.selectedEvent)
        signupNotifications = defaults.bool(forKey: Keys.signupNotifications)
        signupNotificationsSince = defaults.string(forKey: Keys.signupNotificationsSince)
        withdrawalNotifications = defaults.bool(forKey: Keys.withdrawalNotifications)
        withdrawalNotificationsSince = defaults.string(forKey: Keys.withdrawalNotificationsSince)
        arrivalNotifications = defaults.bool(forKey: Keys.arrivalNotifications)
        selectedContexts = defaults.dictionary(forKey: Keys.contexts) as? [String: String] ?? [:]
    }

    func setSelectedContext(eventId: String, contextId: String) {
        selectedContexts[eventId] = contextId
    }

    /// Forget account-specific choices (sign-out / session expiry). Preferences stay.
    func clearAccountData() {
        selectedEventId = nil
        selectedContexts = [:]
        // The next account opts in for itself.
        signupNotifications = false
        withdrawalNotifications = false
        arrivalNotifications = false
    }
}
