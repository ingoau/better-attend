import Foundation
import Testing
@testable import Attend

private func member(_ id: String, _ name: String, here: Bool = true, status: String = "complete",
                    pronouns: String? = nil, anaphylaxis: Bool = false) -> Participant {
    Participant(participantId: "p-\(id)", participantEventId: id, displayName: String(name.split(separator: " ")[0]), fullName: name,
                email: "\(id)@example.com", pronouns: pronouns, status: status,
                checkedInAt: here ? "2026-10-03T00:00:00Z" : nil, hasAnaphylaxisRisk: anaphylaxis)
}

/// Zoe (here), Arjun (here), Maya (not here), Leo (here, withdrawn), Priya (here, awaiting guardian).
private let roster = [
    member("pe-zoe", "Zoe Adams", pronouns: "she/her"),
    member("pe-arjun", "Arjun Patel", anaphylaxis: true),
    member("pe-maya", "Maya Chen", here: false),
    member("pe-leo", "Leo Nguyen", status: "withdrawn"),
    member("pe-priya", "Priya Sharma", status: "awaiting_guardian"),
]

private let at = "2026-10-03T01:30:00Z"

@Suite struct RollCallLogicTests {
    private func session(_ expected: RollCallExpected = .checkedIn, scanPoint: RollCallScanPoint? = nil) -> RollCallSession {
        RollCallLogic.start(eventId: "e1", participants: roster, expected: expected, scanPoint: scanPoint, now: Fixtures.now)
    }

    @Test func checkedInExpectsActiveArrivalsInNameOrder() {
        #expect(RollCallLogic.expectedIds(roster, expected: .checkedIn) == ["pe-arjun", "pe-priya", "pe-zoe"])
        #expect(RollCallLogic.expectedCount(roster, expected: .checkedIn) == 3)
    }

    @Test func everyoneExpectsEveryActiveRegistration() {
        #expect(RollCallLogic.expectedIds(roster, expected: .everyone) == ["pe-arjun", "pe-maya", "pe-priya", "pe-zoe"])
        #expect(RollCallLogic.expectedCount(roster, expected: .everyone) == 4)
    }

    @Test func startFreezesTheListAndRecordsChoices() {
        let point = RollCallScanPoint(id: "c3", name: "Saturday lunch")
        let s = session(.checkedIn, scanPoint: point)
        #expect(s.eventId == "e1")
        #expect(s.startedAt == Time.iso(Fixtures.now))
        #expect(s.scanPoint == point)
        #expect(s.isRecordingScans)
        #expect(s.ticks.isEmpty)
        // Maya checks in after the roll call started: the list doesn't move.
        var later = roster
        later[2].checkedInAt = "2026-10-03T01:00:00Z"
        let entries = RollCallLogic.entries(s, roster: later)
        #expect(entries.map(\.id) == ["pe-arjun", "pe-priya", "pe-zoe"])
    }

    @Test func phoneOnlyHasNoScanPoint() {
        let s = session()
        #expect(s.scanPoint == nil)
        #expect(!s.isRecordingScans)
    }

    @Test func togglingTicksAndUnticks() {
        var s = session()
        let ticked = s.toggle("pe-zoe", at: at)
        #expect(ticked)
        #expect(s.isAccounted("pe-zoe"))
        #expect(s.tickTime("pe-zoe") == at)
        let unticked = s.toggle("pe-zoe", at: at)
        #expect(!unticked)
        #expect(!s.isAccounted("pe-zoe"))
        // Setting the same state twice changes nothing; people off the list can't be ticked by set.
        let first = s.set("pe-arjun", accounted: true, at: at)
        let again = s.set("pe-arjun", accounted: true, at: at)
        let offList = s.set("pe-maya", accounted: true, at: at)
        #expect(first)
        #expect(!again)
        #expect(!offList)
        #expect(s.ticks.count == 1)
    }

