import Foundation
import UserNotifications

/// Tells organizers who opted in (Settings → Notifications) when people sign up for their event.
/// One notification per event, replaced by the next batch; opens People.
enum SignupNotifier {
    private static let prefix = "signups-"

    /// Asks for permission when the setting is turned on. False if notifications are (or stay) off.
    static func requestPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: return true
        case .notDetermined: return (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        default: return false
        }
    }

    static func notify(eventId: String, eventName: String?, signups: [Participant]) {
        guard !signups.isEmpty else { return }
        let content = UNMutableNotificationContent()
        content.title = SignupLogic.title(count: signups.count, eventName: eventName)
        content.body = SignupLogic.summary(signups)
        content.sound = .default
        content.interruptionLevel = .active
        content.threadIdentifier = prefix + eventId
        content.userInfo = ["url": DeepLink.people.absoluteString]
        let request = UNNotificationRequest(identifier: prefix + eventId, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// Clears every signup notification (sign-out): they name participants.
    static func removeAll() {
        UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
            let ids = delivered.map(\.request.identifier).filter { $0.hasPrefix(prefix) }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        }
    }
}
