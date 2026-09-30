import Foundation

/// The find-person sheet's state.
struct ScanSearchState: Hashable {
    var query = ""
    var results: [Participant] = []
    var remoteLoading = false
    var remoteError: String?
    /// Set when the query is itself a scannable id / QR payload.
    var directInput: ScanInput?
    var canSearch = true
    var rosterEmpty = false
}

/// A one-off message for the scanner's toast.
struct ScanToast: Hashable, Identifiable {
    var id = UUID()
    var text: String
}

/// Drives the scanner and the kiosk (port of Android's `ScanViewModel`): event + checkpoint
/// selection, the same-code gate, submitting scans with an instant "Checking…" card, undo, the
/// offline queue and the find-person search.
@MainActor
@Observable
final class ScanModel {
    @ObservationIgnored let app: AppModel
    /// Kiosk: always this event instead of the app-wide selected one.
    @ObservationIgnored let fixedEventId: String?
    /// Kiosk: always this checkpoint.
    @ObservationIgnored let lockedContextId: String?
    @ObservationIgnored private let gate = SameCodeGate()
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var startedEventId: String?
    @ObservationIgnored private var refreshedContexts: Set<String> = []

    /// Plays the sound + haptic for a final scan outcome. Replaceable for tests.
    @ObservationIgnored var playFeedback: (FeedbackKind) -> Void
    /// Haptic for staff actions that aren't scans (undo, "Sync now"): true = it worked.
    @ObservationIgnored var actionFeedback: (Bool) -> Void = { ok in ok ? Haptics.confirm() : Haptics.reject() }

    private(set) var card: ScanCard?
    private(set) var inFlight = 0
    private(set) var syncing = false
    private(set) var contextErrors: [String: String] = [:]
    private(set) var search = ScanSearchState()
    var toast: ScanToast?

    init(app: AppModel, fixedEventId: String? = nil, lockedContextId: String? = nil) {
        self.app = app
        self.fixedEventId = fixedEventId
        self.lockedContextId = lockedContextId
        playFeedback = { [weak app] kind in
            guard let app else { return }
            ScanFeedbackPlayer.shared.play(kind, sound: app.settings.sounds, haptic: app.settings.haptics)
        }
    }

    // MARK: Derived state

    var event: Event? {
        if let fixedEventId { return app.events.events?.first { $0.id == fixedEventId } }
        return app.events.selectedEvent
    }

    /// True until the events list has been read from cache at least once.
    var eventsLoading: Bool { app.events.events == nil }

    /// nil while loading with nothing cached.
    var contexts: [ScanContext]? {
        event.flatMap { app.events.cachedContexts($0.id) }.map { $0.sorted { $0.position < $1.position } }
    }

    var contextsError: String? { event.flatMap { contextErrors[$0.id] } }

    var selectedContextId: String? {
        guard let e = event, let list = contexts, !list.isEmpty else { return nil }
        if let lockedContextId { return list.first { $0.id == lockedContextId }?.id }
        return list.first { $0.id == app.settings.selectedContexts[e.id] }?.id ?? EventLogic.defaultContext(list)?.id
    }

    var selectedContext: ScanContext? { contexts?.first { $0.id == selectedContextId } }

    /// Scans can be submitted: we know the event and have checkpoints (or know they failed to load).
    var ready: Bool { event != nil && (contexts != nil || contextsError != nil) }

    var pendingCount: Int { app.scans.pending.count }

    var canOpenDetails: Bool { event?.canViewParticipants == true }

    // MARK: Lifecycle

    /// Call whenever the screen appears or the event changes: resets per-event state and loads
    /// checkpoints and the roster (for instant names while checking and offline search).
    func start() async {
        guard let e = event else { return }
        if startedEventId != e.id {
            startedEventId = e.id
            gate.reset()
            card = nil
            search = ScanSearchState()
        }
        if !app.scans.pending.isEmpty { syncNow(manual: false) }
        await app.events.loadContexts(e.id)
        async let contexts: Void = refreshContextsIfNeeded(e.id)
        async let roster: Void = syncRoster(e)
        _ = await (contexts, roster)
    }

    private func syncRoster(_ e: Event) async {
        let roster = await app.participants.load(e.id)
        guard e.canViewParticipants else { return }
        // Other screens sync too; skip if it's fresh (the venue Wi-Fi shares one rate limit).
        if let last = Time.parse(roster?.lastSyncAt), Date().timeIntervalSince(last) < 60 { return }
        _ = try? await app.participants.sync(e.id)
    }

    private func refreshContextsIfNeeded(_ eventId: String) async {
        if !refreshedContexts.contains(eventId) { await refreshContexts(eventId) }
    }

    func refreshContexts(_ eventId: String? = nil) async {
        guard let id = eventId ?? event?.id else { return }
        do {
            _ = try await app.events.refreshContexts(id)
            contextErrors[id] = nil
            refreshedContexts.insert(id)
        } catch where !error.isCancellation {
            contextErrors[id] = error.friendlyMessage
        } catch {}
    }

    func selectContext(_ id: String) {
        guard let e = event, lockedContextId == nil, id != selectedContextId else { return }
        gate.reset()
        app.settings.setSelectedContext(eventId: e.id, contextId: id)
    }

    // MARK: Inputs

    /// Every decoded QR value from a camera frame. Duplicates are gated.
    func onCameraCodes(_ values: [String]) {
        guard ready else { return }
        for raw in values {
            let key = "qr:\(raw)"
            guard gate.offer(key) else { continue }
            if let input = ScanCode.parse(raw, source: "qr") {
                submit(input, gateKey: key)
            } else {
                card = .notAttendCode(key: newKey(), gateKey: key)
                playFeedback(.reject)
            }
        }
    }

