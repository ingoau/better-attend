import Foundation

/// A note plus its optimistic-send state.
struct NoteItem: Hashable, Identifiable {
    var note: Note
    var pending = false
    var failed = false
    var id: String { note.id }
}

/// Where the NFC badge flow is. While `.writing`, the system NFC sheet is up.
enum BadgePhase: Equatable {
    case idle
    case preparing
    case writing
    case failed(String, retryable: Bool)
}

enum DetailBusy: Equatable {
    /// Checking in at this context (nil = the default one).
    case checkingIn(String?)
    /// Undoing this context (nil = everywhere).
    case undoing(String?)
    case updatingStatus
    case resettingBadge
    case savingEdits
    case removing
}

/// State and actions for one person's detail page. The person itself is read live from the roster
/// (so scans, undo and syncs show up instantly), overlaid with the last full detail fetch.
@MainActor
@Observable
final class ParticipantDetailModel {
    let eventId: String
    let participantEventId: String

    private(set) var detail: Participant?
    /// True once the full profile has arrived.
    private(set) var detailLoaded = false
    private(set) var loading = false
    /// True once a detail fetch has finished (successfully or not).
    private(set) var attempted = false
    private(set) var error: String?
    private(set) var notes: [NoteItem]?
    private(set) var notesError: String?
    /// The notes endpoint refused (403/404): hide the section rather than nag.
    private(set) var notesHidden = false
    private(set) var busy: DetailBusy?
    private(set) var badge: BadgePhase = .idle
    var toast: Toast?

    @ObservationIgnored private var writer: BadgeWriter?
    @ObservationIgnored private var loadTask: Task<Void, Never>?

    init(eventId: String, participantEventId: String) {
        self.eventId = eventId
        self.participantEventId = participantEventId
    }

    func participant(_ app: AppModel) -> Participant? {
        let live = app.participants.roster(eventId)?.participants.first { $0.participantEventId == participantEventId }
        return ParticipantDetailLogic.overlay(live: live, detail: detail)
    }

    func event(_ app: AppModel) -> Event? { app.events.events?.first { $0.id == eventId } }

    func contexts(_ app: AppModel) -> [ScanContext] { app.events.cachedContexts(eventId) ?? [] }

    // MARK: Loading

    func refresh(_ app: AppModel) async {
        loading = true
        async let notes: Void = loadNotes(app)
        async let contexts: Void = loadContexts(app)
        do {
            let full = try await app.participants.detail(eventId, participantEventId: participantEventId)
            detail = full
            detailLoaded = true
            error = nil
        } catch {
            if !error.isCancellation { self.error = error.friendlyMessage }
        }
        loading = false
        attempted = true
        _ = await (notes, contexts)
    }

    /// Starts the first load once. Unstructured, so view churn (tab switches, deep links) can't cancel it halfway.
    func start(_ app: AppModel) {
        guard loadTask == nil else { return }
        loadTask = Task { await refresh(app) }
    }

    private func loadContexts(_ app: AppModel) async {
        await app.participants.load(eventId)
        await app.events.loadContexts(eventId)
        _ = try? await app.events.refreshContexts(eventId)
    }

    func loadNotes(_ app: AppModel) async {
        do {
            let fetched = try await app.api.notes(eventId: eventId, participantEventId: participantEventId)
            let unsent = (notes ?? []).filter { $0.pending || $0.failed }
            notes = unsent + fetched.map { NoteItem(note: $0) }
            notesError = nil
            notesHidden = false
        } catch {
            guard !error.isCancellation else { return }
            let api = error as? APIError
            notesHidden = api?.isForbidden == true || api?.isNotFound == true
            notesError = notesHidden ? nil : error.friendlyMessage
        }
    }

    // MARK: Check-in

    func checkIn(_ app: AppModel, at context: ScanContext?) async {
        guard busy == nil else { return }
        busy = .checkingIn(context?.id)
        defer { busy = nil }
        let ctx: ScanContext? = if let context { context } else { await CheckInActions.defaultContext(app, eventId: eventId) }
        let outcome = await CheckInActions.checkIn(app, eventId: eventId, participantEventId: participantEventId, context: ctx)
        if let updated = outcome.participant, updated.participantEventId == participantEventId {
            detail = ParticipantDetailLogic.overlay(live: updated, detail: detail)
        }
        toast = CheckInActions.toast(for: outcome, name: participant(app)?.name ?? "them", context: ctx, tz: event(app)?.timezone)
    }

