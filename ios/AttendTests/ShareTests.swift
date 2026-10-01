import Foundation
import SwiftUI
import Testing
@testable import Attend

/// Port of Android `ShareStatsTest`.
@Suite struct ShareStatsTests {
    let now = Fixtures.Home.now
    let event = Fixtures.event
    let stats = EventStats.from(Fixtures.Home.participants, now: Fixtures.Home.now)
    var contexts: [ContextProgress] { DashboardLogic.contextProgress(Fixtures.contexts, stats: stats, now: now) }

    private func ids(at: Date? = nil) -> [String] {
        ShareStats.available(event: event, stats: stats, contexts: contexts, arrivals: nil, feed: nil, now: at ?? now).map(\.id)
    }

    @Test func liveEventSharesCheckInsAndEveryTile() throws {
        let all = ShareStats.available(event: event, stats: stats, contexts: contexts,
                                       arrivals: DashboardLogic.arrivals(Fixtures.Home.travel, now: now), feed: nil, now: now)
        let checkedIn = try #require(all.first)
        #expect(checkedIn.id == ShareStats.checkedIn)
        #expect(checkedIn.value == 84)
        #expect(checkedIn.total == 120)
        #expect(ShareStats.heroId(event, now: now) == ShareStats.checkedIn)
        let allIds = Set(all.map(\.id))
        #expect(allIds.isSuperset(of: [ShareStats.registered, ShareStats.confirmed, ShareStats.notComplete, ShareStats.withdrawn, ShareStats.toCollect]))
        #expect(Fixtures.contexts.allSatisfy { allIds.contains(ShareStats.context($0.id)) })
        #expect(all.first { $0.id == ShareStats.lastHour }?.display == "+12")
    }

    @Test func upcomingEventSharesRegistrationsInstead() {
        let before = Time.parse("2026-09-30T02:00:00Z")!
        #expect(ShareStats.heroId(event, now: before) == ShareStats.registrations)
        #expect(ids(at: before).first == ShareStats.registrations)
        #expect(!ids(at: before).contains(ShareStats.checkedIn))
    }

    @Test func pastEventDropsLastHour() {
        let after = Time.parse("2026-10-08T02:00:00Z")!
        #expect(ids(at: after).contains(ShareStats.checkedIn))
        #expect(!ids(at: after).contains(ShareStats.lastHour))
    }

    @Test func limitedRolesShareScanCounts() {
        let feed = DashboardLogic.scanFeed(Fixtures.Home.scans, tz: event.timezone, now: now)
        let all = ShareStats.available(event: event, stats: nil, contexts: [], arrivals: nil, feed: feed, now: now)
        #expect(all.map(\.id) == [ShareStats.scansToday, ShareStats.peopleScanned])
    }

    @Test func toggleKeepsOrderAndLimits() {
        #expect(ShareStats.toggle(["a"], "b") == ["a", "b"])
        #expect(ShareStats.toggle(["a", "b"], "a") == ["b"])
        // The last number can't be removed.
        #expect(ShareStats.toggle(["a"], "a") == ["a"])
        let full = (1...ShareStats.maxOnCard).map { "s\($0)" }
        #expect(ShareStats.toggle(full, "extra") == full)
    }

    @Test func layoutColumns() {
        #expect((1...6).map { ShareCardLayout.row.columns($0) } == [1, 2, 3, 2, 3, 3])
        #expect((1...3).map { ShareCardLayout.grid.columns($0) } == [1, 2, 2])
        #expect(ShareCardLayout.list.columns(4) == 1)
    }

    @Test func fractionNeedsATotal() {
        #expect(ShareStat(id: "x", label: "X", value: 5, icon: "person").fraction == nil)
        #expect(ShareStat(id: "x", label: "X", value: 5, icon: "person", total: 10).fraction == 0.5)
        #expect(ShareStat(id: "x", label: "X", value: 5, icon: "person", total: 0).fraction == nil)
    }

    @Test func savedFileNameIsSafeAndLocal() {
        // 01:30 UTC is 11:30 in Sydney.
        #expect(ShareStats.fileName(event, now: now) == "Campfire Sydney 2026-10-03 1130.png")
        var odd = event
        odd.name = "A/B: C?"
        #expect(ShareStats.fileName(odd, now: now) == "A B C 2026-10-03 1130.png")
    }

    /// Every style, layout and appearance renders to a PNG; the images land next to the widget renders for a look.
    @MainActor @Test func cardsRender() throws {
        let all = ShareStats.available(event: event, stats: stats, contexts: contexts,
                                       arrivals: DashboardLogic.arrivals(Fixtures.Home.travel, now: now), feed: nil, now: now)
        let env = ProcessInfo.processInfo.environment["WIDGET_RENDER_DIR"]
        let dir = env.map { URL(fileURLWithPath: $0) } ?? FileManager.default.temporaryDirectory.appending(path: "WidgetRenders")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for (i, style) in ShareCardStyle.allCases.enumerated() {
            for layout in ShareCardLayout.allCases {
                for dark in [false, true] {
                    var options = ShareCardOptions(dark: dark, style: style, layout: layout)
                    options.colour = ShareCardColour.allCases[i % ShareCardColour.allCases.count]
                    let stats = Array(all.prefix(layout == .list ? 4 : 5))
                    let data = try #require(ShareCardImage(event: event, stats: stats, options: options, dark: dark, now: now, width: 370).render())
                    try data.write(to: dir.appending(path: "share_\(style.rawValue)_\(layout.rawValue)_\(dark ? "dark" : "light").png"))
                }
            }
        }
        print("Share card renders: \(dir.path)")
    }
}
