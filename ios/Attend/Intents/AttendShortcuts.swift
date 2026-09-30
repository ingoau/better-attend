import AppIntents

// App Intents for Siri, Spotlight, the Shortcuts app and the Action button. Each opens the app on
// the right screen through the same path as Home Screen quick actions (`QuickActions.pending` is
// picked up by the signed-in UI, even on a cold launch).

struct ScanTicketsIntent: AppIntent {
    static let title: LocalizedStringResource = "Scan Tickets"
    static let description = IntentDescription("Opens the Attend scanner with the camera ready.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        QuickActions.pending = DeepLink.scan
        return .result()
    }
}

struct FindPersonIntent: AppIntent {
    static let title: LocalizedStringResource = "Find a Person"
    static let description = IntentDescription("Opens People in Attend to search your event's participants.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        QuickActions.pending = DeepLink.people
        return .result()
    }
}

struct ShowMyTicketIntent: AppIntent {
    static let title: LocalizedStringResource = "Show My Ticket"
    static let description = IntentDescription("Opens your pass for your next Hack Club event.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        QuickActions.pending = Self.destination(WidgetSnapshotStore.read())
        return .result()
    }

    /// The next confirmed ticket's pass, otherwise the tickets list.
    static func destination(_ snapshot: WidgetSnapshot) -> URL {
        guard let t = snapshot.ticket, t.confirmed else { return DeepLink.tickets }
        return DeepLink.ticket(t.id)
    }
}

struct AttendShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ScanTicketsIntent(),
            phrases: [
                "Scan tickets in \(.applicationName)",
                "Open the \(.applicationName) scanner",
                "Check people in with \(.applicationName)",
            ],
            shortTitle: "Scan Tickets",
            systemImageName: "qrcode.viewfinder"
        )
        AppShortcut(
            intent: FindPersonIntent(),
            phrases: [
                "Find a person in \(.applicationName)",
                "Search people in \(.applicationName)",
            ],
            shortTitle: "Find a Person",
            systemImageName: "person.2"
        )
        AppShortcut(
            intent: ShowMyTicketIntent(),
            phrases: [
                "Show my ticket in \(.applicationName)",
                "Show my \(.applicationName) ticket",
                "Open my \(.applicationName) pass",
            ],
            shortTitle: "My Ticket",
            systemImageName: "ticket"
        )
    }

    static let shortcutTileColor: ShortcutTileColor = .red
}
