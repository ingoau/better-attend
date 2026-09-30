import Foundation
import SwiftUI
import Testing
import WidgetKit
@testable import Attend

extension Fixtures {
    /// The Android `WidgetSnapshotsTest` travel sample.
    static var widgetTravel: TravelWidgetData { WidgetSamples.organizer.travel! }
}

@Suite struct WidgetLabelTests {
    let now = Fixtures.now

    /// Port of Android `WidgetSnapshotsTest.labels()`.
    @Test func labels() {
        #expect(WidgetLabels.updated("2026-10-03T01:20:00Z", now: now) == "Updated 10 min ago")
        #expect(WidgetLabels.updated(nil, now: now) == "Not synced yet")
        let t = Fixtures.widgetTravel
        #expect(WidgetLabels.nextArrival(t, tz: "Australia/Sydney", compact: false).hasPrefix("Next: Sam · "))
        var none = t
        none.nextArrivalAt = nil
        #expect(WidgetLabels.nextArrival(none, tz: nil, compact: true) == "No more arrivals scheduled")
    }

    @Test func updatedJustNow() {
        #expect(WidgetLabels.updated("2026-10-03T01:29:50Z", now: now) == "Updated just now")
        #expect(WidgetLabels.updated("2026-10-02T23:00:00Z", now: now) == "Updated 2 h ago")
    }

    @Test func nextArrivalFullAndCompact() {
        var t = Fixtures.widgetTravel
        let time = Time.time(t.nextArrivalAt, tz: "Australia/Sydney")!
        #expect(WidgetLabels.nextArrival(t, tz: "Australia/Sydney", compact: false) == "Next: Sam · \(time) · QF401")
        #expect(WidgetLabels.nextArrival(t, tz: "Australia/Sydney", compact: true) == "Next \(time) · Sam")
        t.nextArrivalMinor = true
        t.nextArrivalReference = nil
        t.nextArrivalName = nil
        #expect(WidgetLabels.nextArrival(t, tz: "Australia/Sydney", compact: false) == "Next: Someone (minor) · \(time)")
    }

    @Test func checkInStatus() {
        var org = WidgetSamples.organizer
        #expect(WidgetLabels.checkInStatus(org, compact: false) == "36 not here yet")
        #expect(WidgetLabels.checkInAccessibility(org) == "84 of 120 checked in")
        org.notArrived = 0
        #expect(WidgetLabels.checkInStatus(org, compact: true) == "Everyone's here")
        org.checkedIn = 0
        #expect(org.nobodyYet)
        #expect(WidgetLabels.checkInStatus(org, compact: true) == "expected")
        #expect(WidgetLabels.checkInStatus(org, compact: false) == "No one checked in yet")
        #expect(WidgetLabels.checkInAccessibility(org) == "120 expected, no one checked in yet")
    }

    @Test func relativeTimes() {
        #expect(WidgetLabels.relative(now.addingTimeInterval(30), now: now) == "now")
        #expect(WidgetLabels.relative(now.addingTimeInterval(25 * 60), now: now) == "in 25 min")
        #expect(WidgetLabels.relative(now.addingTimeInterval(130 * 60), now: now) == "in 2 h")
        #expect(WidgetLabels.relative(now.addingTimeInterval(-10 * 60), now: now) == "10 min ago")
        #expect(WidgetLabels.relative(now.addingTimeInterval(-90 * 60), now: now) == "1 h ago")
    }

    @Test func ticketCopy() {
        #expect(WidgetLabels.noTicket(isParticipant: true).title == "No upcoming events")
        #expect(WidgetLabels.noTicket(isParticipant: false).title != WidgetLabels.noTicket(isParticipant: true).title)
        #expect(WidgetLabels.grouped("A1B2C3D4") == "A1B2 C3D4")
        #expect(WidgetLabels.grouped("ABC") == "ABC")
        #expect(WidgetLabels.ticketCountdown(WidgetSamples.ticketLive.ticket!, now: now) == "Happening now")
        #expect(WidgetLabels.ticketCountdown(WidgetSamples.ticketSoon.ticket!, now: now) == "Starts in 35 min")
        #expect(WidgetLabels.ticketCountdown(WidgetSamples.ticket, now: now) == "in 3 days")
    }
}