    func onNfc(_ result: NfcParseResult) {
        guard event != nil else { return }
        switch result {
        case .input(let input):
            let key = "nfc:\(input.badgeToken ?? input.participantId ?? "")"
            guard ready, gate.offer(key) else { return }
            submit(input, gateKey: key)
        case .unrecognised(let message):
            guard gate.offer("nfc-error:\(message)") else { return }
            card = .notAttendBadge(key: newKey(), message: message)
            playFeedback(.reject)
        }
    }

    /// Manual check-in from the find-person sheet; bypasses the gate (a deliberate tap).
    func checkInManually(participantEventId: String) {
        submit(ScanInput(participantId: participantEventId, source: "manual"), gateKey: nil)
    }

    func submitDirect(_ input: ScanInput) {
        var i = input
        i.source = "manual"
        submit(i, gateKey: nil)
    }

    func retry(_ card: ScanCard) {
        if let input = card.input { submit(input, gateKey: card.gateKey) }
        if card.contextId == nil { Task { await refreshContexts() } }
    }

    private func submit(_ input: ScanInput, gateKey: String?) {
        guard let e = event else { return }
        guard ready else {
            card = .stillLoading(key: newKey(), input: input)
            return
        }
        let ctx = selectedContext
        let key = newKey()
        let cached = app.participants.roster(e.id)?.find(input.badgeToken ?? input.participantId ?? "")
        card = ScanCard(key: key, kind: .checking, title: "Checking…", participant: cached, contextName: ctx?.name, contextId: ctx?.id,
                        gateKey: gateKey, input: input)
        inFlight += 1
        Task {
            defer { inFlight -= 1 }
            let outcome = await app.scans.submit(eventId: e.id, input: input, scanContextId: ctx?.id, scanContextName: ctx?.name)
            let result = outcome.card(key: key, context: ctx, tz: e.timezone, gateKey: gateKey, input: input)
            if let kind = result.kind.feedback { playFeedback(kind) }
            if card?.key == key { card = result }
            if result.kind == .rejected, result.message?.localizedCaseInsensitiveContains("context") == true {
                await refreshContexts(e.id)
            }
        }
    }

    /// Dismiss the card and let the same code scan again straight away.
    func dismiss() {
        if let gateKey = card?.gateKey { gate.release(gateKey) }
        card = nil
    }

    /// Clears the card without releasing the gate (kiosk auto-hide: the ticket may still be in frame).
    func hide(_ key: String) {
        if card?.key == key { card = nil }
    }

    func undo(_ target: ScanCard) async {
        guard let e = event, let peid = target.participant?.participantEventId, let ctxId = target.contextId else { return }
        if card?.key == target.key { card?.busy = true }
        do {
            _ = try await app.scans.undo(eventId: e.id, participantEventId: peid, scanContextId: ctxId)
            await app.participants.applyUndo(e.id, participantEventId: peid, scanContextId: ctxId, contexts: contexts ?? [])
            actionFeedback(true)
            if let gateKey = target.gateKey { gate.release(gateKey) }
            if card?.key == target.key {
                card?.kind = .undone
                card?.title = "Scan undone"
                card?.message = "\(target.participant?.name ?? "They") is no longer marked as scanned here"
                card?.canUndo = false
                card?.retryable = false
                card?.busy = false
            }
        } catch {
            actionFeedback(false)
            if card?.key == target.key { card?.busy = false }
            if !error.isCancellation { toast = ScanToast(text: "Couldn't undo: \(error.friendlyMessage)") }
        }
    }

    // MARK: Offline queue

    /// - Parameter manual: the user tapped "Sync now" (gets a success/failure cue); false for quiet retries.
    func syncNow(manual: Bool = true) {
        guard !syncing else { return }
        syncing = true
        Task {
            defer { syncing = false }
            let left = await app.scans.flush()
            if manual { actionFeedback(left == 0) }
            if left > 0, manual {
                toast = ScanToast(text: "Still offline. \(left) \(left == 1 ? "scan is" : "scans are") waiting to sync.")
            }
        }
    }

    func discard(_ clientScanId: String) async {
        await app.scans.discardPending(clientScanId)
    }

    // MARK: Find person

    func setQuery(_ q: String) {
        let e = event
        let canSearch = e?.canViewParticipants != false
        let roster = e.flatMap { app.participants.roster($0.id) }?.participants ?? []
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        search = ScanSearchState(
            query: q,
            results: canSearch ? RosterSearch.filter(roster, query: q) : [],
            remoteLoading: canSearch && trimmed.count >= 2,
            directInput: RosterSearch.directInput(q),
            canSearch: canSearch,
            rosterEmpty: roster.isEmpty
        )
        searchTask?.cancel()
        guard let e, canSearch, trimmed.count >= 2 else { return }
        searchTask = Task {
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            do {
                let found = try await app.participants.search(e.id, query: trimmed)
                guard !Task.isCancelled, search.query == q else { return }
                let known = Set(search.results.map(\.participantEventId))
                search.results += found.filter { !known.contains($0.participantEventId) }
                search.remoteLoading = false
                search.remoteError = nil
            } catch {
                guard !Task.isCancelled, !error.isCancellation, search.query == q else { return }
                search.remoteLoading = false
                search.remoteError = error.friendlyMessage
            }
        }
    }

    private func newKey() -> String { UUID().uuidString }
}
