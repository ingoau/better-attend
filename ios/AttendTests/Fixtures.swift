import Foundation
@testable import Attend

/// Fixed-time fake data for unit tests (a port of the Android `SampleData`).
/// "Now" is Saturday 3 Oct 2026, 11:30 in Sydney.
enum Fixtures {
    static let now = Time.parse("2026-10-03T01:30:00Z")!
    static func iso(_ minutesFromNow: Double) -> String { Time.iso(now.addingTimeInterval(minutesFromNow * 60)) }

    static let event = Event(
        id: "0f5d1a64-2a0e-4d0c-8a3b-1e4c9a7b3c21", name: "Campfire Sydney", slug: "campfire-sydney",
        startsAt: "2026-10-02T22:00:00Z", endsAt: "2026-10-04T06:00:00Z", timezone: "Australia/Sydney",
        locationCity: "Sydney", role: "event_admin", canViewParticipantPii: true, canViewParticipants: true,
        canViewSensitiveData: true, travelEnabled: true
    )

    static let events = [
        event,
        Event(id: "e2", name: "Scrapyard Melbourne", slug: "scrapyard-mel", startsAt: "2026-11-14T22:00:00Z", endsAt: "2026-11-15T08:00:00Z",
              timezone: "Australia/Melbourne", locationCity: "Melbourne", role: "ops"),
        Event(id: "e3", name: "Counterspell Brisbane", slug: "counterspell-bne", startsAt: "2026-06-01T22:00:00Z", endsAt: "2026-06-02T08:00:00Z",
              timezone: "Australia/Brisbane", locationCity: "Brisbane", role: "ops"),
    ]

    static let user = User(id: "u1", name: "Orpheus Dino", email: "orpheus@hackclub.com", globalAdmin: false, isOrganizer: true, isParticipant: true)

    static let contexts = [
        ScanContext(id: "c1", name: "Check-in desk", checksIn: true, position: 0),
        ScanContext(id: "c2", name: "Airport pickup", isTravelPickup: true, isAirport: true, position: 1),
        ScanContext(id: "c3", name: "Saturday lunch", position: 2, startsAt: "2026-10-04T12:00:00+10:00", endsAt: "2026-10-04T13:30:00+10:00"),
    ]

    private static let names: [(String, String?)] = [
        ("Sam Lee", "she/her"), ("Arjun Patel", "he/him"), ("Maya Chen", "she/her"), ("Leo Nguyen", "he/him"),
        ("Priya Sharma", "she/her"), ("Noah Williams", "he/him"), ("Zara Ahmed", nil), ("Kai Tanaka", "they/them"),
        ("Ella Brown", "she/her"), ("Oliver Smith", "he/him"), ("Isla Wilson", "she/her"), ("Jack Taylor", "he/him"),
    ]

    static let participants: [Participant] = names.enumerated().map { i, pair in
        let (name, pronouns) = pair
        let checkedIn = i % 3 != 0
        let first = String(name.split(separator: " ")[0])
        var p = Participant(
            participantId: "a1b2c3d\(i)-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            participantEventId: "3b1f9a2\(i)-8d7e-4f60-9a1b-2c3d4e5f6a7b",
            displayName: first, fullName: name, email: "\(first.lowercased())@example.com", phone: "+6140000000\(i)",
            slackUserId: "U0\(i)", pronouns: pronouns,
            status: i == 11 ? "awaiting_guardian" : i == 10 ? "withdrawn" : "complete",
            checkedInAt: checkedIn ? iso(-15 * Double(i)) : nil,
            nfcBadgeToken: "e4b1-\(i)", nfcBadgeAssigned: i % 2 == 0,
            hasAnaphylaxisRisk: i == 2, requiresRefrigeration: i == 2, highSupportFlag: i == 5,
            waiverSigned: i != 11, allergies: i == 2 ? "Peanuts (anaphylaxis)" : nil,
            dietType: i % 4 == 0 ? "vegetarian" : "omnivore",
            scansByContext: checkedIn ? [ContextScanSummary(scanContextId: "c1", scanContextName: "Check-in desk", checksIn: true, scanCount: 1,
                                                           firstScannedAt: iso(-15 * Double(i)), lastScannedAt: iso(-15 * Double(i)))] : []
        )
        if i < 6 {
            p.travelInbound = Travel(direction: "inbound", mode: "plane", carrier: "Qantas", flightNumber: "QF40\(i)",
                                     departureCity: "Melbourne", arrivalCity: "Sydney", arrivalTime: iso(60 * Double(i)),
                                     legs: [TravelLeg(flightCode: "QF40\(i)", departureAirport: "MEL", arrivalAirport: "SYD",
                                                      arrivalTime: iso(60 * Double(i)), liveStatus: i < 2 ? "Landed" : "Scheduled")])
        }
        return p
    }