    @Test func countsAndHeadline() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let c = RollCallLogic.counts(RollCallLogic.entries(s, roster: roster))
        #expect(c.total == 3)
        #expect(c.accounted == 1)
        #expect(c.missing == 2)
        #expect(c.added == 0)
        #expect(!c.isComplete)
        #expect(abs(c.progress - 1.0 / 3.0) < 0.0001)
        #expect(RollCallLogic.headline(c) == "1 / 3 accounted for · 2 missing")
        #expect(RollCallCounts().progress == 0)
    }

    @Test func addingSomeoneOffTheListMarksThemPresent() {
        var s = session()
        s.add("pe-maya", at: at)
        #expect(s.addedIds == ["pe-maya"])
        #expect(s.isAccounted("pe-maya"))
        #expect(s.isAdded("pe-maya"))
        let entries = RollCallLogic.entries(s, roster: roster)
        #expect(entries.map(\.id) == ["pe-arjun", "pe-priya", "pe-zoe", "pe-maya"])
        #expect(entries.last?.added == true)
        let c = RollCallLogic.counts(entries)
        #expect(c.total == 4)
        #expect(c.accounted == 1)
        #expect(c.added == 1)
        // Unticking someone who was only added takes them off the list again.
        s.toggle("pe-maya", at: at)
        #expect(s.addedIds.isEmpty)
        #expect(RollCallLogic.entries(s, roster: roster).count == 3)
    }

    @Test func addingSomeoneExpectedJustTicksThem() {
        var s = session()
        s.add("pe-zoe", at: at)
        #expect(s.addedIds.isEmpty)
        #expect(s.isAccounted("pe-zoe"))
        #expect(!s.isAdded("pe-zoe"))
    }

    @Test func addCandidatesAreActiveUntickedMatches() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let all = RollCallLogic.addCandidates(roster, session: s, query: "").map(\.participantEventId)
        #expect(all == ["pe-arjun", "pe-maya", "pe-priya"]) // no Zoe (ticked), no Leo (withdrawn)
        #expect(RollCallLogic.addCandidates(roster, session: s, query: "chen").map(\.participantEventId) == ["pe-maya"])
    }

    @Test func filtersAndSearch() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let entries = RollCallLogic.entries(s, roster: roster)
        #expect(RollCallLogic.filter(entries, filter: .missing, query: "").map(\.id) == ["pe-arjun", "pe-priya"])
        #expect(RollCallLogic.filter(entries, filter: .accounted, query: "").map(\.id) == ["pe-zoe"])
        #expect(RollCallLogic.filter(entries, filter: .all, query: "").count == 3)
        #expect(RollCallLogic.filter(entries, filter: .all, query: "patel").map(\.id) == ["pe-arjun"])
        #expect(RollCallLogic.filter(entries, filter: .accounted, query: "patel").isEmpty)
        #expect(RollCallLogic.filterCounts(entries, query: "") == [.missing: 2, .accounted: 1, .all: 3])
        #expect(RollCallLogic.filterCounts(entries, query: "zoe") == [.missing: 0, .accounted: 1, .all: 1])
    }

    @Test func someoneMissingFromTheRosterGetsAPlaceholder() {
        let s = session()
        let entries = RollCallLogic.entries(s, roster: roster.filter { $0.participantEventId != "pe-priya" })
        let priya = entries.first { $0.id == "pe-priya" }
        #expect(priya?.known == false)
        #expect(priya?.participant.name == "Unknown participant")
        #expect(entries.first { $0.id == "pe-zoe" }?.known == true)
    }

    @Test func missingShareTextListsOnlyMissingPeople() {
        var s = session()
        s.toggle("pe-zoe", at: at)
        let text = RollCallLogic.missingShareText(eventName: "Campfire Sydney", session: s,
                                                  entries: RollCallLogic.entries(s, roster: roster), tz: "Australia/Sydney", now: Fixtures.now)
        #expect(text.hasPrefix("Campfire Sydney roll call (started "))
        #expect(text.contains("1 / 3 accounted for · 2 missing"))
        #expect(text.contains("Missing (2):"))
        #expect(text.contains("• Arjun Patel"))
        #expect(text.contains("• Priya Sharma"))
        #expect(!text.contains("Zoe"))
    }

    @Test func shareTextWhenEveryoneIsAccountedFor() {
        var s = session()
        for id in s.expectedIds { s.toggle(id, at: at) }
        let entries = RollCallLogic.entries(s, roster: roster)
        #expect(RollCallLogic.counts(entries).isComplete)
        let text = RollCallLogic.missingShareText(eventName: "Campfire", session: s, entries: entries, tz: nil, now: Fixtures.now)
        #expect(text.contains("Everyone is accounted for."))
        #expect(!text.contains("Missing ("))
    }

    @Test func scanBookkeepingOnlyTracksScansTheRollCallMade() {
        var s = session(scanPoint: RollCallScanPoint(id: "c1", name: "Desk", checksIn: true))
        s.noteRecorded("pe-zoe")
        s.noteRecorded("pe-zoe")
        #expect(s.recordedIds == ["pe-zoe"])
        s.noteStillRecorded("pe-zoe")
        #expect(s.stillRecordedIds == ["pe-zoe"])
        #expect(RollCallLogic.entries(s, roster: roster).first { $0.id == "pe-zoe" }?.stillRecorded == true)
        // Ticked again: the scan that stayed recorded counts again.
        s.noteRecorded("pe-zoe")
        #expect(s.stillRecordedIds.isEmpty)
        s.noteUndone("pe-zoe")
        #expect(s.recordedIds.isEmpty)
    }

    @Test func scanPointFromContext() {
        let p = RollCallScanPoint(Fixtures.contexts[0])
        #expect(p == RollCallScanPoint(id: "c1", name: "Check-in desk", checksIn: true))
    }

    @Test func sessionRoundTripsThroughTheCacheCoders() throws {
        // Ids with capitals and underscores would be mangled as dictionary keys by the snake_case
        // coders; the session keeps them in arrays.
        var s = RollCallLogic.start(eventId: "e1", participants: [member("PE_Mixed-Case", "Ana Ruiz"), member("pe-zoe", "Zoe Adams")],
                                    expected: .everyone, scanPoint: RollCallScanPoint(id: "c_1", name: "Desk", checksIn: true), now: Fixtures.now)
        s.toggle("PE_Mixed-Case", at: at)
        s.add("Added_ID", at: at)
        s.noteRecorded("PE_Mixed-Case")
        s.noteStillRecorded("pe-zoe")
        let data = try AttendJSON.encoder().encode(s)
        let back = try AttendJSON.decoder().decode(RollCallSession.self, from: data)
        #expect(back == s)
        #expect(back.isAccounted("PE_Mixed-Case"))
        #expect(back.expected == .everyone)
    }

    @Test func cacheKeyIsPerEvent() {
        #expect(RollCallLogic.cacheKey("e1") == "rollcall_e1")
    }
}

