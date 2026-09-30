import CoreGraphics
import Foundation
import Testing
@testable import Attend

extension Fixtures {
    /// A ticket for an event with the given start/end, for ordering and paging.
    static func ticket(_ id: String, start: String? = nil, end: String? = nil, confirmed: Bool = true, status: String = "complete") -> Ticket {
        var t = ticket
        t.id = id
        t.confirmed = confirmed
        t.status = status
        if status == "withdrawn" { t.displayStatus = "Withdrawn" }
        if let start { t.event.startsAt = start; t.event.endsAt = end }
        return t
    }

    static let travelLeg = TicketTravelLeg(flightCode: "QF401", departureAirport: "MEL", arrivalAirport: "SYD",
                                           departureTime: "2026-10-02T20:00:00Z", arrivalTime: "2026-10-02T21:30:00Z")
}

@Suite struct TicketsListTests {
    func at(_ iso: String) -> Date { Time.parse(iso)! }

    @Test func subtitleCountsUpcoming() {
        #expect(TicketPassLogic.subtitle(current: 0, total: 0) == nil)
        #expect(TicketPassLogic.subtitle(current: 0, total: 2) == "No upcoming events")
        #expect(TicketPassLogic.subtitle(current: 1, total: 2) == "1 upcoming event")
        #expect(TicketPassLogic.subtitle(current: 3, total: 3) == "3 upcoming events")
    }

    @Test func refreshIsThrottledToOncePerMinute() {
        let now = at("2026-10-03T00:00:00Z")
        #expect(TicketPassLogic.refreshDue(last: nil, now: now))
        #expect(!TicketPassLogic.refreshDue(last: now.addingTimeInterval(-30), now: now))
        #expect(TicketPassLogic.refreshDue(last: now.addingTimeInterval(-60), now: now))
    }

    @Test func calendarBlockUsesEventTimezone() {
        // 2026-10-02T22:00Z is Saturday 3 October in Sydney.
        let block = TicketPassLogic.calendarBlock(Fixtures.ticketEvent, locale: Locale(identifier: "en_AU"))
        #expect(block.month == "OCT")
        #expect(block.day == "3")
        var undated = Fixtures.ticketEvent
        undated.startsAt = nil
        #expect(TicketPassLogic.calendarBlock(undated) == ("TBA", "–"))
    }

    @Test func listSplitsCurrentAndPastInOrder() {
        let now = at("2026-09-30T00:00:00Z")
        let past = Fixtures.ticket("past", start: "2026-06-01T00:00:00Z", end: "2026-06-02T00:00:00Z")
        let older = Fixtures.ticket("older", start: "2026-03-01T00:00:00Z", end: "2026-03-02T00:00:00Z")
        let later = Fixtures.ticket("later", start: "2026-12-01T00:00:00Z", end: "2026-12-02T00:00:00Z")
        let soon = Fixtures.ticket("soon")
        let (current, pastList) = TicketLogic.sorted([older, later, past, soon], now: now)
        #expect(current.map(\.id) == ["soon", "later"])
        #expect(pastList.map(\.id) == ["past", "older"])
        #expect(TicketPassLogic.subtitle(current: current.count, total: 4) == "2 upcoming events")
    }

    @Test func spokenCodeSpellsCharacters() {
        #expect(TicketPassLogic.spokenCode("A1B2") == "A 1 B 2")
    }
}

@Suite struct TicketPassTests {
    let event = Fixtures.ticketEvent
    func at(_ iso: String) -> Date { Time.parse(iso)! }

    @Test func timeZoneNoteOnlyWhenZonesDiffer() {
        #expect(TicketPassLogic.timeZoneNote(event, device: TimeZone(identifier: "Australia/Sydney")!) == nil)
        #expect(TicketPassLogic.timeZoneNote(event, device: TimeZone(identifier: "America/New_York")!) == "Times shown in Sydney time")
        var noCity = event
        noCity.locationCity = nil
        noCity.timezone = "America/Los_Angeles"
        #expect(TicketPassLogic.timeZoneNote(noCity, device: TimeZone(identifier: "Europe/London")!) == "Times shown in Los Angeles time")
        var noZone = event
        noZone.timezone = nil
        #expect(TicketPassLogic.timeZoneNote(noZone, device: TimeZone(identifier: "Europe/London")!) == nil)
    }

    @Test func travelLegLines() {
        let lines = TicketPassLogic.legLines(Fixtures.travelLeg, tz: "Australia/Sydney", now: at("2026-10-01T00:00:00Z"))
        #expect(lines.route == "MEL → SYD")
        #expect(lines.times?.hasPrefix("Departs ") == true)
        #expect(lines.times?.contains(" · arrives ") == true)
        let unknown = TicketPassLogic.legLines(TicketTravelLeg(), tz: nil)
        #expect(unknown.route == "? → ?")
        #expect(unknown.times == nil)
    }

