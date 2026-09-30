import Foundation
import Testing
@testable import Attend

extension Fixtures {
    /// Port of Android's `OrganizerSamples.travel`: 11 journeys across four modes, one unscheduled.
    static let organizerTravel: TravelCalendar = {
        let blue = ParticipantGroup(id: "g1", name: "Team Blue", color: "#3b82f6")
        let green = ParticipantGroup(id: "g2", name: "Mentors", color: "#22c55e")
        let names = ["Sam Lee", "Arjun Patel", "Maya Chen", "Leo Nguyen", "Priya Sharma", "Noah Williams", "Zara Ahmed",
                     "Kai Tanaka", "Ella Brown", "Oliver Smith", "Isla Wilson"]
        func entry(_ i: Int, _ direction: String, _ mode: String, _ minutes: Double?, _ date: String?, _ route: String,
                   _ ref: String?, _ pickup: String?, um: Bool = false, groups: [ParticipantGroup] = []) -> TravelEntry {
            TravelEntry(
                id: "tr\(i)", participantId: "p\(i)", participantEventId: "pe\(i)", participantName: names[i],
                participantPreferredName: String(names[i].split(separator: " ")[0]), direction: direction, mode: mode,
                primaryTimeAt: minutes.map(iso), agendaDate: date, route: route, reference: ref,
                pickupState: pickup, isUnaccompaniedMinor: um, groups: groups
            )
        }
        return TravelCalendar(
            eventTimezone: "Australia/Sydney",
            dates: ["2026-10-03", "2026-10-04", "2026-10-05"],
            entries: [
                entry(0, "inbound", "plane", -95, "2026-10-03", "MEL → SYD", "QF401", "collected", groups: [blue]),
                entry(1, "inbound", "plane", -60, "2026-10-03", "BNE → SYD", "VA914", "checked_in"),
                entry(2, "inbound", "plane", 20, "2026-10-03", "AKL → SYD", "NZ103", "awaiting_pickup", um: true, groups: [blue, green]),
                entry(3, "inbound", "train", 45, "2026-10-03", "Central → Venue", "NSW TrainLink", "awaiting_pickup"),
                entry(4, "inbound", "plane", 75, "2026-10-03", "PER → SYD", "QF566 · QF412", "awaiting_pickup"),
                entry(5, "inbound", "car", 120, "2026-10-03", "Parent drop-off", nil, "pickup_not_needed"),
                entry(6, "inbound", "bus", 150, "2026-10-03", "Canberra → Sydney", "Murrays", "awaiting_pickup"),
                entry(7, "outbound", "plane", 60 * 24 + 360, "2026-10-04", "SYD → MEL", "JQ508", nil),
                entry(8, "outbound", "plane", 60 * 48 - 60, "2026-10-05", "SYD → AKL", "NZ104", nil, um: true),
                entry(9, "outbound", "train", 60 * 48 + 30, "2026-10-05", "Venue → Central", nil, nil),
                entry(10, "inbound", "plane", nil, nil, "Details pending", nil, "awaiting_pickup"),
            ],
            counts: TravelCounts(total: 11, inbound: 8, outbound: 3, scheduled: 10, unscheduled: 1, awaitingPickup: 5,
                                 collected: 1, checkedIn: 1, pickupNotNeeded: 1)
        )
    }()
}

@Suite struct TravelLogicTests {
    let entries = Fixtures.organizerTravel.entries
    let today = CalendarDay(iso: "2026-10-03")!

    @Test func sectionsAreChronologicalWithUnscheduledLast() {
        let sections = TravelLogic.sections(entries, today: today)
        #expect(sections.map(\.title) == ["Today", "Tomorrow", Time.longDay(CalendarDay(iso: "2026-10-05")!), "Unscheduled"])
        #expect(sections[0].subtitle == Time.longDay(today))
        #expect(sections[2].subtitle == nil)
        #expect(sections[3].date == nil)
        #expect(sections.map(\.key) == ["2026-10-03", "2026-10-04", "2026-10-05", "unscheduled"])
        #expect(sections.reduce(0) { $0 + $1.entries.count } == entries.count)
        let times = sections[0].entries.map { Time.parse($0.primaryTimeAt)! }
        #expect(times == times.sorted())
    }

