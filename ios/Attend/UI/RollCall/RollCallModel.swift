import Foundation

/// Screen state for a roll call: the search, filter and sheets. The session itself, and any scans
/// its ticks record, live in `app.rollCalls` (persisted, and outliving this screen).
@MainActor
@Observable
final class RollCallModel {
    var query = ""
    var filter: RollCallFilter = .missing
    var toast: Toast?
    var showAdd = false
    var confirmFinish = false
    var showSummary = false

    func toggle(_ app: AppModel, event: Event, _ entry: RollCallEntry) {
        set(app, event: event, entry.participant, accounted: !entry.accounted)
    }

    /// "Add someone": marks a person present, adding them to the list if they weren't expected.
    func add(_ app: AppModel, event: Event, _ p: Participant) {
        set(app, event: event, p, accounted: true)
        toast = Toast(message: "\(p.name) is accounted for")
    }

    /// Ticks or unticks someone on this phone straight away; a scan (or its undo) follows in the background.
    func set(_ app: AppModel, event: Event, _ p: Participant, accounted: Bool) {
        guard app.rollCalls.set(event.id, p, accounted: accounted) else { return }
        if accounted { Haptics.impact() } else { Haptics.tap() }
    }

    /// Shows what the background scan work had to say about this event's roll call.
    func show(_ notice: RollCallNotice?, eventId: String, app: AppModel) {
        guard let notice, notice.eventId == eventId else { return }
        toast = notice.isError ? .error(notice.message) : .info(notice.message, systemImage: "mappin.and.ellipse")
        app.rollCalls.clearNotice(notice.id)
    }

    /// Ends the roll call: forgets the session. Scans already recorded stay recorded.
    func end(_ app: AppModel, eventId: String) {
        app.rollCalls.end(eventId)
        query = ""
        filter = .missing
        Haptics.confirm()
    }
}
