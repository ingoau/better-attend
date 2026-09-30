import Foundation
import Testing
@testable import Attend

@Suite struct TimeTests {
    @Test func parsesAttendTimestamps() {
        #expect(Time.parse("2026-10-03T01:30:00Z") == Date(timeIntervalSince1970: 1_790_991_000))
        #expect(abs(Time.parse("2026-10-03T01:30:00.123456Z")!.timeIntervalSince(Time.parse("2026-10-03T01:30:00Z")!) - 0.123456) < 1e-6)
        #expect(Time.parse("2026-10-04T12:00:00+10:00") == Time.parse("2026-10-04T02:00:00Z"))
        #expect(Time.parse("2026-10-04T12:00:00-0130") == Time.parse("2026-10-04T13:30:00Z"))
        #expect(Time.parse("nope") == nil)
        #expect(Time.parse("") == nil)
        #expect(Time.parse(nil) == nil)
    }

    @Test func isoRoundTrips() {
        let d = Time.parse("2026-10-03T01:30:00.250Z")!
        #expect(Time.parse(Time.iso(d)) == d)
    }

    @Test func calendarDays() {
        let sydney = Time.zone("Australia/Sydney")
        // 2 Oct 14:30 UTC is already 3 Oct in Sydney.
        #expect(CalendarDay(Time.parse("2026-10-02T14:30:00Z")!, in: sydney) == CalendarDay(year: 2026, month: 10, day: 3))
        #expect(CalendarDay(iso: "2026-10-03")!.adding(days: 30).description == "2026-11-02")
        #expect(CalendarDay(iso: "2026-10-03")!.days(until: CalendarDay(iso: "2026-10-05")!) == 2)
        #expect(CalendarDay(iso: "2026-13-01") == nil)
    }

    @Test func agoAndPhase() {
        let now = Fixtures.now
        #expect(Time.ago(Fixtures.iso(-0.5), now: now) == "just now")
        #expect(Time.ago(Fixtures.iso(-5), now: now) == "5 min ago")
        #expect(Time.ago(Fixtures.iso(-125), now: now) == "2 h ago")
        #expect(Time.ago(Fixtures.iso(-60 * 24 * 3), now: now) == "3 d ago")
        #expect(Time.phase(Fixtures.event.startsAt, Fixtures.event.endsAt, now: now) == .live)
        #expect(Time.phase("2026-11-01T00:00:00Z", nil, now: now) == .upcoming)
        #expect(Time.phase(nil, nil, now: now) == .unknown)
    }
}

@Suite struct ModelDecodingTests {
    @Test func missingAndNullKeysUseDefaults() throws {
        let json = #"{"id":"c1","name":"Desk","checks_in":null,"position":3}"#
        let c = try AttendJSON.decoder().decode(ScanContext.self, from: Data(json.utf8))
        #expect(c.checksIn == false)
        #expect(c.isTravelPickup == false)
        #expect(c.position == 3)
    }