    /// `scanContextId` nil = undo every context.
    func undo(_ app: AppModel, scanContextId: String?) async {
        guard busy == nil else { return }
        busy = .undoing(scanContextId)
        defer { busy = nil }
        do {
            let res = try await CheckInActions.undo(app, eventId: eventId, participantEventId: participantEventId, scanContextId: scanContextId)
            // Keep the local copy in step for people who aren't in the roster (opened from a scan).
            if var d = detail {
                d.scansByContext = scanContextId.map { id in d.scansByContext.filter { $0.scanContextId != id } } ?? []
                let checkIns = Set(contexts(app).filter(\.checksIn).map(\.id))
                d.checkedInAt = d.scansByContext.filter { $0.checksIn || checkIns.contains($0.scanContextId) }
                    .compactMap(\.firstScannedAt).min { (Time.parse($0) ?? .distantFuture) < (Time.parse($1) ?? .distantFuture) }
                detail = d
            }
            Haptics.confirm()
            let place = scanContextId.flatMap { id in contexts(app).first { $0.id == id }?.name }
            let removed = res.deletedScans == 1 ? "Removed 1 scan" : "Removed \(res.deletedScans) scans"
            toast = Toast(message: removed + (place.map { " at \($0)" } ?? ""), systemImage: "arrow.uturn.backward.circle.fill", tone: .neutral)
        } catch {
            guard !error.isCancellation else { return }
            Haptics.reject()
            toast = .error("Couldn't undo: \(error.friendlyMessage)")
        }
    }

    // MARK: Status

    func setWithdrawn(_ app: AppModel, _ withdrawn: Bool) async {
        guard busy == nil else { return }
        busy = .updatingStatus
        defer { busy = nil }
        do {
            let updated = try await app.api.updateParticipantStatus(eventId: eventId, participantEventId: participantEventId,
                                                                    status: withdrawn ? "withdrawn" : "in_progress")
            await app.participants.upsert(eventId, updated)
            detail = ParticipantDetailLogic.overlay(live: updated, detail: detail)
            Haptics.confirm()
            toast = Toast(message: withdrawn ? "Marked as withdrawn" : "Reinstated",
                          systemImage: withdrawn ? "person.fill.xmark" : "person.fill.checkmark", tone: withdrawn ? .neutral : .success)
        } catch {
            guard !error.isCancellation else { return }
            Haptics.reject()
            toast = .error(error.friendlyMessage)
        }
    }

    // MARK: Edit details

    /// Saves profile edits (only the changed fields). Returns why it failed, for the form to show
    /// inline while it stays open, or nil once saved. Needs a connection: edits are never queued.
    func saveEdits(_ app: AppModel, _ edit: ParticipantEdit) async -> String? {
        guard !edit.isEmpty else { return nil }
        guard busy == nil else { return "Another change is still being saved. Try again in a moment." }
        guard app.isOnline else { return "You're offline. Editing details needs a connection." }
        busy = .savingEdits
        defer { busy = nil }
        do {
            let updated = try await app.api.updateParticipant(eventId: eventId, participantEventId: participantEventId, edit: edit)
            await app.participants.upsert(eventId, updated)
            // The PATCH answers with the short (roster) shape: show the edit on the old profile straight
            // away (without bringing back anything it cleared), then fetch the full profile. If that
            // fails, the edited copy stands until the next refresh.
            detail = ParticipantDetailLogic.applying(edit, live: updated, to: detail)
            if let full = try? await app.participants.detail(eventId, participantEventId: participantEventId) {
                detail = full
                detailLoaded = true
            }
            Haptics.confirm()
            toast = Toast(message: "Saved")
            return nil
        } catch {
            guard !error.isCancellation else { return "Not saved." }
            Haptics.reject()
            return error.friendlyMessage
        }
    }

    // MARK: Remove

    /// Deletes this registration. True once it's gone, so the page can close.
    func remove(_ app: AppModel) async -> Bool {
        guard busy == nil else { return false }
        busy = .removing
        defer { busy = nil }
        do {
            try await app.api.deleteParticipant(eventId: eventId, participantEventId: participantEventId)
        } catch let api as APIError where api.isNotFound {
            // Already removed (elsewhere, or a retried request): same outcome.
        } catch {
            guard !error.isCancellation else { return false }
            Haptics.reject()
            toast = .error("Couldn't remove: \(error.friendlyMessage)")
            return false
        }
        await app.participants.remove(eventId, participantEventId: participantEventId)
        Haptics.confirm()
        return true
    }

    // MARK: Notes

    /// Validates and sends a note optimistically. Returns an error message, or nil when it was accepted.
    func addNote(_ app: AppModel, content: String, type: String, sensitivity: String) -> String? {
        if let problem = NoteRules.validate(content, type: type, sensitivity: sensitivity) {
            Haptics.reject()
            return problem
        }
        let user = app.user
        let local = Note(id: "local-\(UUID().uuidString)", content: content.trimmingCharacters(in: .whitespacesAndNewlines),
                         noteType: type, sensitivity: sensitivity, createdAt: Time.nowISO(),
                         author: NoteAuthor(id: user?.id, name: user?.displayName, email: user?.email))
        notes = [NoteItem(note: local, pending: true)] + (notes ?? [])
        Task { await send(app, local) }
        return nil
    }

