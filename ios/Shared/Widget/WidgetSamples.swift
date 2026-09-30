import Foundation

/// Illustrative data for the widget gallery, placeholders, previews and tests (no real people).
/// A port of Android's `WidgetSamples`. "Now" is Saturday 3 Oct 2026, 11:30 in Sydney.
enum WidgetSamples {
    static let now = Time.parse("2026-10-03T01:30:00Z")!

    static let organizer = OrganizerWidgetData(
        eventId: "sample", eventName: "Campfire Sydney", timezone: "Australia/Sydney",
        hasCounts: true, checkedIn: 84, expected: 120, notArrived: 36, lastHour: 12,
        contexts: [
            ContextCount(id: "c1", name: "Check-in desk", count: 84, checksIn: true),
            ContextCount(id: "c2", name: "Airport pickup", count: 23, isTravel: true),
            ContextCount(id: "c3", name: "Saturday lunch", count: 71),
            ContextCount(id: "c4", name: "Swag table", count: 40),
        ],
        updatedAt: "2026-10-03T01:26:00Z",
        travelEnabled: true,
        travel: TravelWidgetData(
            awaitingPickup: 7, collected: 12, checkedIn: 18, total: 41,
            nextArrivalName: "Sam", nextArrivalAt: "2026-10-03T11:40:00+10:00",
            nextArrivalRoute: "MEL → SYD", nextArrivalReference: "QF401"
        )
    )

    static let ticket = TicketWidgetData(
        id: "sample", eventName: "Campfire Sydney", startsAt: "2026-10-05T22:00:00Z", endsAt: "2026-10-07T06:00:00Z",
        timezone: "Australia/Sydney", city: "Sydney", confirmed: true, shortCode: "A1B2C3D4", statusLabel: "Ready"
    )

    static let snapshot = WidgetSnapshot(
        signedIn: true, organizer: organizer, ticket: ticket, isParticipant: true, builtAt: Time.iso(now)
    )

    /// A sample snapshot moved in time so that `date` plays the role of `now`: the widget gallery
    /// shows "Updated 4 min ago", "in 10 min" and "in 3 days" whatever the real date is.
    static func snapshot(relativeTo date: Date, from base: WidgetSnapshot = snapshot) -> WidgetSnapshot {
        let delta = date.timeIntervalSince(now)
        func shift(_ iso: String?) -> String? { Time.parse(iso).map { Time.iso($0.addingTimeInterval(delta)) } }
        var s = base
        s.builtAt = shift(base.builtAt)
        s.organizer?.updatedAt = shift(base.organizer?.updatedAt)
        s.organizer?.travel?.nextArrivalAt = shift(base.organizer?.travel?.nextArrivalAt)
        s.ticket?.startsAt = shift(base.ticket?.startsAt)
        s.ticket?.endsAt = shift(base.ticket?.endsAt)
        return s
    }

    // MARK: States (gallery-quality renders of every empty / edge state)

    /// People are expected but nobody has checked in yet.
    static var beforeCheckIn: WidgetSnapshot {
        var s = snapshot
        s.organizer?.checkedIn = 0
        s.organizer?.notArrived = 120
        s.organizer?.lastHour = 0
        s.organizer?.contexts = organizer.contexts.map { var c = $0; c.count = 0; return c }
        return s
    }

    /// Everyone expected has arrived.
    static var everyoneHere: WidgetSnapshot {
        var s = snapshot
        s.organizer?.checkedIn = 120
        s.organizer?.notArrived = 0
        return s
    }

    /// Signed in, but the roster hasn't synced (or the role can't read it).
    static var noCounts: WidgetSnapshot {
        var s = snapshot
        s.organizer?.hasCounts = false
        return s
    }

    /// Signed in as a participant with no organizer event selected.
    static var noEvent: WidgetSnapshot {
        var s = snapshot
        s.organizer = nil
        return s
    }

    /// The selected event doesn't run travel.
    static var noTravel: WidgetSnapshot {
        var s = snapshot
        s.organizer?.travelEnabled = false
        s.organizer?.travel = nil
        return s
    }

    /// No more inbound arrivals.
    static var noArrivals: WidgetSnapshot {
        var s = snapshot
        s.organizer?.travel?.nextArrivalAt = nil
        s.organizer?.travel?.nextArrivalName = nil
        return s
    }

    /// The next arrival is an unaccompanied minor.
    static var minorArrival: WidgetSnapshot {
        var s = snapshot
        s.organizer?.travel?.nextArrivalName = "Leo"
        s.organizer?.travel?.nextArrivalMinor = true
        return s
    }

    static var noTicket: WidgetSnapshot {
        var s = snapshot
        s.ticket = nil
        return s
    }

    static var noTicketNotParticipant: WidgetSnapshot {
        var s = noTicket
        s.isParticipant = false
        return s
    }

    /// A ticket that still needs something before it's valid.
    static var ticketIncomplete: WidgetSnapshot {
        var s = snapshot
        s.ticket?.confirmed = false
        s.ticket?.statusLabel = "Waiting on guardian"
        return s
    }

    /// The event is on right now.
    static var ticketLive: WidgetSnapshot {
        var s = snapshot
        s.ticket?.startsAt = "2026-10-02T22:00:00Z"
        s.ticket?.endsAt = "2026-10-04T06:00:00Z"
        return s
    }

    /// Doors open in under an hour (the countdown ticks).
    static var ticketSoon: WidgetSnapshot {
        var s = snapshot
        s.ticket?.startsAt = "2026-10-03T02:05:00Z"
        s.ticket?.endsAt = "2026-10-04T06:00:00Z"
        return s
    }
}
