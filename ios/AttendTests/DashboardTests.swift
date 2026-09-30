import Foundation
import Testing
@testable import Attend

/// Event-sized fixtures for Home and Announcements (a port of Android's `OrganizerSamples`).
/// "Now" is Saturday 3 Oct 2026, 11:30 in Sydney.
extension Fixtures {
    enum Home {
        static let now = Fixtures.now
        static func iso(_ minutes: Double) -> String { Fixtures.iso(minutes) }

        private static let first = ["Sam", "Arjun", "Maya", "Leo", "Priya", "Noah", "Zara", "Kai", "Ella", "Oliver", "Isla", "Jack", "Mia", "Ethan",
                                    "Aisha", "Lucas", "Chloe", "Ravi", "Grace", "Hugo", "Nina", "Theo", "Ivy", "Omar"]
        private static let last = ["Lee", "Patel", "Chen", "Nguyen", "Sharma", "Williams", "Ahmed", "Tanaka", "Brown", "Smith", "Wilson", "Taylor"]

        /// 142 registrations: 120 complete (84 checked in, 12 of them in the last hour), 12 still onboarding,
        /// 6 withdrawn, 4 rejected. 63 have had Saturday lunch; 22 were collected at the airport.
        static let participants: [Participant] = (0..<142).map { i in
            let fn = first[i % first.count]
            let ln = last[(i / first.count + i) % last.count]
            let status: String = switch i {
            case ..<120: "complete"
            case ..<124: "invited"
            case ..<129: "in_progress"
            case ..<132: "awaiting_guardian"
            case ..<138: "withdrawn"
            default: "rejected"
            }
            let checkedIn = i < 84
            let checkedInAt = checkedIn ? iso(i < 12 ? -(Double(i) * 4 + 2) : -(70 + Double(i) * 3)) : nil
            var scans: [ContextScanSummary] = []
            if checkedIn {
                scans.append(ContextScanSummary(scanContextId: "c1", scanContextName: "Check-in desk", checksIn: true, scanCount: 1,
                                                firstScannedAt: checkedInAt, lastScannedAt: checkedInAt))
            }
            if (40..<62).contains(i) {
                scans.append(ContextScanSummary(scanContextId: "c2", scanContextName: "Airport pickup", isTravelPickup: true, scanCount: 1,
                                                firstScannedAt: iso(-200), lastScannedAt: iso(-200)))
            }
            if checkedIn && i % 4 != 3 {
                scans.append(ContextScanSummary(scanContextId: "c3", scanContextName: "Saturday lunch", scanCount: 1,
                                                firstScannedAt: iso(-5), lastScannedAt: iso(-5)))
            }
            return Participant(
                participantId: String(format: "%08x-e5f6-4a7b-8c9d-0e1f2a3b4c5d", 0x5a1b_2000 + i * 7919),
                participantEventId: String(format: "%08x-8d7e-4f60-9a1b-2c3d4e5f6a7b", 0x3b1f_9000 + i * 104_729),
                displayName: fn, fullName: "\(fn) \(ln)", email: "\(fn.lowercased()).\(ln.lowercased())\(i)@example.com",
                slackUserId: i % 30 == 29 ? nil : String(format: "U%05d", i),
                status: status, checkedInAt: checkedInAt,
                hasAnaphylaxisRisk: [3, 57, 101].contains(i), requiresRefrigeration: i == 3, highSupportFlag: [8, 90].contains(i),
                scansByContext: scans
            )
        }