    func retryNote(_ app: AppModel, id: String) {
        guard let item = notes?.first(where: { $0.id == id }) else { return }
        update(id) { $0.pending = true; $0.failed = false }
        Task { await send(app, item.note) }
    }

    func discardNote(id: String) {
        notes?.removeAll { $0.id == id }
    }

    private func send(_ app: AppModel, _ local: Note) async {
        do {
            let saved = try await app.api.createNote(eventId: eventId, participantEventId: participantEventId, content: local.content,
                                                     noteType: local.noteType, sensitivity: local.sensitivity)
            notes = notes?.map { $0.id == local.id ? NoteItem(note: saved) : $0 }
            Haptics.confirm()
        } catch {
            update(local.id) { $0.pending = false; $0.failed = true }
            Haptics.reject()
            toast = .error("Note not saved: \(error.friendlyMessage)")
        }
    }

    private func update(_ id: String, _ change: (inout NoteItem) -> Void) {
        guard let i = notes?.firstIndex(where: { $0.id == id }) else { return }
        change(&notes![i])
    }

    // MARK: NFC badge

    /// ensure (get the token) → write + verify on the tag → confirm with Attend.
    func writeBadge(_ app: AppModel) async {
        guard BadgeWriter.isAvailable, badge == .idle || badge.isFailure else { return }
        badge = .preparing
        let token: String
        do {
            token = try await app.api.nfcEnsure(eventId: eventId, participantEventId: participantEventId).badgeToken
        } catch {
            guard !error.isCancellation else { badge = .idle; return }
            let api = error as? APIError
            let message = api?.isUnprocessable == true ? (api?.message.nonBlank ?? "NFC badges aren't enabled for this event.") : error.friendlyMessage
            badge = .failed(message, retryable: api?.isUnprocessable != true)
            Haptics.reject()
            return
        }
        let p = participant(app)
        badge = .writing
        let writer = BadgeWriter(
            token: token,
            slackUserId: p?.slackUserId,
            name: p?.name ?? "this person",
            confirm: { [weak self] in await self?.confirmBadge(app, token: token) },
            finish: { [weak self] outcome in self?.badgeFinished(outcome) }
        )
        self.writer = writer
        writer.begin()
    }

    private func confirmBadge(_ app: AppModel, token: String) async -> String? {
        do {
            let confirmed = try await app.api.nfcConfirm(eventId: eventId, participantEventId: participantEventId, badgeToken: token)
            if var p = participant(app) {
                p.nfcBadgeToken = confirmed.badgeToken
                p.nfcBadgeAssigned = true
                await app.participants.upsert(eventId, p)
                detail = ParticipantDetailLogic.overlay(live: p, detail: detail)
            }
            return nil
        } catch {
            return error.friendlyMessage
        }
    }

    private func badgeFinished(_ outcome: BadgeWriter.Outcome) {
        writer = nil
        switch outcome {
        case .written:
            badge = .idle
            Haptics.confirm()
            toast = Toast(message: "Badge ready", systemImage: "wave.3.right.circle.fill")
        case .cancelled:
            badge = .idle
        case let .failed(message, retryable):
            badge = .failed(message, retryable: retryable)
            Haptics.reject()
        }
    }

    func dismissBadgeError() { badge = .idle }

    func resetBadge(_ app: AppModel) async {
        guard busy == nil else { return }
        busy = .resettingBadge
        defer { busy = nil }
        do {
            let res = try await app.api.nfcReset(eventId: eventId, participantEventId: participantEventId)
            if var p = participant(app) {
                p.nfcBadgeToken = res.badgeToken
                p.nfcBadgeAssigned = false
                await app.participants.upsert(eventId, p)
                detail = ParticipantDetailLogic.overlay(live: p, detail: detail)
            }
            Haptics.confirm()
            toast = Toast(message: "Badge reset. The old badge no longer works.", systemImage: "arrow.counterclockwise.circle.fill", tone: .neutral)
        } catch {
            guard !error.isCancellation else { return }
            Haptics.reject()
            let api = error as? APIError
            toast = .error(api?.isUnprocessable == true ? (api?.message.nonBlank ?? "NFC badges aren't enabled for this event.") : error.friendlyMessage)
        }
    }
}

extension BadgePhase {
    var isFailure: Bool { if case .failed = self { true } else { false } }
    var isWorking: Bool { self == .preparing || self == .writing }
}