    @Test func longDayNamesTheWeekdayAndMonth() {
        let label = Time.longDay(CalendarDay(iso: "2026-10-05")!)
        #expect(label.contains("Monday"))
        #expect(label.contains("October"))
        #expect(label.contains("5"))
    }

    @Test func sameTimeSortsByName() {
        let at = Fixtures.iso(0)
        let a = TravelEntry(id: "a", participantName: "zed", primaryTimeAt: at, agendaDate: "2026-10-03")
        let b = TravelEntry(id: "b", participantName: "Amy", primaryTimeAt: at, agendaDate: "2026-10-03")
        let c = TravelEntry(id: "c", participantName: "Bob", agendaDate: "2026-10-03")
        #expect(TravelLogic.sections([c, a, b], today: today).single?.entries.map(\.id) == ["b", "a", "c"])
    }

    @Test func yesterdayLabel() {
        #expect(TravelLogic.relativeLabel(today.adding(days: -1), today: today) == "Yesterday")
        #expect(TravelLogic.relativeLabel(today.adding(days: 1), today: today) == "Tomorrow")
        #expect(TravelLogic.relativeLabel(today.adding(days: 2), today: today) == nil)
    }

    @Test func badDatesGoToUnscheduled() {
        let e = TravelEntry(id: "x", participantName: "Zed", agendaDate: "not-a-date")
        #expect(TravelLogic.sections([e], today: today).single?.title == "Unscheduled")
        #expect(TravelLogic.parseDate("2026-10-03T10:00") == nil)
        #expect(TravelLogic.parseDate("2026-10-03") == today)
    }

    @Test func filters() {
        func n(_ f: TravelFilter) -> Int { TravelLogic.filter(entries, query: "", filter: f, mode: nil).count }
        #expect(n(.all) == 11)
        #expect(n(.arrivals) == 8)
        #expect(n(.departures) == 3)
        #expect(n(.awaitingPickup) == 5)
        #expect(n(.pickedUp) == 2)
        #expect(n(.minors) == 2)
        #expect(TravelLogic.filter(entries, query: "", filter: .all, mode: .plane).count == 7)
        #expect(TravelLogic.filter(entries, query: "", filter: .awaitingPickup, mode: .plane).count == 3)
    }

    @Test func searchMatchesAllTermsAcrossFields() {
        func ids(_ q: String) -> [String] { TravelLogic.filter(entries, query: q, filter: .all, mode: nil).map(\.id).sorted() }
        #expect(ids("qf401") == ["tr0"])
        #expect(ids("akl") == ["tr2", "tr8"])
        #expect(ids("  AKL   nz103 ") == ["tr2"])
        #expect(ids("akl zzz").isEmpty)
        #expect(ids("maya") == ["tr2"])
        #expect(ids("   ").count == 11)
    }

    @Test func searchIncludesNotes() {
        var e = TravelEntry(id: "n", participantName: "Zed")
        e.details = "Meet at gate 5"
        #expect(TravelLogic.matchesQuery(e, "GATE 5"))
        #expect(!TravelLogic.matchesQuery(e, "gate 6"))
    }

    @Test func countsFollowSearchAndMode() {
        let counts = TravelLogic.filterCounts(entries, query: "", mode: .train)
        #expect(counts[.all] == 2)
        #expect(counts[.arrivals] == 1)
        #expect(counts[.departures] == 1)
        #expect(counts[.minors] == 0)
        let modes = TravelLogic.modeCounts(entries, query: "", filter: .departures)
        #expect(modes == [.plane: 2, .train: 1])
        #expect(TravelLogic.modesPresent(entries).count == 4)
        #expect(TravelLogic.filterCounts(entries, query: "akl", mode: nil)[.all] == 2)
    }

