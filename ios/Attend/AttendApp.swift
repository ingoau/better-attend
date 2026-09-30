import SwiftUI

@main
struct AttendApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var app = AppModel(demo: DemoBackend.isEnabled)
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .tint(Color("AccentColor"))
                .preferredColorScheme(app.settings.themeMode.colorScheme)
                .task { await app.loadCaches() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                app.scheduleBackgroundRefresh()
                Task { await app.publishWidgets() }
            case .active:
                if !app.scans.pending.isEmpty { app.scheduleScanRetry(immediately: true) }
            default:
                break
            }
        }
        .backgroundTask(.appRefresh(AppModel.backgroundRefreshTask)) {
            await app.backgroundRefresh()
        }
    }
}

/// Home Screen quick actions arrive through UIKit's scene delegate.
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if let item = options.shortcutItem { QuickActions.pending = QuickActions.url(for: item) }
        let config = UISceneConfiguration(name: nil, sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

@MainActor
final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem) async -> Bool {
        guard let url = QuickActions.url(for: shortcutItem) else { return false }
        QuickActions.pending = url
        return true
    }
}

/// Bridges quick actions (UIKit) to the SwiftUI router.
@MainActor
@Observable
final class QuickActions {
    static let shared = QuickActions()
    var pendingURL: URL?

    static var pending: URL? {
        get { shared.pendingURL }
        set { shared.pendingURL = newValue }
    }

    static func url(for item: UIApplicationShortcutItem) -> URL? {
        switch item.type {
        case "au.ingo.betterattend.scan": DeepLink.scan
        case "au.ingo.betterattend.people": DeepLink.people
        case "au.ingo.betterattend.tickets": DeepLink.tickets
        default: nil
        }
    }
}