    static let ticketEvent = TicketEvent(
        id: event.id, name: event.name, slug: event.slug, startsAt: event.startsAt, endsAt: event.endsAt, timezone: event.timezone,
        locationCity: "Sydney", locationAddress: "1 Example St, Sydney NSW", locationCountry: "AU",
        locationLatitude: -33.8688, locationLongitude: 151.2093
    )

    static let ticket = Ticket(
        id: "3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b", participantId: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", status: "complete",
        displayStatus: "Complete", confirmed: true, checkedIn: false, attendeeName: "Sam",
        qrPayload: "attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", shortCode: "A1B2C3D4",
        onboardingUrl: "https://attend.hackclub.com/onboarding", event: ticketEvent
    )

    static var tickets: [Ticket] {
        var t2 = ticket
        t2.id = "t2"
        t2.confirmed = false
        t2.status = "in_progress"
        t2.displayStatus = "Awaiting Parent"
        t2.event.id = "e2"
        t2.event.name = "Scrapyard Melbourne"
        t2.event.startsAt = "2026-11-14T22:00:00Z"
        t2.event.locationCity = "Melbourne"
        return [ticket, t2]
    }

    static let travel: TravelCalendar = {
        let entries = participants.prefix(6).enumerated().map { i, p in
            TravelEntry(
                id: "tr\(i)", participantId: p.participantId, participantEventId: p.participantEventId,
                participantName: p.fullName, participantPreferredName: p.displayName,
                direction: i < 4 ? "inbound" : "outbound", mode: i == 3 ? "train" : "plane",
                primaryTimeAt: iso(45 * Double(i)), agendaDate: i < 4 ? "2026-10-03" : "2026-10-05",
                route: i == 3 ? "Central → Venue" : "MEL → SYD", reference: "QF40\(i)",
                pickupState: [0: "collected", 1: "checked_in", 2: "awaiting_pickup", 3: "awaiting_pickup"][i],
                isUnaccompaniedMinor: i == 2, groups: i == 1 ? [ParticipantGroup(id: "g1", name: "Team Blue", color: "#3b82f6")] : []
            )
        }
        return TravelCalendar(eventTimezone: "Australia/Sydney", dates: ["2026-10-03", "2026-10-05"], entries: Array(entries),
                              counts: TravelCounts(total: 6, inbound: 4, outbound: 2, scheduled: 6, awaitingPickup: 2, collected: 1, checkedIn: 1))
    }()

    static let blasts = [
        SlackBlast(id: "b1", message: "Lunch is ready in the atrium! 🌮", status: "completed", recipientCount: 120, sentCount: 118, failedCount: 2,
                   createdAt: iso(-40), sentBy: "Orpheus Dino"),
        SlackBlast(id: "b2", message: "Buses leave for the hotel at 9pm sharp.", status: "in_progress", recipientCount: 120, sentCount: 64,
                   createdAt: iso(-2), sentBy: "Heidi"),
    ]
}