@Suite struct WidgetScheduleTests {
    let now = Fixtures.now

    @Test func organizerStepsEveryFiveMinutes() {
        let dates = WidgetSchedule.stepDates(now: now)
        #expect(dates.count == 6)
        #expect(dates.first == now)
        #expect(dates.last == now.addingTimeInterval(25 * 60))
    }

    @Test func ticketBoundaries() {
        let t = WidgetSamples.ticket // starts Tue 6 Oct 09:00 Sydney, ends Wed 7 Oct 17:00
        let dates = WidgetSchedule.ticketBoundaries(t, now: now)
        #expect(dates == dates.sorted())
        #expect(Set(dates).count == dates.count)
        #expect(dates.allSatisfy { $0 > now && $0 <= now.addingTimeInterval(7 * 86_400) })
        // Next local midnight in Sydney (AEST, +10:00 until 4 Oct).
        #expect(dates.first == Time.parse("2026-10-03T14:00:00Z"))
        let start = Time.parse(t.startsAt)!
        #expect(dates.contains(start.addingTimeInterval(-3600)))
        #expect(dates.contains(start))
        #expect(dates.contains(Time.parse(t.endsAt)!))
    }

    @Test func liveTicketOnlyLooksAhead() {
        let t = WidgetSamples.ticketLive.ticket!
        let dates = WidgetSchedule.ticketBoundaries(t, now: now)
        #expect(!dates.contains(Time.parse(t.startsAt)!))
        #expect(dates.contains(Time.parse(t.endsAt)!))
    }

    @Test func samplesShiftKeepsRelativeTimes() {
        let later = now.addingTimeInterval(400 * 86_400 + 1234)
        let s = WidgetSamples.snapshot(relativeTo: later)
        #expect(WidgetLabels.updated(s.organizer?.updatedAt, now: later) == "Updated 4 min ago")
        #expect(Time.parse(s.ticket?.startsAt)!.timeIntervalSince(later) == Time.parse(WidgetSamples.ticket.startsAt)!.timeIntervalSince(now))
        let soon = WidgetSamples.snapshot(relativeTo: later, from: WidgetSamples.ticketSoon)
        #expect(WidgetLabels.ticketCountdown(soon.ticket!, now: later) == "Starts in 35 min")
    }
}

@MainActor
@Suite struct WidgetLinkTests {
    @Test func showMyTicketOpensConfirmedPass() {
        #expect(ShowMyTicketIntent.destination(WidgetSamples.snapshot) == DeepLink.ticket("sample"))
        #expect(ShowMyTicketIntent.destination(WidgetSamples.ticketIncomplete) == DeepLink.tickets)
        #expect(ShowMyTicketIntent.destination(WidgetSamples.noTicket) == DeepLink.tickets)
        #expect(ShowMyTicketIntent.destination(.signedOut) == DeepLink.tickets)
    }

    @Test func routerHandlesEveryWidgetLink() {
        let tabs = AppTab.allCases
        for (url, tab) in [(DeepLink.home, AppTab.home), (DeepLink.scan, .scan), (DeepLink.travel, .travel),
                           (DeepLink.people, .people), (DeepLink.tickets, .tickets)] {
            let router = Router()
            router.tab = tab == .home ? .people : .home
            #expect(router.handle(url, available: tabs, selectedEventId: nil))
            #expect(router.tab == tab)
        }
        let router = Router()
        #expect(router.handle(DeepLink.ticket("abc"), available: tabs, selectedEventId: nil))
        #expect(router.tab == .tickets)
        #expect(router.paths[.tickets] == [.ticket(id: "abc")])
    }
}

// MARK: - Visual snapshots

