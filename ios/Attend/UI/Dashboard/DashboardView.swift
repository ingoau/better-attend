import SwiftUI

/// Home: live check-in numbers for the selected event, quick actions, and what needs doing next.
struct DashboardView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var model = DashboardModel()
    /// Keeps "Updated 2 min ago", countdowns and "in the last hour" honest between syncs.
    @State private var now = Date()

    var body: some View {
        content
            .eventToolbar(fallbackTitle: "Home")
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(30))
                    now = Date()
                }
            }
    }

    @ViewBuilder private var content: some View {
        let events = app.events.events
        if events == nil {
            ProgressView("Loading your events…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(.systemGroupedBackground))
        } else if events?.isEmpty == true {
            NoEventsView()
        } else if let event = app.events.selectedEvent {
            DashboardBody(event: event, model: model, now: now)
                .id(event.id)
                .transition(.opacity)
        } else {
            ContentUnavailableView {
                Label("Pick an Event", systemImage: "calendar.badge.checkmark")
            } description: {
                Text("Choose which event you're working on. You can switch any time from the title.")
            } actions: {
                Button("Choose Event") { router.sheet = .eventPicker }
                    .buttonStyle(.borderedProminent)
            }
        }
    }
}

/// Signed in as staff, but no event has added us yet.
private struct NoEventsView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var checking = false
    /// With nothing cached, an offline launch looks like an empty list, so ask the server before
    /// claiming there are no events.
    @State private var error: String?

    var body: some View {
        ScrollView {
            Group {
                if let error {
                    ContentUnavailableView {
                        Label("Couldn't Load Your Events", systemImage: "icloud.slash")
                    } description: {
                        Text(error)
                    } actions: {
                        checkButton("Try Again").buttonStyle(.borderedProminent)
                    }
                } else {
                    ContentUnavailableView {
                        Label("You're Not on Any Events Yet", systemImage: "calendar.badge.exclamationmark")
                    } description: {
                        Text("To run check-in, an event admin needs to add you as staff for their event on attend.hackclub.com. Once they do, it'll show up here.")
                    } actions: {
                        if app.user?.isParticipant == true {
                            Button("Open My Tickets") { router.switchTab(.tickets) }
                                .buttonStyle(.borderedProminent)
                        }
                        checkButton("Check Again").buttonStyle(.bordered)
                    }
                }
            }
            .containerRelativeFrame(.vertical)
        }
        .background(Color(.systemGroupedBackground))
        .refreshable { await check() }
        .task { await check() }
        .animation(.smooth, value: error)
    }

    private func checkButton(_ title: String) -> some View {
        Button {
            Haptics.tap()
            Task { await check() }
        } label: {
            if checking { ProgressView() } else { Text(title) }
        }
        .disabled(checking)
    }

    private func check() async {
        guard !checking else { return }
        checking = true
        defer { checking = false }
        do {
            try await app.events.refresh()
            error = nil
        } catch where !error.isCancellation {
            self.error = error.friendlyMessage
        } catch {}
    }
}