    @Test func unknownModeIsOther() {
        #expect(TravelMode.of(TravelEntry(id: "x", mode: nil)) == .other)
        #expect(TravelMode.of(TravelEntry(id: "x", mode: "boat")) == .other)
        #expect(TravelMode.of(TravelEntry(id: "x", mode: "bus")) == .bus)
        #expect(TravelMode.systemImage(mode: "plane", direction: "inbound") == "airplane.arrival")
        #expect(TravelMode.systemImage(mode: "plane", direction: "outbound") == "airplane.departure")
    }

    @Test func zoneLabels() {
        let now = Fixtures.now // AEST (+10); Sydney DST starts 4 Oct
        #expect(TravelLogic.zoneLabel("Australia/Sydney", now: now) == "Sydney · GMT+10")
        #expect(TravelLogic.zoneLabel("Australia/Sydney", now: Time.parse("2026-10-05T01:30:00Z")!) == "Sydney · GMT+11")
        #expect(TravelLogic.zoneLabel("Asia/Kolkata", now: now) == "Kolkata · GMT+5:30")
        #expect(TravelLogic.zoneLabel("Europe/London", now: now) == "London · GMT+1")
        #expect(TravelLogic.zoneLabel("America/Los_Angeles", now: now) == "Los Angeles · GMT-7")
        #expect(TravelLogic.zoneLabel("UTC", now: now) == "UTC · GMT")
        #expect(TravelLogic.zoneLabel(nil, now: now) == nil)
        #expect(TravelLogic.zoneLabel("Mars/Olympus", now: now) == nil)
        #expect(TravelLogic.deviceZoneDiffers("Australia/Sydney", now: now, device: TimeZone(identifier: "UTC")!))
        #expect(!TravelLogic.deviceZoneDiffers("Australia/Sydney", now: now, device: TimeZone(identifier: "Australia/Melbourne")!))
        #expect(!TravelLogic.deviceZoneDiffers(nil, now: now))
    }

    @Test func todayUsesEventZone() {
        // 23:30 UTC on the 2nd is already the 3rd in Sydney.
        #expect(TravelLogic.today(tz: "Australia/Sydney", now: Time.parse("2026-10-02T23:30:00Z")!) == today)
        #expect(TravelLogic.today(tz: "UTC", now: Time.parse("2026-10-02T23:30:00Z")!) == today.adding(days: -1))
    }

    @Test func timeSplitting() {
        #expect(TravelLogic.splitTime("7:25 AM") == ("7:25", "AM"))
        #expect(TravelLogic.splitTime("7:25\u{202F}PM") == ("7:25", "PM"))
        #expect(TravelLogic.splitTime("19:25") == ("19:25", nil))
        #expect(TravelLogic.splitTime(nil) == (nil, nil))
    }

    @Test func pickupStates() {
        #expect(TravelLogic.pickup("awaiting_pickup")?.tone == .warning)
        #expect(TravelLogic.pickup("collected")?.label == "Picked up")
        #expect(TravelLogic.pickup("checked_in")?.tone == .info)
        #expect(TravelLogic.pickup("pickup_not_needed")?.tone == .neutral)
        #expect(TravelLogic.pickup(nil) == nil)
    }

    @Test func accessibilityLabelReadsLikeASentence() {
        let e = entries[2]
        let label = TravelLogic.accessibilityLabel(e, time: "11:50 AM")
        #expect(label == "Maya, unaccompanied minor, arrives 11:50 AM, AKL → SYD, NZ103, Awaiting pickup, Team Blue, Mentors")
        #expect(TravelLogic.accessibilityLabel(entries[9], time: nil).contains("departs unscheduled"))
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