@MainActor
@Suite(.serialized) struct RollCallStoreTests {
    let directory = FileManager.default.temporaryDirectory.appending(path: "rollcall-tests-\(UUID().uuidString)")

    @Test func survivesARestartAndEnds() async {
        let cache = JsonCache(directory: directory, cipher: IdentityCipher())
        let store = RollCallStore(cache: cache)
        var s = RollCallLogic.start(eventId: "e1", participants: roster, expected: .checkedIn, scanPoint: nil, now: Fixtures.now)
        store.start(s)
        store.update("e1") { $0.toggle("pe-zoe", at: at) }
        s.toggle("pe-zoe", at: at)
        await store.waitForWrites()

        // A fresh store (the app relaunched) reads it back.
        let relaunched = RollCallStore(cache: cache)
        #expect(relaunched.session("e1") == nil)
        let restored = await relaunched.load("e1")
        let other = await relaunched.load("e2")
        #expect(restored == s)
        #expect(other == nil)

        relaunched.end("e1")
        await relaunched.waitForWrites()
        let afterEnd = await RollCallStore(cache: cache).load("e1")
        #expect(afterEnd == nil)
    }

    @Test func updateWithoutASessionDoesNothing() {
        let store = RollCallStore(cache: JsonCache(directory: directory, cipher: IdentityCipher()))
        store.update("e1") { $0.toggle("pe-zoe", at: at) }
        #expect(store.session("e1") == nil)
    }

    @Test func withoutEncryptionItLastsOnlyInMemory() async {
        let cache = JsonCache(directory: directory, cipher: nil)
        let store = RollCallStore(cache: cache)
        let s = RollCallLogic.start(eventId: "e1", participants: roster, expected: .everyone, scanPoint: nil, now: Fixtures.now)
        store.start(s)
        await store.waitForWrites()
        #expect(store.session("e1") == s)
        let fromDisk = await RollCallStore(cache: cache).load("e1")
        #expect(fromDisk == nil)
    }

    @Test func clearForgetsEverything() async {
        let store = RollCallStore(cache: JsonCache(directory: directory, cipher: IdentityCipher()))
        store.start(RollCallLogic.start(eventId: "e1", participants: roster, expected: .everyone, scanPoint: nil, now: Fixtures.now))
        store.clear()
        #expect(store.session("e1") == nil)
    }
}

@MainActor
@Suite struct RosterToolLinkTests {
    @Test func rollCallAndFirstAidLinksOpenOnHome() {
        let router = Router()
        router.tab = .people
        #expect(router.handle(URL(string: "attend://rollcall")!, available: AppTab.allCases, selectedEventId: "e1"))
        #expect(router.tab == .home)
        #expect(router.paths[.home] == [.rollCall(eventId: "e1")])

        #expect(router.handle(URL(string: "attend://firstaid")!, available: AppTab.allCases, selectedEventId: "e1"))
        #expect(router.paths[.home] == [.firstAid(eventId: "e1")])
    }

    @Test func linksNeedASelectedEvent() {
        let router = Router()
        #expect(!router.handle(URL(string: "attend://rollcall")!, available: AppTab.allCases, selectedEventId: nil))
        #expect(!router.handle(URL(string: "attend://firstaid")!, available: AppTab.allCases, selectedEventId: nil))
    }
}
