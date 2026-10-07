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
    var selectedEventId: String? { didSet { defaults.set(selectedEventId, forKey: Keys.selectedEvent) } }
    /// Notify when people sign up for the selected event. Off until the organizer turns it on.
    var signupNotifications: Bool { didSet { defaults.set(signupNotifications, forKey: Keys.signupNotifications) } }
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
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        themeMode = defaults.string(forKey: Keys.theme).flatMap(ThemeMode.init(rawValue:)) ?? .system
        sounds = defaults.object(forKey: Keys.sounds) as? Bool ?? true
        haptics = defaults.object(forKey: Keys.haptics) as? Bool ?? true
        keepScreenOn = defaults.object(forKey: Keys.keepScreenOn) as? Bool ?? true
        selectedEventId = defaults.string(forKey: Keys.selectedEvent)
        signupNotifications = defaults.bool(forKey: Keys.signupNotifications)
        selectedContexts = defaults.dictionary(forKey: Keys.contexts) as? [String: String] ?? [:]
    }

    func setSelectedContext(eventId: String, contextId: String) {
        selectedContexts[eventId] = contextId
    }

    /// Forget account-specific choices (sign-out / session expiry). Preferences stay.
    func clearAccountData() {
        selectedEventId = nil
        selectedContexts = [:]
        signupNotifications = false // the next account opts in for itself
    }
}
