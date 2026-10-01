import AppIntents
import Foundation
import WidgetKit

/// The refresh button on the organizer widgets. Runs in the widget extension: it re-reads the
/// snapshot the app last wrote to the App Group and re-renders, so "Updated … ago" and the counts
/// are current. (The extension never touches the network; the app refreshes the snapshot when it
/// runs and in background refresh.)
struct RefreshWidgetsIntent: AppIntent {
    static let title: LocalizedStringResource = "Refresh Widgets"
    static let description = IntentDescription("Reloads BetterAttend's widgets from the latest synced data.")
    static let isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult {
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

/// Opens the scanner. Compiled into both the app and the widget extension so the Control Center /
/// Lock Screen control can run it; the system opens the app and follows the deep link.
struct OpenScannerIntent: AppIntent {
    static let title: LocalizedStringResource = "Scan Tickets"
    static let description = IntentDescription("Opens the BetterAttend scanner with the camera ready.")
    static let openAppWhenRun = true
    // The discoverable "Scan tickets" shortcut is `ScanTicketsIntent` in the app.
    static let isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult & OpensIntent {
        .result(opensIntent: OpenURLIntent(DeepLink.scan))
    }
}