    @Test func participantDecodesSnakeCaseAndDefaults() throws {
        let json = #"""
        {"participant_id":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","participant_event_id":"pe1","display_name":" ",
         "full_name":"Maya Chen","has_anaphylaxis_risk":true,"scans_by_context":[{"scan_context_id":"c1","checks_in":true}],
         "personal":{"address":{"line_1":"1 Example St"}},"unknown_field":42}
        """#
        let p = try AttendJSON.decoder().decode(Participant.self, from: Data(json.utf8))
        #expect(p.name == "Maya Chen")
        #expect(p.hasSafetyAlert)
        #expect(p.groups.isEmpty)
        #expect(p.scansByContext.first?.checksIn == true)
        #expect(p.personal?.address?.line1 == "1 Example St")
        #expect(p.shortCode == "A1B2C3D4")
    }

    @Test func travelCalendarUsesCamelCaseKeys() throws {
        let json = #"{"eventTimezone":"Australia/Sydney","entries":[{"id":"t1","participantName":"Sam","pickupState":"collected","isUnaccompaniedMinor":true}],"counts":{"awaitingPickup":2}}"#
        let cal = try AttendJSON.decoder().decode(TravelCalendar.self, from: Data(json.utf8))
        #expect(cal.entries.first?.pickupState == "collected")
        #expect(cal.entries.first?.isUnaccompaniedMinor == true)
        #expect(cal.counts.awaitingPickup == 2)
        #expect(cal.dates.isEmpty)
    }

    @Test func cachedModelsRoundTrip() throws {
        let roster = Roster(eventId: "e", participants: Fixtures.participants, syncedAt: "x")
        let data = try AttendJSON.encoder().encode(roster)
        #expect(try AttendJSON.decoder().decode(Roster.self, from: data) == roster)
        let cal = try AttendJSON.decoder().decode(TravelCalendar.self, from: AttendJSON.encoder().encode(Fixtures.travel))
        #expect(cal == Fixtures.travel)
    }

    @Test func scanResultOutcome() throws {
        let already = try AttendJSON.decoder().decode(ScanResult.self, from: Data(#"{"first_scan_in_context":false}"#.utf8))
        #expect(already.isAlreadyScanned)
        let scanned = try AttendJSON.decoder().decode(ScanResult.self, from: Data(#"{"outcome":"scanned","first_scan_in_context":false}"#.utf8))
        #expect(!scanned.isAlreadyScanned)
    }
}

@Suite struct EventStatsTests {
    private func p(_ i: Int, _ status: String, checkedIn: Bool = false) -> Participant {
        Participant(participantId: "p\(i)", participantEventId: "pe\(i)", status: status, checkedInAt: checkedIn ? "2026-10-03T01:00:00Z" : nil)
    }

    @Test func expectedIsConfirmedWhenAnyoneIsComplete() {
        let s = EventStats.from([p(1, "complete", checkedIn: true), p(2, "complete"), p(3, "in_progress"), p(4, "withdrawn")])
        #expect(s.expected == 2)
        #expect(s.checkedIn == 1)
        #expect(s.notArrived == 1)
    }

    @Test func statusColumnLaggingFallsBackToActiveRegistrations() {
        // Nobody marked "complete" (Attend's status column lags) must not read as 0 / 0.
        let s = EventStats.from([p(1, "in_progress", checkedIn: true), p(2, "invited"), p(3, "awaiting_guardian"), p(4, "rejected")])
        #expect(s.expected == 3)
        #expect(s.checkedIn == 1)
        #expect(s.notArrived == 2)
    }

    @Test func checkedInNeverExceedsExpected() {
        let s = EventStats.from([p(1, "complete", checkedIn: true), p(2, "in_progress", checkedIn: true), p(3, "in_progress", checkedIn: true)])
        #expect(s.expected == 3)
        #expect(s.progress == 1)
    }

    @Test func lastHourAndPerContext() {
        let s = EventStats.from(Fixtures.participants, now: Fixtures.now)
        // Checked in 15 and 30 min ago (indices 1, 2); index 4 at exactly 60 min doesn't count.
        #expect(s.checkedInLastHour == 2)
        #expect(s.perContext["c1"] == s.checkedIn)
    }

    @Test func rosterFindAndMerge() {
        let r = Roster(eventId: "e", participants: Fixtures.participants)
        let maya = Fixtures.participants[2]
        #expect(r.find("attend://checkin/\(maya.participantId)")?.participantEventId == maya.participantEventId)
        #expect(r.find("attend:P:\(maya.participantId.uppercased())") == maya)
        #expect(r.find(maya.participantEventId) == maya)
        #expect(r.find("E4B1-2") == maya)
        #expect(r.find("nobody") == nil)

        var detailed = maya
        detailed.personal = Personal(age: 16)
        detailed.groups = [ParticipantGroup(id: "g", name: "G")]
        var fresh = maya
        fresh.checkedInAt = nil
        let merged = Roster.mergeKeepingDetail(detailed, fresh)
        #expect(merged.personal?.age == 16)
        #expect(merged.groups.count == 1)
        #expect(merged.checkedInAt == nil)
    }

    @Test func suggestsLiveThenUpcomingThenRecent() {
        #expect(EventLogic.suggestEvent(Fixtures.events, now: Fixtures.now)?.id == Fixtures.event.id)
        #expect(EventLogic.suggestEvent(Array(Fixtures.events.dropFirst()), now: Fixtures.now)?.id == "e2")
        #expect(EventLogic.suggestEvent([Fixtures.events[2]], now: Fixtures.now)?.id == "e3")
        #expect(EventLogic.suggestEvent([], now: Fixtures.now) == nil)
    }

    @Test func defaultContextPrefersLiveWindowThenCheckIn() {
        #expect(EventLogic.defaultContext(Fixtures.contexts, now: Fixtures.now)?.id == "c1")
        let lunch = Time.parse("2026-10-04T02:30:00Z")!
        #expect(EventLogic.defaultContext(Fixtures.contexts, now: lunch)?.id == "c3")
        #expect(EventLogic.roleLabel("safeguarding_lead") == "Safeguarding lead")
        #expect(EventLogic.roleLabel("some_new_role") == "Some new role")
    }
}

@Suite struct ScanCodeTests {
    let uuid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    @Test func parsesEveryAttendFormat() {
        #expect(ScanCode.parse("attend://checkin/\(uuid)")?.participantId == "attend://checkin/\(uuid)")
        #expect(ScanCode.parse("attend:P:\(uuid)")?.participantId == uuid)
        #expect(ScanCode.parse("  \(uuid.uppercased()) ")?.participantId == uuid.uppercased())
        #expect(ScanCode.parse("https://attend.hackclub.com/tickets/\(uuid)?x=1")?.participantId == uuid)
        #expect(ScanCode.parse("https://example.com/\(uuid)") == nil)
        #expect(ScanCode.parse("attend:P:") == nil)
        #expect(ScanCode.parse("hello") == nil)
        #expect(ScanCode.parse(uuid, source: "nfc")?.source == "nfc")
    }
}

@Suite struct SameCodeGateTests {
    func t(_ ms: Double) -> Date { Date(timeIntervalSince1970: ms / 1000) }

    @Test func firstSightingAcceptedThenRepeatsIgnored() {
        let gate = SameCodeGate(window: 2.5)
        #expect(gate.offer("A", now: t(0)))
        #expect(!gate.offer("A", now: t(100)))
        #expect(!gate.offer("A", now: t(2000)))
    }

    @Test func codeHeldInFrameStaysBlocked() {
        let gate = SameCodeGate(window: 2.5)
        #expect(gate.offer("A", now: t(0)))
        for i in 1...100 { #expect(!gate.offer("A", now: t(Double(i) * 100))) }
    }

    @Test func acceptedAgainAfterBeingAbsentForTheWindow() {
        let gate = SameCodeGate(window: 2.5)
        #expect(gate.offer("A", now: t(0)))
        #expect(!gate.offer("A", now: t(1000)))
        #expect(!gate.offer("A", now: t(3499)))
        #expect(gate.offer("A", now: t(6000)))
        let exact = SameCodeGate(window: 2.5)
        #expect(exact.offer("A", now: t(0)))
        #expect(exact.offer("A", now: t(2500)))
    }

    @Test func codesAreIndependentAndDoNotFlipFlop() {
        let gate = SameCodeGate(window: 2.5)
        #expect(gate.offer("A", now: t(0)))
        #expect(gate.offer("B", now: t(0)))
        for ms in stride(from: 100.0, through: 3000, by: 100) {
            #expect(!gate.offer("A", now: t(ms)))
            #expect(!gate.offer("B", now: t(ms)))
        }
    }

    @Test func releaseAndReset() {
        let gate = SameCodeGate(window: 2.5)
        #expect(gate.offer("A", now: t(0)))
        gate.release("A")
        #expect(gate.offer("A", now: t(100)))
        #expect(gate.offer("B", now: t(100)))
        gate.reset()
        #expect(gate.offer("A", now: t(110)))
        #expect(gate.offer("B", now: t(110)))
    }

    @Test func pruningKeepsRecentEntries() {
        let gate = SameCodeGate(window: 2.5)
        #expect(gate.offer("keep", now: t(10_000)))
        for i in 0..<40 { _ = gate.offer("old\(i)", now: t(Double(i))) }
        #expect(!gate.offer("keep", now: t(11_000)))
    }
}

@Suite struct RosterSearchAndToneTests {
    let people = Fixtures.participants

    @Test func matchesNamePrefixesAndAllTerms() {
        #expect(RosterSearch.filter(people, query: "may").first?.displayName == "Maya")
        #expect(RosterSearch.filter(people, query: "sam lee").map(\.fullName) == ["Sam Lee"])
        #expect(RosterSearch.filter(people, query: "sam patel").isEmpty)
        #expect(RosterSearch.filter(people, query: "   ").isEmpty)
    }

    @Test func matchesShortCodeAndEmail() {
        #expect(RosterSearch.filter(people, query: people[3].shortCode.lowercased()) == [people[3]])
        #expect(RosterSearch.filter(people, query: "priya@exa") == [people[4]])
    }

    @Test func inactiveSortLast() {
        var list = people
        list[0].displayName = "Isabel"
        list[0].fullName = "Isabel Lee"
        let r = RosterSearch.filter(list, query: "is")
        #expect(r.last?.participantEventId == people[10].participantEventId)
    }

    @Test func directInputAndShortCodes() {
        #expect(RosterSearch.directInput("attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d") != nil)
        #expect(RosterSearch.directInput("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")?.source == "manual")
        #expect(RosterSearch.directInput("A1B2C3D4") == nil)
        #expect(RosterSearch.looksLikeShortCode("A1B2C3D4"))
        #expect(!RosterSearch.looksLikeShortCode("Sam Lee"))
    }

    @Test func wavHeaderAndLength() {
        let samples = ToneSynth.render(ToneSynth.notes(for: .success))
        let wav = ToneSynth.wav(samples)
        #expect(String(decoding: wav.prefix(4), as: UTF8.self) == "RIFF")
        #expect(String(decoding: wav[8..<12], as: UTF8.self) == "WAVE")
        #expect(wav.count == 44 + samples.count * 2)
        #expect(samples.count == (90 + 10 + 170) * 44_100 / 1000)
    }

    @Test func tonesStartAndEndSilentAndAreDistinct() {
        for kind in FeedbackKind.allCases {
            let s = ToneSynth.render(ToneSynth.notes(for: kind))
            #expect(abs(Int(s.first!)) < 200)
            #expect(abs(Int(s.last!)) < 2000)
            #expect(s.map { Int($0) }.max()! > 10_000)
        }
        #expect(Set(FeedbackKind.allCases.map { ToneSynth.wav(for: $0) }).count == FeedbackKind.allCases.count)
    }
}

@Suite struct NdefTests {
    let uuid = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    let token = "e4b1f0c2-1111-4a7b-8c9d-0e1f2a3b4c5d"

    func external(_ type: String, _ payload: String) -> RawNdefRecord { RawNdefRecord(tnf: 0x04, type: Data(type.utf8), payload: Data(payload.utf8)) }
    func uri(_ prefix: UInt8, _ rest: String) -> RawNdefRecord { RawNdefRecord(tnf: 0x01, type: Data("U".utf8), payload: Data([prefix]) + Data(rest.utf8)) }
    func text(_ value: String, lang: String = "en", utf16: Bool = false) -> RawNdefRecord {
        let status = UInt8((utf16 ? 0x80 : 0) | lang.utf8.count)
        let body = utf16 ? value.data(using: .utf16)! : Data(value.utf8)
        return RawNdefRecord(tnf: 0x01, type: Data("T".utf8), payload: Data([status]) + Data(lang.utf8) + body)
    }
    func input(_ r: NfcParseResult) -> ScanInput? { if case .input(let i) = r { i } else { nil } }

    @Test func externalRecordGivesBadgeTokenAndWins() {
        #expect(input(NdefParser.parse([external("hackclub.com:attend", token)])) == ScanInput(badgeToken: token, source: "nfc"))
        #expect(input(NdefParser.parse([uri(0x04, "badge.hackclub.com/t/U01ABC"), external("hackclub.com:attend", token)]))?.badgeToken == token)
        #expect(input(NdefParser.parse([external("HackClub.com:Attend", "  \(token)\u{0}")]))?.badgeToken == token)
    }

    @Test func otherExternalTypesFallBackToPayload() {
        #expect(input(NdefParser.parse([external("example.com:thing", token)])) == ScanInput(participantId: token, source: "nfc"))
        #expect(input(NdefParser.parse([external("example.com:thing", "hello")])) == nil)
        #expect(input(NdefParser.parse([external("hackclub.com:attend", ""), uri(0x00, "attend://checkin/\(uuid)")]))?.participantId == "attend://checkin/\(uuid)")
    }

    @Test func uriAndTextRecords() {
        #expect(input(NdefParser.parse([uri(0x00, "attend://checkin/\(uuid)")]))?.participantId == "attend://checkin/\(uuid)")
        #expect(input(NdefParser.parse([uri(0x04, "attend.hackclub.com/tickets/\(uuid)")]))?.participantId == uuid)
        #expect(input(NdefParser.parse([text(uuid)]))?.participantId == uuid)
        #expect(input(NdefParser.parse([text("attend:P:\(uuid)", lang: "en-AU", utf16: true)]))?.participantId == uuid)
        #expect(input(NdefParser.parse([RawNdefRecord(tnf: 0x03, type: Data("attend://checkin/\(uuid)".utf8), payload: Data())]))?.participantId == "attend://checkin/\(uuid)")
        #expect(input(NdefParser.parse([RawNdefRecord(tnf: 0x02, type: Data("text/plain".utf8), payload: Data(uuid.utf8))]))?.participantId == uuid)
    }

    @Test func unrecognisedTags() {
        #expect(NdefParser.parse([uri(0x04, "badge.hackclub.com/t/U01ABCDEF")]) == .unrecognised(NdefParser.unlinkedBadge))
        #expect(NdefParser.parse([]) == .unrecognised(NdefParser.noData))
        #expect(NdefParser.parse([uri(0x02, "example.com")]) == .unrecognised(NdefParser.unrecognised))
    }

    @Test func uriPrefixTable() {
        #expect(NdefParser.decodeURI(Data([0x02]) + Data("hackclub.com".utf8)) == "https://www.hackclub.com")
        #expect(NdefParser.decodeURI(Data([0x05]) + Data("123".utf8)) == "tel:123")
        #expect(NdefParser.decodeURI(Data([0x23]) + Data("x".utf8)) == "urn:nfc:x")
    }

    @Test func badgeRecordsRoundTrip() {
        let records = NfcBadgeFormat.badgeRecords(slackUserId: "U01ABCDEF", token: token)
        #expect(records.count == 2)
        #expect(NdefParser.textOf(records[0]) == "https://badge.hackclub.com/t/U01ABCDEF")
        #expect(records[0].payload.first == 0x04) // "https://" abbreviated
        #expect(records[1].tnf == 0x04)
        #expect(String(decoding: records[1].type, as: UTF8.self) == "hackclub.com:attend")
        #expect(input(NdefParser.parse(records)) == ScanInput(badgeToken: token, source: "nfc"))
        #expect(NfcBadgeFormat.readToken(records) == token)
        #expect(NfcBadgeFormat.badgeRecords(slackUserId: "  ", token: token).count == 1)
        #expect(NfcBadgeFormat.readToken([records[0]]) == nil)
    }
}

@Suite struct TicketLogicTests {
    let event = Fixtures.ticketEvent
    func at(_ iso: String) -> Date { Time.parse(iso)! }

    @Test func clockFormats() {
        #expect(TicketLogic.clock(2 * 86_400 + 4 * 3600 + 10 * 60 + 5) == "2d 4h 10m")
        #expect(TicketLogic.clock(4 * 3600 + 10 * 60 + 5) == "4h 10m 5s")
        #expect(TicketLogic.clock(10 * 60 + 5) == "10m 5s")
    }

    @Test func countdownPhases() {
        if case .upcoming = TicketLogic.countdown(event, now: at("2026-10-01T00:00:00Z")) {} else { Issue.record("expected upcoming") }
        #expect(TicketLogic.countdown(event, now: at("2026-10-03T01:00:00Z")) == .live)
        #expect(TicketLogic.countdown(event, now: at("2026-10-05T00:00:00Z")) == .ended)
        var undated = event
        undated.startsAt = nil
        #expect(TicketLogic.countdown(undated) == .unknown)
    }

    @Test func relativeLabelUsesEventTimezoneDays() {
        #expect(TicketLogic.relativeLabel(event, now: at("2026-09-30T01:30:00Z")) == "in 3 days")
        #expect(TicketLogic.relativeLabel(event, now: at("2026-10-02T00:00:00Z")).hasPrefix("Tomorrow · "))
        #expect(TicketLogic.relativeLabel(event, now: at("2026-10-02T14:30:00Z")).hasPrefix("Today · doors "))
        #expect(TicketLogic.relativeLabel(event, now: at("2026-10-02T21:30:00Z")) == "Starts in 30 min")
        #expect(TicketLogic.relativeLabel(event, now: at("2026-10-03T01:00:00Z")) == "Happening now")
        #expect(TicketLogic.relativeLabel(event, now: at("2026-10-06T00:00:00Z")) == "Ended")
        #expect(TicketLogic.relativeLabel(event, now: at("2026-08-25T00:00:00Z")) == "in 5 weeks")
        #expect(TicketLogic.relativeLabel(event, now: at("2026-06-01T00:00:00Z")) == "in 4 months")
        var undated = event
        undated.startsAt = nil
        #expect(TicketLogic.relativeLabel(undated) == "Date to be announced")
    }

    @Test func statusMapping() {
        var t = Fixtures.ticket
        #expect(TicketLogic.status(t) == .ready)
        t.checkedIn = true
        #expect(TicketLogic.status(t) == .checkedIn)
        t = Fixtures.ticket
        t.confirmed = false
        t.status = "awaiting_guardian"
        t.displayStatus = "Awaiting Parent"
        #expect(TicketLogic.status(t).label == "Waiting on guardian")
        t.status = "in_progress"
        t.displayStatus = "Awaiting Participant"
        if case .incomplete = TicketLogic.status(t) {} else { Issue.record("expected incomplete") }
        t.status = "withdrawn"
        t.displayStatus = "Withdrawn"
        #expect(TicketLogic.status(t).isClosed)
    }

    private func ticket(_ id: String, start: String? = nil, end: String? = nil, confirmed: Bool = true, status: String = "complete") -> Ticket {
        var t = Fixtures.ticket
        t.id = id
        t.confirmed = confirmed
        t.status = status
        if status == "withdrawn" { t.displayStatus = "Withdrawn" }
        if let start { t.event.startsAt = start; t.event.endsAt = end }
        return t
    }

    @Test func sortingAndNext() {
        let now = at("2026-09-30T00:00:00Z")
        let past = ticket("past", start: "2026-06-01T00:00:00Z", end: "2026-06-02T00:00:00Z")
        let later = ticket("later", start: "2026-12-01T00:00:00Z", end: "2026-12-02T00:00:00Z")
        let soon = ticket("soon")
        let withdrawn = ticket("w", start: "2026-09-30T06:00:00Z", confirmed: false, status: "withdrawn")
        let (current, pastList) = TicketLogic.sorted([past, later, soon], now: now)
        #expect(current.map(\.id) == ["soon", "later"])
        #expect(pastList.map(\.id) == ["past"])
        #expect(TicketLogic.next([later, withdrawn, soon, past], now: now)?.id == "soon")
        #expect(TicketLogic.next([past], now: now) == nil)
    }

    @Test func pagerIdsFollowListOrderAndSkipUnconfirmed() {
        let now = at("2026-09-30T00:00:00Z")
        let past = ticket("past", start: "2026-06-01T00:00:00Z", end: "2026-06-02T00:00:00Z")
        let later = ticket("later", start: "2026-12-01T00:00:00Z", end: "2026-12-02T00:00:00Z")
        let soon = ticket("soon")
        let pending = ticket("pending", start: "2026-11-01T00:00:00Z", end: "2026-11-02T00:00:00Z", confirmed: false)
        let all = [past, pending, later, soon]
        #expect(TicketLogic.pagerIds(all, openedId: "later", now: now) == ["soon", "later", "past"])
        #expect(TicketLogic.pagerIds(all, openedId: "pending", now: now) == ["soon", "pending", "later", "past"])
        #expect(TicketLogic.pagerIds(nil, openedId: "x", now: now) == ["x"])
        #expect(TicketLogic.pagerIds(all, openedId: "x", now: now) == ["x"])
        #expect(TicketLogic.pagerIds([soon], openedId: "soon", now: now) == ["soon"])
    }

    @Test func venue() {
        #expect(TicketLogic.venueLines(event) == ("1 Example St, Sydney NSW", "Sydney, AU"))
        #expect(TicketLogic.venueLines(TicketEvent(id: "y", name: "Y")) == ("Venue to be announced", nil))
        #expect(TicketLogic.venueQuery(TicketEvent(id: "x", name: "X", locationCity: "Sydney", locationAddress: "1 Example St")) == "1 Example St, Sydney")
        #expect(TicketLogic.venueQuery(TicketEvent(id: "y", name: "Y")) == nil)
        #expect(TicketLogic.hasVenue(event))
    }
}

@Suite struct WidgetSnapshotTests {
    let now = Fixtures.now
    let roster = Roster(eventId: Fixtures.event.id, participants: Fixtures.participants, syncedAt: "2026-10-03T01:20:00.123456Z",
                        lastFullSyncAt: "2026-10-03T00:00:00Z", lastSyncAt: "2026-10-03T01:20:00Z")

    func build(user: User? = Fixtures.user, event: Event? = Fixtures.event, roster: Roster?? = nil, isOrganizer: Bool = true) -> WidgetSnapshot {
        WidgetSnapshots.build(user: user, event: event, roster: roster ?? self.roster, contexts: Fixtures.contexts, travel: Fixtures.travel,
                              tickets: Fixtures.tickets, isOrganizer: isOrganizer, now: now)
    }

    @Test func signedOutHasNoData() {
        let s = build(user: nil)
        #expect(!s.signedIn)
        #expect(s.organizer == nil)
        #expect(s.ticket == nil)
    }

    @Test func organizerCountsMatchEventStats() throws {
        let org = try #require(build().organizer)
        let stats = EventStats.from(Fixtures.participants, now: now)
        #expect(org.eventName == "Campfire Sydney")
        #expect(org.hasCounts)
        #expect(org.checkedIn == stats.checkedIn)
        #expect(org.expected == max(stats.confirmed, stats.checkedIn))
        #expect(org.notArrived == stats.notArrived)
        #expect(org.lastHour == stats.checkedInLastHour)
        #expect(org.updatedAt == "2026-10-03T01:20:00Z")
        #expect(org.contexts.map(\.name) == Fixtures.contexts.sorted { $0.position < $1.position }.map(\.name))
        for c in org.contexts { #expect(c.count == stats.perContext[c.id] ?? 0) }
        #expect(org.contexts.first { $0.id == "c2" }?.isTravel == true)
    }

    @Test func noCountsWithoutRosterAccessOrSync() {
        var hidden = Fixtures.event
        hidden.canViewParticipants = false
        #expect(build(event: hidden).organizer?.hasCounts == false)
        var partial = roster
        partial.syncedAt = nil
        #expect(build(roster: .some(partial)).organizer?.hasCounts == false)
        #expect(build(roster: .some(nil)).organizer?.hasCounts == false)
    }

    @Test func participantOnlyUserGetsTicketButNoOrganizerData() {
        var u = Fixtures.user
        u.isOrganizer = false
        let s = build(user: u, isOrganizer: false)
        #expect(s.organizer == nil)
        #expect(s.ticket != nil)
        #expect(s.isParticipant)
    }

    @Test func travelUsesServerCountsAndNextUpcomingInbound() throws {
        let t = try #require(build().organizer?.travel)
        #expect(t.awaitingPickup == Fixtures.travel.counts.awaitingPickup)
        #expect(t.collected == Fixtures.travel.counts.collected)
        #expect(t.checkedIn == Fixtures.travel.counts.checkedIn)
        let expected = Fixtures.travel.entries[2]
        #expect(t.nextArrivalName == expected.name)
        #expect(t.nextArrivalAt == expected.primaryTimeAt)
        #expect(t.nextArrivalMinor)
    }

    @Test func nextArrivalSkipsLongPastAndOutbound() {
        var cal = Fixtures.travel
        cal.entries = [
            TravelEntry(id: "old", participantName: "Old", direction: "inbound", primaryTimeAt: "2026-10-02T20:00:00Z"),
            TravelEntry(id: "out", participantName: "Out", direction: "outbound", primaryTimeAt: "2026-10-03T02:00:00Z"),
            TravelEntry(id: "late", participantName: "Late", direction: "inbound", primaryTimeAt: "2026-10-03T05:00:00Z"),
            TravelEntry(id: "soon", participantName: "Just landed", direction: "inbound", primaryTimeAt: "2026-10-03T01:10:00Z"),
        ]
        #expect(WidgetSnapshots.travel(cal, now: now).nextArrivalName == "Just landed")
        cal.entries = Array(cal.entries.prefix(2))
        #expect(WidgetSnapshots.travel(cal, now: now).nextArrivalName == nil)
    }

    @Test func travelOmittedWhenEventHasNoTravel() {
        var e = Fixtures.event
        e.travelEnabled = false
        #expect(build(event: e).organizer?.travel == nil)
    }

    @Test func ticketIsTheNextOne() throws {
        let t = try #require(build().ticket)
        #expect(t.id == Fixtures.ticket.id)
        #expect(t.statusLabel == "Ready")
        #expect(t.confirmed)
    }

    @Test func sameContentIgnoresBuildTime() {
        let a = build()
        var b = a
        b.builtAt = "2030-01-01T00:00:00Z"
        #expect(a.sameContent(as: b))
        var c = a
        c.organizer?.checkedIn = 1
        #expect(!a.sameContent(as: c))
        #expect(!a.sameContent(as: nil))
    }

    @Test func snapshotSurvivesJsonRoundTrip() throws {
        let s = build()
        #expect(try JSONDecoder().decode(WidgetSnapshot.self, from: JSONEncoder().encode(s)) == s)
    }
}