        static let travel: TravelCalendar = {
            let rows: [(Int, String, String, Double?, String?, String, String?, String?, Bool)] = [
                (0, "inbound", "plane", -95, "2026-10-03", "MEL → SYD", "QF401", "collected", false),
                (1, "inbound", "plane", -60, "2026-10-03", "BNE → SYD", "VA914", "checked_in", false),
                (2, "inbound", "plane", 20, "2026-10-03", "AKL → SYD", "NZ103", "awaiting_pickup", true),
                (3, "inbound", "train", 45, "2026-10-03", "Central → Venue", "NSW TrainLink", "awaiting_pickup", false),
                (4, "inbound", "plane", 75, "2026-10-03", "PER → SYD", "QF566 · QF412", "awaiting_pickup", false),
                (5, "inbound", "car", 120, "2026-10-03", "Parent drop-off", nil, "pickup_not_needed", false),
                (6, "inbound", "bus", 150, "2026-10-03", "Canberra → Sydney", "Murrays", "awaiting_pickup", false),
                (7, "outbound", "plane", 60 * 24 + 360, "2026-10-04", "SYD → MEL", "JQ508", nil, false),
                (8, "outbound", "plane", 60 * 48 - 60, "2026-10-05", "SYD → AKL", "NZ104", nil, true),
                (9, "outbound", "train", 60 * 48 + 30, "2026-10-05", "Venue → Central", nil, nil, false),
                (10, "inbound", "plane", nil, nil, "Details pending", nil, "awaiting_pickup", false),
            ]
            let entries = rows.map { i, dir, mode, minutes, date, route, ref, pickup, um in
                let p = participants[i * 5 + 40]
                return TravelEntry(id: "tr\(i)", participantId: p.participantId, participantEventId: p.participantEventId,
                                   participantName: p.fullName, participantPreferredName: p.displayName, direction: dir, mode: mode,
                                   primaryTimeAt: minutes.map(iso), agendaDate: date, route: route, reference: ref,
                                   pickupState: pickup, isUnaccompaniedMinor: um)
            }
            return TravelCalendar(eventTimezone: "Australia/Sydney", dates: ["2026-10-03", "2026-10-04", "2026-10-05"], entries: entries,
                                  counts: TravelCounts(total: 11, inbound: 8, outbound: 3, scheduled: 10, unscheduled: 1, awaitingPickup: 5,
                                                       collected: 1, checkedIn: 1, pickupNotNeeded: 1))
        }()

        /// Latest scans feed (what a read-only role sees instead of the roster): 40 today, 20 yesterday.
        static let scans: [Scan] = (0..<40).map { i in
            let ctx = i % 5 == 0 ? ScanContextRef(id: "c2", name: "Airport pickup", isTravelPickup: true)
                : i % 3 == 0 ? ScanContextRef(id: "c1", name: "Check-in desk", checksIn: true)
                : ScanContextRef(id: "c3", name: "Saturday lunch")
            let p = participants[i % 2 == 0 ? (i / 2 % 11) * 5 + 40 : (i * 7) % 120]
            return Scan(id: "s\(i)", participantId: p.participantId, participantEventId: p.participantEventId,
                        scannedAt: iso(-(Double(i) * 6 + 1)), scannedBy: i % 2 == 0 ? "Heidi" : "Orpheus Dino", scanContext: ctx)
        } + (0..<20).map { i in
            Scan(id: "y\(i)", participantEventId: participants[i].participantEventId, scannedAt: iso(-(60 * 24 + Double(i))),
                 scanContext: ScanContextRef(id: "c1", name: "Check-in desk", checksIn: true))
        }

        static let blasts = [
            SlackBlast(id: "b3", message: "Buses to the hotel leave from the main entrance at <b>9pm sharp</b>.<br>Bring everything with you!",
                       status: "in_progress", recipientCount: 116, sentCount: 71, failedCount: 0, createdAt: iso(-1), sentBy: "Heidi"),
            SlackBlast(id: "b1", message: "Lunch is ready in the atrium! 🌮", status: "completed", recipientCount: 120, sentCount: 118,
                       failedCount: 2, createdAt: iso(-40), sentBy: "Orpheus Dino"),
            SlackBlast(id: "bf", message: "Test message", status: "failed", createdAt: iso(-60 * 26), sentBy: "Heidi"),
        ]
    }
}

@Suite struct DashboardLogicTests {
    let now = Fixtures.Home.now
    let stats = EventStats.from(Fixtures.Home.participants, now: Fixtures.Home.now)
    let tz = "Australia/Sydney"

    @Test func heroNumbersAddUp() {
        #expect(stats.confirmed == 120)
        #expect(DashboardLogic.checkedInConfirmed(stats) == 84)
        #expect(stats.notArrived == 36)
        #expect(stats.confirmed == DashboardLogic.checkedInConfirmed(stats) + stats.notArrived)
        #expect(stats.checkedInLastHour == 12)
        #expect(stats.registered == 132)
        #expect(DashboardLogic.notComplete(stats) == 12)
        #expect(stats.withdrawn == 10)
        #expect(DashboardLogic.needsAttention(Fixtures.Home.participants) == 5)
    }

    @Test func contextProgressFollowsPositionAndMarksActive() {
        let lunchNow = Fixtures.contexts.map { c in
            var c = c
            if c.id == "c3" { c.startsAt = "2026-10-03T11:00:00+10:00"; c.endsAt = "2026-10-03T13:00:00+10:00" }
            return c
        }.reversed()
        let rows = DashboardLogic.contextProgress(Array(lunchNow), stats: stats, now: now)
        #expect(rows.map(\.context.id) == ["c1", "c2", "c3"])
        #expect(rows.map(\.count) == [84, 22, 63])
        #expect(rows.map(\.active) == [false, false, true])
        #expect(abs(rows[0].fraction - 0.7) < 0.001)
    }

