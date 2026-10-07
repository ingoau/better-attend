import BackgroundTasks
import Foundation
import Network
import UserNotifications
import WidgetKit

/// Hand-rolled dependency container: one per process, injected into SwiftUI with `.environment(app)`.
@MainActor
@Observable
final class AppModel {
    static let backgroundRefreshTask = "au.ingo.betterattend.refresh"

    let isDemo: Bool
    let settings: SettingsStore
    let api: AttendAPI
    let auth: AuthStore
    let events: EventRepository
    let participants: ParticipantRepository
    let scans: ScanRepository
    let tickets: TicketRepository
    let travel: TravelRepository
    let rollCalls: RollCallStore

    /// False while the network is unreachable (drives "offline" hints and queued-scan retries).
    private(set) var isOnline = true

    /// False when the cache's encryption key is unavailable: nothing is saved on this device, so the
    /// app works online only (and says so).
    var secureStorage: Bool { cache.isEncrypted }

    @ObservationIgnored let cache: JsonCache
    @ObservationIgnored private let pathMonitor = NWPathMonitor()
    @ObservationIgnored private var widgetPublish: Task<Void, Never>?
    @ObservationIgnored private var scanRetry: Task<Void, Never>?
    @ObservationIgnored private var scanRetryDelay: Duration = .seconds(15)
    @ObservationIgnored private var lastSnapshot: WidgetSnapshot?

    init(demo: Bool = false) {
        isDemo = demo
        let keychain = Keychain(service: demo ? "au.ingo.betterattend.demo" : "au.ingo.betterattend")
        let defaults = demo ? UserDefaults(suiteName: "au.ingo.betterattend.demo")! : .standard
        if demo {
            // Demo mode starts fresh every launch so screenshots and UI tests are deterministic.
            defaults.removePersistentDomain(forName: "au.ingo.betterattend.demo")
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try? FileManager.default.removeItem(at: base.appending(path: "demo_cache"))
        }
        let cache = demo ? JsonCache.ephemeral(namespace: "demo_cache") : JsonCache.standard(keychain: keychain)
        let tokenStore = SecureTokenStore(keychain: keychain)
        let api = AttendAPI(tokens: tokenStore, session: demo ? DemoBackend.makeSession() : nil)

        self.cache = cache
        self.settings = SettingsStore(defaults: defaults)
        self.api = api
        self.auth = AuthStore(store: tokenStore) { api }
        self.events = EventRepository(api: api, cache: cache, settings: settings)
        self.participants = ParticipantRepository(api: api, cache: cache)
        self.scans = ScanRepository(api: api, cache: cache, participants: participants)
        self.tickets = TicketRepository(api: api, cache: cache)
        self.travel = TravelRepository(api: api, cache: cache)
        self.rollCalls = RollCallStore(cache: cache, scans: scans, participants: participants)

        api.onSessionExpired = { [weak self] in self?.auth.sessionExpired() }
        auth.onSignedOut = { [weak self] in Task { await self?.clearAccountData() } }
        scans.fallbackContext = { [weak self] eventId in
            guard let self else { return nil }
            await events.loadContexts(eventId)
            let list = events.cachedContexts(eventId) ?? []
            return (list.first(where: \.checksIn) ?? list.first)?.id
        }
        scans.onQueued = { [weak self] in
            self?.scheduleScanRetry()
            if !demo { RejectionNotifier.requestPermissionIfNeeded() }
        }
        scans.onRejected = { rejected in if !demo { RejectionNotifier.notify(rejected) } }
        participants.onRosterChanges = { [weak self] eventId, changes, previousSyncAt in
            guard let self, !demo else { return }
            let eventName = events.events?.first { $0.id == eventId }?.name
            if settings.signupNotifications, RosterAlerts.isBaseline(since: settings.signupNotificationsSince, previousSyncAt: previousSyncAt) {
                AlertNotifier.notify(.signups, eventId: eventId, eventName: eventName, people: changes.signups)
            }
            if settings.withdrawalNotifications,
               RosterAlerts.isBaseline(since: settings.withdrawalNotificationsSince, previousSyncAt: previousSyncAt) {
                AlertNotifier.notify(.withdrawals, eventId: eventId, eventName: eventName, people: changes.withdrawals)
            }
        }
        travel.onRefreshed = { [weak self] eventId, calendar in
            guard let self, !demo, settings.arrivalNotifications, user != nil,
                  let event = events.selectedEvent, event.id == eventId, event.travelEnabled else { return }
            ArrivalReminders.reschedule(eventId: eventId, eventName: event.name, calendar: calendar, defaults: settings.store)
        }
        settings.onArrivalAlertsChange = { [weak self] in
            guard !demo else { return }
            Task { await self?.updateArrivalReminders() }
        }
        if !demo { UNUserNotificationCenter.current().delegate = NotificationRouter.shared }
        // Offline "wrong event" check: the code may be on another of this user's events.
        scans.otherRosters = { [weak self] eventId in
            guard let self else { return [:] }
            var found: [String: Roster] = [:]
            for e in events.events ?? [] where e.id != eventId {
                if let r = await participants.load(e.id) { found[e.name] = r }
            }
            return found
        }
        events.onChange = { [weak self] in
            self?.scheduleWidgetPublish()
            // The suggested event can change with the list, not only an explicit choice.
            if !demo { Task { await self?.updateArrivalReminders() } }
        }
        participants.onChange = { [weak self] in self?.scheduleWidgetPublish() }
        tickets.onChange = { [weak self] in self?.scheduleWidgetPublish() }
        travel.onChange = { [weak self] in self?.scheduleWidgetPublish() }

        if demo { DemoBackend.shared.signIn(auth) }
        startPathMonitor()
    }

