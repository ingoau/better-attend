import SwiftUI

/// Switches between sign-in and the signed-in app.
struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        ZStack {
            switch app.auth.state {
            case .loading:
                ProgressView("Signing you in…")
                    .controlSize(.large)
                    .transition(.opacity)
            case .signedOut(let message):
                LoginView(message: message)
                    .transition(.opacity)
            case .signedIn(let user):
                SignedInView(user: user)
                    .id(user.id)
                    .transition(.opacity)
            }
        }
        .animation(.smooth, value: app.auth.state)
        .onChange(of: app.settings.haptics, initial: true) { _, on in Haptics.enabled = on }
    }
}

/// The signed-in app: tabs for the user's roles, deep links, and the first refresh.
struct SignedInView: View {
    let user: User
    @Environment(AppModel.self) private var app
    @State private var router = Router()

    private var tabs: [AppTab] {
        var tabs: [AppTab] = []
        let selected = app.events.selectedEvent
        if app.isOrganizer {
            tabs += [.home, .scan]
            if selected?.canViewParticipants != false { tabs.append(.people) }
            if selected?.travelEnabled == true { tabs.append(.travel) }
        }
        if user.isParticipant || !app.isOrganizer { tabs.append(.tickets) }
        return tabs
    }

    var body: some View {
        @Bindable var router = router
        TabView(selection: $router.tab) {
            ForEach(tabs) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: tab) {
                    TabRoot(tab: tab, showsAccountOnly: tabs == [.tickets])
                        // A lone tab needs no tab bar (participant-only accounts).
                        .toolbar(tabs.count == 1 ? .hidden : .automatic, for: .tabBar)
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .environment(router)
        .sheet(item: $router.sheet) { sheet in
            switch sheet {
            case .settings: SettingsView().environment(router)
            case .eventPicker: EventPickerSheet().environment(router)
            }
        }
        .fullScreenCover(item: $router.kiosk) { config in
            KioskView(config: config).environment(router)
        }
        .onChange(of: tabs, initial: true) { _, tabs in
            if !tabs.contains(router.tab), let first = tabs.first { router.tab = first }
        }
        .onOpenURL { url in router.handle(url, available: tabs, selectedEventId: app.events.selectedEvent?.id) }
        .onChange(of: QuickActions.shared.pendingURL, initial: true) { _, url in
            guard let url else { return }
            router.handle(url, available: tabs, selectedEventId: app.events.selectedEvent?.id)
            QuickActions.pending = nil
        }
        .task(id: user.id) {
            async let me: Void = app.auth.refreshUser()
            async let events: Void = { if user.isOrganizer || user.globalAdmin { _ = try? await app.events.refresh() } }()
            async let tickets: Void = { if user.isParticipant { _ = try? await app.tickets.refresh() } }()
            _ = await (me, events, tickets)
            // Debug/screenshot hook: `-AttendOpenURL attend://people` opens a screen at launch.
            if let link = UserDefaults.standard.string(forKey: "AttendOpenURL").flatMap(URL.init(string:)) {
                router.handle(link, available: tabs, selectedEventId: app.events.selectedEvent?.id)
            }
            await app.publishWidgets()
        }
    }
}

/// One tab: its own navigation stack, with every pushable route registered.
struct TabRoot: View {
    let tab: AppTab
    var showsAccountOnly = false
    @Environment(Router.self) private var router

    var body: some View {
        NavigationStack(path: router.path(for: tab)) {
            Group {
                switch tab {
                case .home: DashboardView()
                case .scan: ScanView()
                case .people: PeopleView()
                case .travel: TravelView()
                case .tickets: TicketsView(showsAccount: showsAccountOnly)
                }
            }
            .navigationDestination(for: Route.self) { route in
                switch route {
                case let .participant(eventId, participantEventId, browseIds):
                    ParticipantDetailView(eventId: eventId, participantEventId: participantEventId, browseIds: browseIds)
                case let .ticket(id):
                    TicketDetailView(ticketId: id)
                case let .blasts(eventId):
                    BlastsView(eventId: eventId)
                case let .rollCall(eventId):
                    RollCallView(eventId: eventId)
                case let .firstAid(eventId):
                    FirstAidView(eventId: eventId)
                }
            }
        }
    }
}
