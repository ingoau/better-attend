import Foundation
import Testing
@testable import Attend

// Ports of Android's `RosterAlertsTest` and `ArrivalAlertsTest` (the logic halves): signups and
// withdrawals between roster syncs, pickup reminders from the travel calendar, and their wording.

@Suite struct RosterAlertsTests {
    private let mia = Fixtures.person(1, "Mia Chen")
    private let ollie = Fixtures.person(2, "Ollie Smith")
    private let ava = Fixtures.person(3, "Ava Jones", status: "in_progress")

    @Test func newRegistrationsAreSignups() {
        let old = Roster(eventId: "e1", participants: [mia])
        let new = Roster(eventId: "e1", participants: [ava, mia, ollie])
        #expect(RosterAlerts.newSignups(old: old, new: new).map(\.participantEventId) == ["pe3", "pe2"])
    }

    @Test func invitesWithdrawalsAndRejectionsAreNotSignups() {
        let old = Roster(eventId: "e1", participants: [mia])
        let new = Roster(eventId: "e1", participants: [
            mia, Fixtures.person(2, "Invited", status: "invited"), Fixtures.person(3, "Gone", status: "withdrawn"),
            Fixtures.person(4, "No", status: "rejected"),
        ])
        #expect(RosterAlerts.newSignups(old: old, new: new).isEmpty)
    }

    @Test func anInviteeWhoStartsRegisteringIsASignup() {
        var invited = Fixtures.person(2, "Ollie Smith", status: "invited")
        let old = Roster(eventId: "e1", participants: [mia, invited])
        invited.status = "in_progress"
        let new = Roster(eventId: "e1", participants: [mia, invited])
        #expect(RosterAlerts.newSignups(old: old, new: new).map(\.participantEventId) == ["pe2"])
    }

    @Test func reinstatementsAreNotSignups() {
        let old = Roster(eventId: "e1", participants: [
            mia, Fixtures.person(2, "Ollie Smith", status: "withdrawn"), Fixtures.person(3, "Ava Jones", status: "rejected"),
        ])
        #expect(RosterAlerts.newSignups(old: old, new: Roster(eventId: "e1", participants: [mia, ollie, ava])).isEmpty)
    }

    @Test func updatesToPeopleAlreadySignedUpAreNotChanges() {
        let old = Roster(eventId: "e1", participants: [mia, ava])
        var checkedIn = mia
        checkedIn.checkedInAt = "2026-10-03T00:00:00Z"
        var complete = ava
        complete.status = "complete"
        #expect(RosterAlerts.changes(old: old, new: Roster(eventId: "e1", participants: [checkedIn, complete])).isEmpty)
    }

    @Test func peopleWhoWereSignedUpAndWithdrawAreWithdrawals() {
        var gone = ollie, alsoGone = ava
        gone.status = "withdrawn"
        alsoGone.status = "withdrawn"
        let old = Roster(eventId: "e1", participants: [mia, ollie, ava])
        let new = Roster(eventId: "e1", participants: [mia, gone, alsoGone])
        #expect(RosterAlerts.newWithdrawals(old: old, new: new).map(\.participantEventId) == ["pe2", "pe3"])
    }

    @Test func rejectionsInviteesAndStrangersAreNotWithdrawals() {
        var rejected = mia
        rejected.status = "rejected"
        let old = Roster(eventId: "e1", participants: [mia, Fixtures.person(2, "Ollie Smith", status: "invited"),
                                                       Fixtures.person(3, "Gone", status: "withdrawn")])
        let new = Roster(eventId: "e1", participants: [
            rejected, Fixtures.person(2, "Ollie Smith", status: "withdrawn"), Fixtures.person(3, "Gone", status: "withdrawn"),
            Fixtures.person(4, "New", status: "withdrawn"),
        ])
        #expect(RosterAlerts.newWithdrawals(old: old, new: new).isEmpty)
    }