    @Test func travelWithoutLegs() {
        let t = TicketTravel(mode: "train", departureCity: "Melbourne", arrivalCity: "Sydney")
        let lines = TicketPassLogic.travelLines(t, tz: nil)
        #expect(lines.title == "Train")
        #expect(lines.route == "Melbourne → Sydney")
        #expect(lines.times == nil)
        #expect(TicketPassLogic.travelLines(TicketTravel(carrier: "Qantas", flightNumber: "QF1"), tz: nil).title == "Qantas QF1")
        #expect(TicketPassLogic.travelLines(TicketTravel(), tz: nil).title == "Travel")
        #expect(TicketPassLogic.modeSymbol("bus") == "bus.fill")
        #expect(TicketPassLogic.modeSymbol(nil) == "airplane")
    }

    @Test func messageMeta() {
        let now = at("2026-10-03T01:30:00Z")
        let m = TicketMessage(id: "m", subject: "Hi", body: nil, senderName: "Orpheus", deliveredAt: "2026-10-03T00:50:00Z")
        #expect(TicketPassLogic.messageMeta(m, now: now) == "Orpheus · 40 min ago")
        #expect(TicketPassLogic.messageMeta(TicketMessage(id: "x"), now: now) == "Event team")
    }

    @Test func messageHTMLBecomesText() {
        #expect(TicketPassLogic.messageText("<p>Doors open at 9am. Bring your laptop charger!</p><p>See you soon.</p>")
                == "Doors open at 9am. Bring your laptop charger!\n\nSee you soon.")
        #expect(TicketPassLogic.messageText("Buses leave at <b>9pm sharp</b>.<br>Bring everything!") == "Buses leave at 9pm sharp.\nBring everything!")
        #expect(TicketPassLogic.messageText("<ul><li>One</li><li>Two</li></ul>") == "• One\n• Two")
        #expect(TicketPassLogic.messageText("Fish &amp; chips &lt;3 &#127790; &#x1F32E;") == "Fish & chips <3 🌮 🌮")
        #expect(TicketPassLogic.messageText("  lots   of\n\n  space  ") == "lots of space")
        #expect(TicketPassLogic.messageText("<style>p{color:red}</style><p>Hi</p><script>alert(1)</script>") == "Hi")
        #expect(TicketPassLogic.messageText(nil) == "")
        #expect(TicketPassLogic.messageText("   ") == "")
    }

    @Test func messageHTMLBecomesMarkdown() {
        #expect(TicketPassLogic.messageMarkdown("Buses at <b>9pm</b> and <em>sharp</em>") == "Buses at **9pm** and *sharp*")
        #expect(TicketPassLogic.messageMarkdown("<strong>Bold </strong>text") == "**Bold** text")
        #expect(TicketPassLogic.messageMarkdown(#"See <a href="https://hack.club/x">the schedule</a>."#) == "See [the schedule](https://hack.club/x).")
        // Only web and mail links survive; others become plain text.
        #expect(TicketPassLogic.messageMarkdown(#"<a href="javascript:alert(1)">click</a>"#) == "click")
        // Markdown characters in the text are escaped.
        #expect(TicketPassLogic.messageMarkdown("2*3 = 6_ish [sic]") == #"2\*3 = 6\_ish \[sic\]"#)
        let parsed = try? AttributedString(markdown: TicketPassLogic.messageMarkdown("<p>Lunch <b>now</b> &amp; 2*3</p>"),
                                           options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))
        #expect(parsed.map { String($0.characters) } == "Lunch now & 2*3")
    }

    /// Replaces Android's geo: / Google Maps URL test: iOS opens Apple Maps.
    @Test func directionsFallbackURL() {
        let url = TicketPassLogic.appleMapsURL(event)?.absoluteString
        #expect(url?.hasPrefix("https://maps.apple.com/?daddr=-33.8688,151.2093") == true)
        let noCoords = TicketEvent(id: "x", name: "X", locationCity: "Sydney", locationAddress: "1 Example St")
        #expect(TicketPassLogic.appleMapsURL(noCoords)?.absoluteString == "https://maps.apple.com/?daddr=1%20Example%20St,%20Sydney")
        #expect(TicketPassLogic.appleMapsURL(TicketEvent(id: "y", name: "Y")) == nil)
        #expect(!TicketLogic.hasVenue(TicketEvent(id: "y", name: "Y")))
    }

    @Test func statusPresentation() {
        #expect(TicketLogic.Status.ready.passTone == .success)
        #expect(TicketLogic.Status.checkedIn.passSymbol == "checkmark")
        var t = Fixtures.ticket
        t.confirmed = false
        t.status = "awaiting_guardian"
        let s = TicketLogic.status(t)
        #expect(s.passTone == .warning)
        #expect(s.passReason?.contains("guardian") == true)
        #expect(TicketLogic.Status.ready.passReason == nil)
    }
}

@MainActor
@Suite struct TicketQRTests {
    @Test func rendersSquareBlackAndWhiteModules() throws {
        let image = try #require(TicketQRRenderer.image(for: Fixtures.ticket.qrPayload))
        #expect(image.width == image.height)
        // Version 3+ (29+ modules) plus the quiet zone; one pixel per module.
        #expect(image.width >= 29 && image.width < 80)
        // Cached: the same image comes back for the same payload.
        #expect(TicketQRRenderer.image(for: Fixtures.ticket.qrPayload) === image)
        #expect(TicketQRRenderer.image(for: "other") !== image)
    }
}