    @Test func fractionIsSafeWithNoConfirmed() {
        #expect(ContextProgress(context: Fixtures.contexts[0], count: 5, total: 0, active: false).fraction == 0)
        #expect(ContextProgress(context: Fixtures.contexts[0], count: 9, total: 5, active: false).fraction == 1)
    }

    @Test func recentCheckInsAreNewestFirstAndSkipWithdrawn() {
        var withdrawnRecent = Fixtures.Home.participants[135]
        withdrawnRecent.checkedInAt = Time.iso(now)
        let recent = DashboardLogic.recentCheckIns(Fixtures.Home.participants + [withdrawnRecent], limit: 5)
        #expect(recent.count == 5)
        #expect(recent.map(\.participantEventId) == Fixtures.Home.participants.prefix(5).map(\.participantEventId))
    }

    @Test func nextArrivalsSkipCollectedLateAndUnscheduled() throws {
        let a = try #require(DashboardLogic.arrivals(Fixtures.Home.travel, now: now))
        #expect(a.next.map(\.id) == ["tr2", "tr3", "tr4"])
        #expect(a.awaitingPickup == 5)
        #expect(a.collected == 1)
        #expect(a.checkedIn == 1)
        // Five hours later only the bus is still due (and it's within the 3 h grace window).
        let later = try #require(DashboardLogic.arrivals(Fixtures.Home.travel, now: now.addingTimeInterval(5 * 3600)))
        #expect(later.next.map(\.id) == ["tr6"])
        #expect(DashboardLogic.arrivals(nil, now: now) == nil)
        #expect(DashboardLogic.arrivals(TravelCalendar(), now: now)?.next.isEmpty == true)
    }

    @Test func scanFeedCountsOnlyTodayInEventZone() {
        let feed = DashboardLogic.scanFeed(Fixtures.Home.scans, tz: tz, now: now)
        #expect(feed.today == 40)
        #expect(!feed.capped)
        #expect(feed.recent.count == 6)
        #expect(feed.recent.first?.id == "s0")
    }

    @Test func scanFeedIsCappedWhenAllHundredAreToday() {
        let scans = (0..<100).map { i in
            Scan(id: "s\(i)", participantEventId: "p\(i % 30)", scannedAt: Time.iso(now.addingTimeInterval(-Double(i) * 30)))
        }
        let feed = DashboardLogic.scanFeed(scans, tz: tz, now: now)
        #expect(feed.capped)
        #expect(feed.uniquePeopleToday == 30)
    }

    @Test func countdown() {
        let start = "2026-10-02T22:00:00Z" // Sat 3 Oct 08:00 Sydney
        func at(_ iso: String) -> Date { Time.parse(iso)! }
        #expect(DashboardLogic.countdown(start, tz: tz, now: at("2026-09-30T02:00:00Z")) == "Starts in 3 days")
        #expect(DashboardLogic.countdown(start, tz: tz, now: at("2026-10-02T02:00:00Z")) == "Starts tomorrow")
        #expect(DashboardLogic.countdown(start, tz: tz, now: at("2026-10-02T15:30:00Z")) == "Starts in 6 h")
        #expect(DashboardLogic.countdown(start, tz: tz, now: at("2026-10-02T21:45:00Z")) == "Starts in 15 min")
        #expect(DashboardLogic.countdown(start, tz: tz, now: at("2026-10-02T21:59:50Z")) == "Starts in 1 min")
        #expect(DashboardLogic.countdown(start, tz: tz, now: at("2026-10-02T22:00:00Z")) == nil)
        #expect(DashboardLogic.countdown(nil, tz: tz, now: now) == nil)
    }

    @Test func ended() {
        let end = "2026-10-04T06:00:00Z" // Sun 4 Oct 17:00 Sydney
        func at(_ iso: String) -> Date { Time.parse(iso)! }
        #expect(DashboardLogic.ended(end, tz: tz, now: at("2026-10-04T08:00:00Z")) == "Ended today")
        #expect(DashboardLogic.ended(end, tz: tz, now: at("2026-10-05T01:00:00Z")) == "Ended yesterday")
        #expect(DashboardLogic.ended(end, tz: tz, now: at("2026-10-08T02:00:00Z")) == "Ended 4 days ago")
        #expect(DashboardLogic.ended(end, tz: tz, now: now) == nil)
    }