    @Test func onlyRostersSyncedSinceTurningItOnAreABaseline() {
        let since = "2026-10-03T10:00:00Z"
        #expect(RosterAlerts.isBaseline(since: since, previousSyncAt: "2026-10-03T10:15:00Z"))
        #expect(RosterAlerts.isBaseline(since: since, previousSyncAt: since))
        // Cached from before it was on: everything since then would show up as new.
        #expect(!RosterAlerts.isBaseline(since: since, previousSyncAt: "2026-10-01T09:00:00Z"))
        #expect(!RosterAlerts.isBaseline(since: since, previousSyncAt: nil))
        #expect(!RosterAlerts.isBaseline(since: nil, previousSyncAt: "2026-10-03T10:15:00Z"))
    }

    @MainActor @Test func turningAlertsOnRecordsWhenAndSignOutTurnsThemOff() {
        let defaults = UserDefaults(suiteName: "alert-tests-\(UUID().uuidString)")!
        let settings = SettingsStore(defaults: defaults)
        #expect(!settings.anyEventAlerts && settings.signupNotificationsSince == nil)
        var arrivalChanges = 0
        settings.onArrivalAlertsChange = { arrivalChanges += 1 }
        settings.signupNotifications = true
        settings.withdrawalNotifications = true
        settings.arrivalNotifications = true
        #expect(settings.signupNotificationsSince != nil && settings.withdrawalNotificationsSince != nil)
        #expect(arrivalChanges == 1)
        let reloaded = SettingsStore(defaults: defaults)
        #expect(reloaded.signupNotificationsSince == settings.signupNotificationsSince && reloaded.arrivalNotifications)
        settings.clearAccountData()
        #expect(!settings.anyEventAlerts && settings.signupNotificationsSince == nil && settings.withdrawalNotificationsSince == nil)
    }

    @Test func wording() {
        #expect(RosterAlerts.signupTitle(count: 1, eventName: "Campfire") == "New signup for Campfire")
        #expect(RosterAlerts.signupTitle(count: 3, eventName: "Campfire") == "3 new signups for Campfire")
        #expect(RosterAlerts.signupTitle(count: 2, eventName: nil) == "2 new signups")
        #expect(RosterAlerts.withdrawalTitle(count: 1, eventName: "Campfire") == "Withdrawal from Campfire")
        #expect(RosterAlerts.withdrawalTitle(count: 2, eventName: " ") == "2 withdrawals")
        #expect(RosterAlerts.summary(["Mia"]) == "Mia")
        #expect(RosterAlerts.summary(["Mia", "Ollie"]) == "Mia and Ollie")
        #expect(RosterAlerts.summary(["Mia", "Ollie", "Ava"]) == "Mia, Ollie and Ava")
        #expect(RosterAlerts.summary(["Mia", "Ollie", "Ava", "Kai", "Zoë"]) == "Mia, Ollie, Ava and 2 others")
        #expect(RosterAlerts.summary(["Mia", "Ollie", "Ava", "Kai"]) == "Mia, Ollie, Ava and 1 other")
    }
}

@Suite struct ArrivalAlertsTests {
    private let now = Fixtures.now
    private let tz = "Australia/Sydney"
    private func at(_ minutes: Double) -> String { Time.iso(now.addingTimeInterval(minutes * 60)) }

    private func arrival(_ id: String, _ name: String, _ minutes: Double?, pickup: String? = "awaiting_pickup",
                         direction: String = "inbound") -> TravelEntry {
        TravelEntry(id: id, participantName: name, direction: direction, primaryTimeAt: minutes.map(at),
                    route: "SYD → CBR", reference: "QF14\(id)", pickupState: pickup)
    }

    private var mia: TravelEntry { arrival("1", "Mia Chen", 120) }
    private var ollie: TravelEntry { arrival("2", "Ollie Smith", 120) }
    private var ava: TravelEntry { arrival("3", "Ava Jones", 20) }
    private var calendar: TravelCalendar {
        TravelCalendar(eventTimezone: tz, entries: [
            mia, ollie, ava,
            arrival("4", "Collected", 60, pickup: "collected"),
            arrival("5", "No pickup", 60, pickup: "pickup_not_needed"),
            arrival("6", "Leaving", 60, direction: "outbound"),
            arrival("7", "Landed", -5),
            arrival("8", "Unscheduled", nil),
        ])
    }
    private func ids(_ slots: [ArrivalSlot]) -> [[String]] { slots.map { $0.entries.map(\.id) } }

