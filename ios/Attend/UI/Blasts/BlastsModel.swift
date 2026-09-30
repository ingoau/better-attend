import Foundation

/// Announcements for one event: history, sending, and live delivery progress.
@MainActor
@Observable
final class BlastsModel {
    let eventId: String
    /// nil until the first load finishes.
    private(set) var blasts: [SlackBlast]?
    private(set) var loading = false
    private(set) var error: String?
    private(set) var sending = false
    var sendError: String?
    /// Bumped when polling should restart at its fast cadence (after a send or a refresh).
    private(set) var pollGeneration = 0

    /// Blasts we saw in flight, so we can buzz once when they finish.
    @ObservationIgnored private var watching: Set<String> = []

    /// Progress checks start every 2 s (a fresh blast delivers quickly) and back off to 15 s, for at
    /// most 10 minutes per round. The venue's staff share one per-IP rate limit (300 requests / 5 min),
    /// so a long send doesn't hammer it; sending or pulling to refresh starts a new round.
    static let firstPollInterval: Double = 2
    static let maxPollInterval: Double = 15
    static let pollRoundLimit: TimeInterval = 600

    init(eventId: String) {
        self.eventId = eventId
    }

    var hasActive: Bool { (blasts ?? []).contains(where: BlastLogic.isActive) }

    func refresh(api: AttendAPI) async {
        loading = true
        defer { loading = false }
        do {
            apply(try await api.slackBlasts(eventId: eventId))
            error = nil
            pollGeneration += 1
        } catch where !error.isCancellation {
            self.error = error.friendlyMessage
        } catch {}
    }

    /// Sends the composer's plain text (converted to HTML). Returns true once the server accepted it.
    func send(_ plain: String, api: AttendAPI) async -> Bool {
        guard !plain.isBlank, !sending else { return false }
        sending = true
        sendError = nil
        defer { sending = false }
        do {
            let blast = try await api.sendSlackBlast(eventId: eventId, message: BlastLogic.toHtml(plain))
            apply(BlastLogic.upsert(blasts ?? [], blast))
            pollGeneration += 1
            Haptics.confirm()
            return true
        } catch {
            sendError = error.friendlyMessage
            Haptics.reject()
            return false
        }
    }

    /// Polls in-flight blasts until they finish, the round's time is up, or the task is cancelled
    /// (the screen left, or the app went to the background).
    func pollWhileActive(api: AttendAPI) async {
        var interval = Self.firstPollInterval
        let started = Date()
        while hasActive, Date().timeIntervalSince(started) < Self.pollRoundLimit {
            try? await Task.sleep(for: .seconds(interval))
            if Task.isCancelled { return }
            await pollOnce(api: api)
            interval = min(interval * 1.3, Self.maxPollInterval)
        }
    }

    private func pollOnce(api: AttendAPI) async {
        let active = (blasts ?? []).filter(BlastLogic.isActive)
        guard !active.isEmpty else { return }
        if active.count == 1 {
            if let updated = try? await api.slackBlast(eventId: eventId, id: active[0].id) {
                apply(BlastLogic.upsert(blasts ?? [], updated))
            }
        } else if let list = try? await api.slackBlasts(eventId: eventId) {
            // Several in flight: one list request is cheaper than one per blast.
            apply(list)
        }
    }

    private func apply(_ list: [SlackBlast]) {
        let finished = list.filter { watching.contains($0.id) && !BlastLogic.isActive($0) }
        if finished.contains(where: { $0.status == "failed" || $0.failedCount > 0 && $0.sentCount == 0 }) {
            Haptics.reject()
        } else if !finished.isEmpty {
            Haptics.confirm()
        }
        watching = Set(list.filter(BlastLogic.isActive).map(\.id))
        blasts = list
    }
}