/// Renders every widget kind × family × state in light and dark into contact sheets (PNG) so the
/// layouts can be reviewed without a Home Screen. Output: `$WIDGET_RENDER_DIR` (pass
/// `TEST_RUNNER_WIDGET_RENDER_DIR=/some/dir` to xcodebuild) or `tmp/WidgetRenders` in the simulator.
@MainActor
@Suite struct WidgetRenderTests {
    static let dir: URL = {
        let env = ProcessInfo.processInfo.environment["WIDGET_RENDER_DIR"]
        let url = env.map { URL(fileURLWithPath: $0) } ?? FileManager.default.temporaryDirectory.appending(path: "WidgetRenders")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    let now = Date()
    func at(_ s: WidgetSnapshot) -> WidgetSnapshot { WidgetSamples.snapshot(relativeTo: now, from: s) }

    @Test(arguments: [false, true]) func checkIn(dark: Bool) throws {
        func v(_ s: WidgetSnapshot, _ f: WidgetFamily, _ label: String) -> SheetTile {
            SheetTile(label: label, family: f, content: AnyView(CheckInWidgetView(snapshot: at(s), family: f, now: now)))
        }
        let accessories = { (s: WidgetSnapshot, name: String) in
            [v(s, .accessoryCircular, "\(name) circular"), v(s, .accessoryRectangular, "\(name) rectangular"), v(s, .accessoryInline, "\(name) inline")]
        }
        try render("checkin", dark: dark, rows: [
            [v(WidgetSamples.snapshot, .systemSmall, "small"), v(WidgetSamples.snapshot, .systemMedium, "medium")],
            [v(WidgetSamples.snapshot, .systemLarge, "large"), v(WidgetSamples.beforeCheckIn, .systemLarge, "large · before check-in")],
            [v(WidgetSamples.beforeCheckIn, .systemSmall, "small · before"), v(WidgetSamples.beforeCheckIn, .systemMedium, "medium · before")],
            accessories(WidgetSamples.snapshot, "") + accessories(WidgetSamples.beforeCheckIn, "before"),
            [v(WidgetSamples.everyoneHere, .systemSmall, "everyone here"), v(WidgetSamples.noCounts, .systemSmall, "no counts yet"),
             v(WidgetSamples.noEvent, .systemSmall, "no event"), v(.signedOut, .systemSmall, "signed out")],
            [v(.signedOut, .systemMedium, "medium · signed out"), v(WidgetSamples.noCounts, .systemMedium, "medium · no counts")],
            accessories(.signedOut, "signed out") + accessories(WidgetSamples.noCounts, "no counts"),
        ])
    }

    @Test(arguments: [false, true]) func arrivals(dark: Bool) throws {
        func v(_ s: WidgetSnapshot, _ f: WidgetFamily, _ label: String) -> SheetTile {
            SheetTile(label: label, family: f, content: AnyView(ArrivalsWidgetView(snapshot: at(s), family: f, now: now)))
        }
        try render("arrivals", dark: dark, rows: [
            [v(WidgetSamples.snapshot, .systemSmall, "small"), v(WidgetSamples.snapshot, .systemMedium, "medium")],
            [v(WidgetSamples.minorArrival, .systemSmall, "small · minor"), v(WidgetSamples.minorArrival, .systemMedium, "medium · minor")],
            [v(WidgetSamples.noArrivals, .systemSmall, "small · none left"), v(WidgetSamples.noArrivals, .systemMedium, "medium · none left")],
            [v(WidgetSamples.noTravel, .systemSmall, "no travel"), v(WidgetSamples.noEvent, .systemSmall, "no event"), v(.signedOut, .systemSmall, "signed out")],
            [v(.signedOut, .systemMedium, "medium · signed out"), v(WidgetSamples.noTravel, .systemMedium, "medium · no travel")],
        ])
    }

    @Test(arguments: [false, true]) func quickScan(dark: Bool) throws {
        func v(_ s: WidgetSnapshot, _ f: WidgetFamily, _ label: String) -> SheetTile {
            SheetTile(label: label, family: f, content: AnyView(QuickScanWidgetView(snapshot: at(s), family: f)), quickScan: true)
        }
        try render("quickscan", dark: dark, rows: [
            [v(WidgetSamples.snapshot, .systemSmall, "small"), v(.signedOut, .systemSmall, "signed out"), v(WidgetSamples.noEvent, .systemSmall, "no event")],
            [v(WidgetSamples.snapshot, .accessoryCircular, "circular")],
        ])
    }

    @Test(arguments: [false, true]) func ticket(dark: Bool) throws {
        func v(_ s: WidgetSnapshot, _ f: WidgetFamily, _ label: String) -> SheetTile {
            SheetTile(label: label, family: f, content: AnyView(TicketWidgetView(snapshot: at(s), family: f, now: now)))
        }
        let row = { (s: WidgetSnapshot, name: String) in
            [v(s, .systemSmall, "small · \(name)"), v(s, .systemMedium, "medium · \(name)"), v(s, .accessoryRectangular, "rect · \(name)")]
        }
        try render("ticket", dark: dark, rows: [
            row(WidgetSamples.snapshot, "upcoming") + [v(WidgetSamples.snapshot, .accessoryInline, "inline")],
            row(WidgetSamples.ticketSoon, "doors soon"),
            row(WidgetSamples.ticketLive, "live"),
            row(WidgetSamples.ticketIncomplete, "incomplete"),
            [v(WidgetSamples.noTicket, .systemSmall, "no ticket · participant"), v(WidgetSamples.noTicketNotParticipant, .systemSmall, "no ticket · organizer"),
             v(.signedOut, .systemMedium, "signed out"), v(WidgetSamples.noTicket, .accessoryRectangular, "rect · no ticket")],
        ])
    }

    private func render(_ name: String, dark: Bool, rows: [[SheetTile]]) throws {
        let sheet = VStack(alignment: .leading, spacing: 20) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .top, spacing: 20) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, tile in tile }
                }
            }
        }
        .padding(24)
        .background(dark ? Color(white: 0.05) : Color(white: 0.9))
        .environment(\.colorScheme, dark ? .dark : .light)

        let renderer = ImageRenderer(content: sheet)
        renderer.scale = 2
        let data = try #require(renderer.uiImage?.pngData())
        let url = Self.dir.appending(path: "widgets_\(name)_\(dark ? "dark" : "light").png")
        try data.write(to: url)
        print("Widget render: \(url.path)")
    }
}