/// The scrolling dashboard for one event.
private struct DashboardBody: View {
    let event: Event
    let model: DashboardModel
    let now: Date

    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.horizontalSizeClass) private var sizeClass

    // A roster without `syncedAt` is partial (a few people learned from scans before the first full
    // sync): counting it would say "3 of 3 checked in", so treat it as not loaded yet.
    private var roster: Roster? { app.participants.roster(event.id).flatMap { $0.syncedAt != nil ? $0 : nil } }
    private var phase: Time.Phase { Time.phase(event.startsAt, event.endsAt, now: now) }
    private var travel: TravelCalendar? { event.travelEnabled ? app.travel.calendars[event.id] : nil }
    private var wide: Bool { sizeClass == .regular }

    var body: some View {
        let roster = self.roster
        let stats = roster.map { EventStats.from($0.participants, now: now) }
        let arrivals = DashboardLogic.arrivals(travel, now: now)

        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if let error = model.error {
                    NoticeBanner(message: roster != nil || model.scans != nil ? "Showing saved numbers. \(error)" : error, tone: .warning) {
                        Task { await model.refresh(event, app: app, force: true) }
                    }
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                if event.canViewParticipants {
                    organizerCards(roster: roster, stats: stats, arrivals: arrivals)
                } else {
                    limitedCards(arrivals: arrivals)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 32)
            .frame(maxWidth: wide ? 1080 : 680)
            .frame(maxWidth: .infinity)
            .animation(.smooth, value: model.error)
            .animation(.smooth, value: stats == nil)
        }
        .background(Color(.systemGroupedBackground))
        .refreshable { await model.refresh(event, app: app, force: true) }
        .poll(every: .seconds(60), id: event.id) { await model.refresh(event, app: app, force: false) }
    }

    // MARK: Header

    private var header: some View {
        let pending = app.scans.pending.count(where: { $0.eventId == event.id })
        let updated = DashboardLogic.updated(model.lastUpdated ?? Time.parse(app.participants.roster(event.id)?.lastSyncAt), now: now)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                if let subtitle = DashboardLogic.subtitle(event) {
                    Text(subtitle).lineLimit(1)
                }
                DashPhasePill(phase: phase)
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            HStack(spacing: 6) {
                Image(systemName: model.refreshing ? "arrow.triangle.2.circlepath" : "checkmark.circle")
                    .symbolEffect(.rotate, isActive: model.refreshing)
                Text(model.refreshing ? "Syncing…" : updated ?? "Not synced yet")
                    .contentTransition(.opacity)
                Spacer(minLength: 8)
                if pending > 0 {
                    Pill(text: "\(pending) scan\(pending == 1 ? "" : "s") to sync", tone: .warning, systemImage: "icloud.and.arrow.up")
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .animation(.smooth, value: model.refreshing)
            .animation(.smooth, value: pending)
        }
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }

    // MARK: Organizer (roster access)

    @ViewBuilder
    private func organizerCards(roster: Roster?, stats: EventStats?, arrivals: ArrivalsSummary?) -> some View {
        let progress = stats.map { DashboardLogic.contextProgress(app.events.cachedContexts(event.id) ?? [], stats: $0, now: now) } ?? []
        // Before doors open every bar is empty, so skip them.
        let showContexts = !progress.isEmpty && (phase != .upcoming || progress.contains { $0.count > 0 })
        let attention = roster.map { DashboardLogic.needsAttention($0.participants) } ?? 0

        let primary = VStack(spacing: 16) {
            if let stats {
                HomeHeroCard(event: event, stats: stats, phase: phase, now: now)
            } else {
                HomeHeroLoadingCard(failed: model.error != nil && !model.refreshing) {
                    Task { await model.refresh(event, app: app, force: true) }
                }
            }
            HomeQuickActions(showFind: true, event: event)
            if let stats {
                HomeStatTiles(stats: stats) { router.switchTab(.people) }
                if attention > 0 {
                    HomeAttentionCard(stats: stats, count: attention) { router.switchTab(.people) }
                        .transition(.opacity)
                }
            }
        }
        let secondary = VStack(spacing: 16) {
            if showContexts {
                HomeContextsCard(rows: progress)
            }
            if let arrivals {
                HomeArrivalsCard(summary: arrivals, tz: event.timezone ?? travel?.eventTimezone, onOpen: openTravel)
            }
            if let roster, phase != .upcoming {
                HomeRecentCheckInsCard(recent: DashboardLogic.recentCheckIns(roster.participants), now: now) { p in
                    router.openParticipant(eventId: event.id, participantEventId: p.participantEventId)
                } onSeeAll: {
                    router.switchTab(.people)
                }
            }
        }
        columns(primary, secondary)
    }

    // MARK: Limited roles (no roster)

    @ViewBuilder
    private func limitedCards(arrivals: ArrivalsSummary?) -> some View {
        let feed = model.scans.map { DashboardLogic.scanFeed($0, tz: event.timezone, now: now) }
        let primary = VStack(spacing: 16) {
            HomeLimitedAccessCard(event: event)
            if let feed {
                HomeScansHeroCard(feed: feed)
            } else {
                HomeHeroLoadingCard(failed: model.error != nil && !model.refreshing, title: "Loading scans…",
                                detail: "Fetching the latest scan activity for this event.") {
                    Task { await model.refresh(event, app: app, force: true) }
                }
            }
            HomeQuickActions(showFind: false, event: event)
        }
        let secondary = VStack(spacing: 16) {
            if let arrivals {
                HomeArrivalsCard(summary: arrivals, tz: event.timezone ?? travel?.eventTimezone, onOpen: openTravel)
            }
            if let feed, !feed.recent.isEmpty {
                HomeRecentScansCard(scans: feed.recent, travel: travel, now: now)
            }
        }
        columns(primary, secondary)
    }

    private var openTravel: (() -> Void)? {
        event.travelEnabled ? { router.switchTab(.travel) } : nil
    }

    /// One column on iPhone; two side by side on iPad and wide windows.
    @ViewBuilder
    private func columns(_ primary: some View, _ secondary: some View) -> some View {
        if wide {
            HStack(alignment: .top, spacing: 16) {
                primary.frame(maxWidth: .infinity)
                secondary.frame(maxWidth: .infinity)
            }
        } else {
            VStack(spacing: 16) {
                primary
                secondary
            }
        }
    }
}