    @Test func onlyUpcomingArrivalsAwaitingPickupGetAReminder() {
        let slots = ArrivalAlerts.slots(calendar, now: now)
        #expect(ids(slots) == [["3"], ["1", "2"]])
        // 30 minutes ahead, or straight away when that's already passed.
        #expect(slots[0].remindAt == now)
        #expect(slots[1].remindAt == Time.parse(at(90)))
    }

    @Test func peopleAlreadyRemindedAtThatTimeAreLeftOut() throws {
        let reminded: Set = [ArrivalAlerts.remindedKey("3", try #require(Time.parse(at(20)))),
                             ArrivalAlerts.remindedKey("1", try #require(Time.parse(at(120))))]
        #expect(ids(ArrivalAlerts.slots(calendar, now: now, reminded: reminded)) == [["2"]])
        // A new time is a new reminder.
        var moved = ava
        moved.primaryTimeAt = at(50)
        #expect(ArrivalAlerts.slots(TravelCalendar(entries: [moved]), now: now, reminded: reminded).count == 1)
    }

    @Test func timeChangesOfAtLeast15Minutes() throws {
        var later = mia, nudged = ollie
        later.primaryTimeAt = at(165)
        nudged.primaryTimeAt = at(130)
        let updated = TravelCalendar(eventTimezone: tz, entries: [later, nudged, ava])
        let changes = ArrivalAlerts.changes(previous: ["1": at(120), "2": at(120), "3": at(20)], calendar: updated, now: now)
        #expect(changes.map(\.entry.id) == ["1"])
        let time = try #require(Time.time(at(165), tz: tz))
        #expect(ArrivalAlerts.changeLine(try #require(changes.first), tz: tz) == "Mia Chen now arrives at \(time) (45 min later)")
        // Someone never scheduled before isn't a change.
        #expect(ArrivalAlerts.changes(previous: [:], calendar: updated, now: now).isEmpty)
    }

    @Test func aRescheduleRemembersWhatWentOffAndWhatMoved() {
        let first = ArrivalReminderPlan(previous: ArrivalReminderState(), eventId: "e1", calendar: calendar, now: now)
        #expect(ids(first.slots) == [["3"], ["1", "2"]])
        #expect(first.changes.isEmpty && first.obsolete.isEmpty)
        #expect(first.state.pending.count == 2)

        // An hour on, Ava's reminder has gone off and Mia's flight is 45 minutes late.
        var later = mia
        later.primaryTimeAt = at(165)
        let updated = TravelCalendar(eventTimezone: tz, entries: [later, ollie, ava])
        let second = ArrivalReminderPlan(previous: first.state, eventId: "e1", calendar: updated, now: now.addingTimeInterval(10 * 60))
        #expect(second.changes.map(\.entry.id) == ["1"])
        #expect(ids(second.slots) == [["2"], ["1"]])
        #expect(second.obsolete == first.state.pending.map(\.id))

        // Another event starts from scratch: no "changes" carried over.
        let other = ArrivalReminderPlan(previous: second.state, eventId: "e2", calendar: updated, now: now)
        #expect(other.changes.isEmpty && other.state.eventId == "e2")
    }

    @Test func wording() throws {
        let slots = ArrivalAlerts.slots(calendar, now: now)
        let early = try #require(Time.time(at(20), tz: tz)), late = try #require(Time.time(at(120), tz: tz))
        #expect(ArrivalAlerts.reminderTitle(slots[0], tz: tz) == "Pickup at \(early): Ava Jones")
        #expect(ArrivalAlerts.reminderText(slots[0]) == "QF143 · SYD → CBR")
        #expect(ArrivalAlerts.reminderTitle(slots[1], tz: tz) == "2 arrivals to collect at \(late)")
        #expect(ArrivalAlerts.reminderText(slots[1]) == "Mia Chen and Ollie Smith")
        #expect(ArrivalAlerts.changeTitle(count: 1) == "Arrival time changed")
        #expect(ArrivalAlerts.changeTitle(count: 2) == "2 arrival times changed")
    }
}