    /// Loads the disk caches. Call once at launch.
    func loadCaches() async {
        await events.loadCache()
        await tickets.loadCache()
        await scans.loadQueue()
        if scans.hasQueuedWork { scheduleScanRetry(immediately: true) }
    }

    var user: User? { auth.currentUser }

    /// Organizer tools show for organizers, admins, and anyone with at least one event.
    var isOrganizer: Bool {
        guard let user else { return false }
        return user.isOrganizer || user.globalAdmin || !(events.events ?? []).isEmpty
    }

    /// Wipes every cached byte of account data (sign-out / session expiry).
    func clearAccountData() async {
        scanRetry?.cancel()
        // First: stops roll call scan work and waits out its disk writes, so nothing is saved after the wipe.
        await rollCalls.clear()
        await scans.clear()
        await participants.clear() // also clears the encrypted file cache
        events.clear()
        tickets.clear()
        travel.clear()
        settings.clearAccountData()
        if !isDemo {
            ArrivalReminders.cancelAll(defaults: settings.store)
            AlertNotifier.removeAll()
        }
        // Participant headshots (minors) may sit in the URL cache.
        URLCache.shared.removeAllCachedResponses()
        lastSnapshot = nil
        WidgetSnapshotStore.write(.signedOut)
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: Offline scan queue

    private func startPathMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.connectivityChanged(online) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "au.ingo.betterattend.path"))
    }

    private func connectivityChanged(_ online: Bool) {
        let cameBack = online && !isOnline
        isOnline = online
        if cameBack, scans.hasQueuedWork { scheduleScanRetry(immediately: true) }
    }

    /// Retries the queue with exponential backoff (15 s → 5 min) until it's empty.
    func scheduleScanRetry(immediately: Bool = false) {
        if immediately { scanRetryDelay = .seconds(15) }
        scanRetry?.cancel()
        scanRetry = Task { [weak self] in
            guard let self else { return }
            if !immediately { try? await Task.sleep(for: scanRetryDelay) }
            guard !Task.isCancelled, auth.currentUser != nil else { return }
            let left = await scans.flush()
            if left == 0 {
                scanRetryDelay = .seconds(15)
            } else if !Task.isCancelled {
                scanRetryDelay = min(scanRetryDelay * 2, .seconds(300))
                scheduleScanRetry()
            }
        }
    }

    // MARK: Widgets

    /// Rebuilds the widget snapshot shortly after data changes (bursts collapse into one publish).
    func scheduleWidgetPublish() {
        widgetPublish?.cancel()
        widgetPublish = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            await self?.publishWidgets()
        }
    }

    /// Writes the snapshot for the widget extension and reloads timelines if anything changed.
    func publishWidgets(force: Bool = false) async {
        let snap = await buildSnapshot()
        let changed = !snap.sameContent(as: lastSnapshot)
        lastSnapshot = snap
        if changed || force {
            WidgetSnapshotStore.write(snap)
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    private func buildSnapshot() async -> WidgetSnapshot {
        guard let user else { return .signedOut }
        let event = isOrganizer ? events.selectedEvent : nil
        if let event {
            if event.canViewParticipants { await participants.load(event.id) }
            await events.loadContexts(event.id)
            if event.travelEnabled { await travel.load(event.id) }
        }
        return WidgetSnapshots.build(
            user: user,
            event: event,
            roster: event.flatMap { participants.roster($0.id) },
            contexts: event.flatMap { events.cachedContexts($0.id) },
            travel: event.flatMap { travel.calendars[$0.id] },
            tickets: tickets.tickets,
            isOrganizer: isOrganizer
        )
    }

    // MARK: Pickup reminders

    /// Schedules pickup reminders from the cached calendar for the selected event, or cancels them when
    /// they're off or the event has no travel. Travel refreshes reschedule them as times change.
    func updateArrivalReminders() async {
        guard !isDemo else { return }
        let defaults = settings.store
        guard settings.arrivalNotifications, user != nil, events.events != nil else {
            // Before the events cache has loaded there's no telling which event is selected: leave them be.
            if !settings.arrivalNotifications || user == nil { ArrivalReminders.cancelAll(defaults: defaults) }
            return
        }
        guard let event = events.selectedEvent, event.travelEnabled else {
            ArrivalReminders.cancelAll(defaults: defaults)
            return
        }
        if ArrivalReminders.scheduledEvent(defaults) != event.id { ArrivalReminders.cancelAll(defaults: defaults) }
        guard let calendar = await travel.load(event.id) else { return }
        // Things may have moved on while the calendar loaded (sign-out, turned off, another event, or a later
        // call that finished first): schedule only if this is still what's wanted.
        guard settings.arrivalNotifications, user != nil, events.selectedEvent?.id == event.id else { return }
        ArrivalReminders.reschedule(eventId: event.id, eventName: event.name, calendar: calendar, defaults: defaults)
    }

    // MARK: Background refresh

    /// Asks iOS for a background refresh in ~15 minutes (widgets, signup notifications and the offline queue).
    func scheduleBackgroundRefresh() {
        guard user != nil else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.backgroundRefreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Budget-conscious refresh: the server allows 300 requests / 5 min per IP (shared by everyone on
    /// the venue Wi-Fi), so each run makes at most a few calls and skips anything refreshed very recently.
    func backgroundRefresh() async {
        scheduleBackgroundRefresh()
        guard let user else { return }
        await loadCaches()
        if scans.hasQueuedWork { await scans.flush() }
        if isOrganizer {
            if events.events?.isEmpty ?? true { _ = try? await events.refresh() }
            await updateArrivalReminders()
            if let event = events.selectedEvent {
                if EventPermissions.canViewParticipants(event) {
                    let roster = await participants.load(event.id)
                    let last = Time.parse(roster?.lastSyncAt)
                    if last.map({ Date().timeIntervalSince($0) > 45 }) ?? true { _ = try? await participants.sync(event.id) }
                }
                if event.travelEnabled { _ = try? await travel.refresh(event.id) }
            }
        }
        if user.isParticipant { _ = try? await tickets.refresh() }
        await publishWidgets(force: true)
    }
}