/// One widget on the contact sheet, framed at iPhone 17 Pro sizes with the system content margins.
private struct SheetTile: View {
    var label: String
    var family: WidgetFamily
    var content: AnyView
    var quickScan = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if isAccessory {
                content
                    .frame(width: size.width, height: size.height)
                    .foregroundStyle(.white)
                    .environment(\.colorScheme, .dark)
                    .padding(10)
                    .background(Color(red: 0.16, green: 0.22, blue: 0.36), in: .rect(cornerRadius: 12))
            } else {
                content
                    .padding(16)
                    .frame(width: size.width, height: size.height)
                    .background { background }
                    .clipShape(.rect(cornerRadius: 24, style: .continuous))
            }
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private var isAccessory: Bool {
        [.accessoryCircular, .accessoryRectangular, .accessoryInline].contains(family)
    }

    @ViewBuilder private var background: some View {
        if quickScan {
            LinearGradient(colors: [WidgetPalette.accent, WidgetPalette.accentDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            WidgetPalette.background
        }
    }

    private var size: CGSize {
        switch family {
        case .systemSmall: CGSize(width: 170, height: 170)
        case .systemMedium: CGSize(width: 364, height: 170)
        case .systemLarge: CGSize(width: 364, height: 382)
        case .accessoryCircular: CGSize(width: 72, height: 72)
        case .accessoryRectangular: CGSize(width: 172, height: 76)
        default: CGSize(width: 257, height: 26)
        }
    }
}