    @Test func labels() {
        // The date range is locale-formatted; the city is appended after a middle dot.
        let range = Time.range(Fixtures.event.startsAt, Fixtures.event.endsAt, tz: Fixtures.event.timezone)
        #expect(range != nil)
        #expect(DashboardLogic.subtitle(Fixtures.event) == "\(range!) · Sydney")
        var cityless = Fixtures.event
        cityless.locationCity = " "
        cityless.startsAt = nil
        #expect(DashboardLogic.subtitle(cityless) == nil)
        #expect(EventLogic.roleLabel("read_only") == "Read only")
        #expect(EventLogic.roleLabel("some_thing") == "Some thing")
        #expect(DashboardLogic.updated(now.addingTimeInterval(-10), now: now) == "Updated just now")
        #expect(DashboardLogic.updated(now.addingTimeInterval(-300), now: now) == "Updated 5 min ago")
        #expect(DashboardLogic.updated(nil, now: now) == nil)
    }
}

@Suite struct BlastLogicTests {
    private func blast(_ status: String, recipients: Int = 120, sent: Int = 0, failed: Int = 0) -> SlackBlast {
        SlackBlast(id: "b", message: "m", status: status, recipientCount: recipients, sentCount: sent, failedCount: failed)
    }

    @Test func htmlKeepsLineBreaksAndEscapes() {
        #expect(BlastLogic.toHtml("  Lunch & snacks <now>  ") == "Lunch &amp; snacks &lt;now&gt;")
        #expect(BlastLogic.toHtml("Line one\nLine two") == "Line one<br>Line two")
        #expect(BlastLogic.toHtml("Para one\n\n\nPara two\r\nmore") == "<p>Para one</p><p>Para two<br>more</p>")
        #expect(BlastLogic.toHtml("Trailing   \nspaces") == "Trailing<br>spaces")
        #expect(BlastLogic.toHtml("   \n  ") == "")
    }

    @Test func plainRoundTrips() {
        let text = "Buses at 9pm & don't be late.\nBring <everything>!\n\nThanks"
        #expect(BlastLogic.toPlain(BlastLogic.toHtml(text)) == text)
        #expect(BlastLogic.toPlain("<p>Hi</p><ul><li>one</li><li>two</li></ul>") == "Hi\n\n• one\n• two")
        #expect(BlastLogic.toPlain("Buses at <b>9pm sharp</b>.<br/>Bring it") == "Buses at 9pm sharp.\nBring it")
        #expect(BlastLogic.toPlain("A<BR>B&nbsp;&quot;C&quot; &#39;D&#x27;") == "A\nB \"C\" 'D'")
    }

    @Test func progressText() {
        #expect(BlastLogic.progressText(blast("completed", sent: 118, failed: 2)) == "118/120 sent · 2 failed")
        #expect(BlastLogic.progressText(blast("completed", sent: 120)) == "Sent to all 120")
        #expect(BlastLogic.progressText(blast("in_progress", sent: 64)) == "64 of 120 sent")
        #expect(BlastLogic.progressText(blast("in_progress", sent: 64, failed: 3)) == "64 of 120 sent · 3 failed")
        #expect(BlastLogic.progressText(blast("pending")) == "Queued for 120 people…")
        #expect(BlastLogic.progressText(blast("pending", recipients: 0)) == "Queued…")
        #expect(BlastLogic.progressText(blast("failed", recipients: 0)) == "Couldn't send")
    }

    @Test func fractionAndActive() {
        #expect(abs(BlastLogic.fraction(blast("in_progress", sent: 55, failed: 5)) - 0.5) < 0.001)
        #expect(BlastLogic.fraction(blast("pending", recipients: 0)) == 0)
        #expect(BlastLogic.isActive(blast("pending")))
        #expect(BlastLogic.isActive(blast("in_progress")))
        #expect(!BlastLogic.isActive(blast("completed")))
        #expect(!BlastLogic.isActive(blast("failed")))
    }

    @Test func recipientsAreConfirmedWithSlack() {
        // 120 complete, of whom every 30th has no Slack link (i = 29, 59, 89, 119).
        #expect(BlastLogic.estimateRecipients(Fixtures.Home.participants) == 116)
    }

    @Test func upsertReplacesOrPrepends() {
        let list = Fixtures.Home.blasts
        var updated = list[0]
        updated.status = "completed"
        updated.sentCount = 116
        #expect(BlastLogic.upsert(list, updated)[0] == updated)
        #expect(BlastLogic.upsert(list, updated).count == list.count)
        var fresh = blast("pending")
        fresh.id = "new"
        #expect(BlastLogic.upsert(list, fresh).first?.id == "new")
        #expect(BlastLogic.upsert(list, fresh).count == list.count + 1)
    }
}
