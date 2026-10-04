import Foundation
import UIKit
import UserNotifications

/// Tells staff when scans saved offline are turned down as they sync (they may already have let the
/// person in). Opening it lands on the app, where Home's banner lists each person and links to them.
enum RejectionNotifier {
    /// Asked once something has been queued offline: that's when a later rejection becomes possible.
    static func requestPermissionIfNeeded() {
        Task {
            let center = UNUserNotificationCenter.current()
            guard await center.notificationSettings().authorizationStatus == .notDetermined else { return }
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
        }
    }

    static func notify(_ rejected: [ScanRejection]) {
        guard !rejected.isEmpty else { return }
        let content = UNMutableNotificationContent()
        content.title = ScanRejectionText.offlineTitle(count: rejected.count)
        content.body = rejected.prefix(6).map(\.headline).joined(separator: "\n")
        content.sound = .default
        content.interruptionLevel = .active
        content.userInfo = ["url": "attend://home"]
        let request = UNNotificationRequest(identifier: "offline-rejections", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

/// Shows the rejection notification even while the app is open (when most syncs happen), and opens
/// the link it carries (Home) when tapped.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationRouter()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let link = (response.notification.request.content.userInfo["url"] as? String).flatMap(URL.init(string:))
        completionHandler()
        guard let link else { return }
        Task { @MainActor in UIApplication.shared.open(link) }
    }
}

enum ScanRejectionText {
    /// "2 offline check-ins were rejected"
    static func offlineTitle(count: Int) -> String {
        count == 1 ? "1 offline check-in was rejected" : "\(count) offline check-ins were rejected"
    }
}
