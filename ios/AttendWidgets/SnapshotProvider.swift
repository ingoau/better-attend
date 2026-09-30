import WidgetKit

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

/// Timeline provider shared by every Attend widget. Reads the snapshot the app writes to the App
/// Group; never touches the network. The app reloads all timelines whenever the snapshot changes,
/// so the schedule here only keeps time-dependent labels fresh.
struct SnapshotProvider: TimelineProvider {
    enum Schedule {
        /// "Updated 5 min ago" / "in 10 min": re-render every 5 minutes, reload after 30.
        case organizer
        /// Countdown labels change at local midnight, an hour before doors, at doors and at the end.
        case ticket
        /// Nothing time-dependent.
        case none
    }

    var schedule: Schedule

    func placeholder(in context: Context) -> SnapshotEntry {
        let now = Date()
        return SnapshotEntry(date: now, snapshot: WidgetSamples.snapshot(relativeTo: now))
    }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        let now = Date()
        // The gallery shows realistic sample data; a placed widget shows the real thing.
        let snap = context.isPreview ? WidgetSamples.snapshot(relativeTo: now) : WidgetSnapshotStore.read()
        completion(SnapshotEntry(date: now, snapshot: snap))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let now = Date()
        let snap = WidgetSnapshotStore.read()
        let entry = { (date: Date) in SnapshotEntry(date: date, snapshot: snap) }

        let single = Timeline(entries: [entry(now)], policy: .never)
        guard snap.signedIn else { return completion(single) }
        switch schedule {
        case .organizer where snap.organizer != nil:
            let dates = WidgetSchedule.stepDates(now: now)
            completion(Timeline(entries: dates.map(entry), policy: .after(now.addingTimeInterval(WidgetSchedule.horizon))))
        case .ticket:
            guard let ticket = snap.ticket else { return completion(single) }
            let boundaries = WidgetSchedule.ticketBoundaries(ticket, now: now)
            completion(Timeline(entries: ([now] + boundaries).map(entry), policy: .atEnd))
        default:
            completion(single)
        }
    }
}
