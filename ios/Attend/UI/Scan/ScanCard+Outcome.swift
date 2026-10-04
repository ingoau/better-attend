import Foundation

extension ScanOutcome {
    var kind: ResultKind {
        switch self {
        case .scanned: .scanned
        case .alreadyScanned: .alreadyScanned
        case .queued: .savedOffline
        case .failed: .rejected
        case .rejected(_, let reason, _, _, _, _, _): reason == .alreadyCheckedIn ? .alreadyScanned : .rejected
        }
    }

    /// Short label for the recent-scans list.
    var label: String {
        switch self {
        case .scanned: "Scanned"
        case .alreadyScanned: "Already scanned"
        case .queued: "Saved offline"
        case .failed(_, let message, _, let notFound, _): notFound ? "Not registered" : message
        case .rejected(_, let reason, _, _, let offline, _, _): reason.title + (offline ? " (offline check)" : "")
        }
    }

    /// Who the log line is about, even when offline with no roster match.
    var displayName: String {
        if let p = participant { return p.name }
        if case .queued(_, let pending, _, _, _) = self { return pending.displayName }
        return "Unknown attendee"
    }

    /// Maps a repository outcome to the card we show (port of Android's `ScanOutcome.toCard`).
    func card(key: String, context: ScanContext?, tz: String?, gateKey: String?, input: ScanInput?) -> ScanCard {
        let base = ScanCard(key: key, kind: .checking, title: "", participant: participant, contextName: context?.name,
                            contextId: context?.id, gateKey: gateKey, input: input)
        switch self {
        case .scanned(_, let result, let p):
            var c = base
            let ctx = result.scanContext
            c.kind = .scanned
            c.title = "Scanned"
            c.message = (ctx?.checksIn ?? context?.checksIn) == true ? "Checked in" : nil
            c.contextName = ctx?.name ?? context?.name
            c.contextId = ctx?.id ?? context?.id
            c.canUndo = (p?.participantEventId ?? result.scan?.participantEventId) != nil && c.contextId != nil && !result.deduplicated
            return c
        case .alreadyScanned(_, let result, _):
            var c = base
            c.kind = .alreadyScanned
            c.title = "Already scanned"
            c.message = result.firstScannedAt.map { "First scanned at \(ScanLogic.firstScanLabel($0, tz: tz))" }
            c.contextName = result.scanContext?.name ?? context?.name
            c.contextId = result.scanContext?.id ?? context?.id
            return c
        case .queued(_, _, _, _, let rosterAt):
            var c = base
            c.kind = .savedOffline
            c.title = "Saved offline"
            c.message = "Offline, will confirm later"
            c.rosterNote = ScanAdmission.rosterAgeLabel(rosterAt) ?? "No roster on this phone to check against"
            c.rosterStale = ScanAdmission.isRosterStale(rosterAt)
            return c
        case .failed(_, let message, _, let notFound, _):
            var c = base
            c.kind = .rejected
            c.title = notFound ? "Not registered" : "Couldn't scan"
            c.message = notFound ? "No registration for this event matches that code." : message
            c.retryable = !notFound
            return c
        case let .rejected(_, reason, p, detail, offline, reverted, rosterAt):
            var c = base
            c.title = reason.title
            if reason == .alreadyCheckedIn {
                c.kind = .alreadyScanned
                c.message = detail.map { "First scanned at \(ScanLogic.firstScanLabel($0, tz: tz))" }
                c.rosterNote = ScanAdmission.rosterAgeLabel(rosterAt).map { "Offline · \($0)" }
            } else {
                c.kind = .rejected
                c.message = [reason.message(name: p?.name, detail: detail), reverted ? "Scan removed." : nil]
                    .compactMap { $0 }.joined(separator: " ")
                // Offline the roster might be out of date: let staff try again once they're back online.
                c.retryable = offline
                c.rosterNote = offline ? "Offline, not saved · " + (ScanAdmission.rosterAgeLabel(rosterAt) ?? "no roster") : nil
            }
            c.rosterStale = offline && ScanAdmission.isRosterStale(rosterAt)
            return c
        }
    }
}
