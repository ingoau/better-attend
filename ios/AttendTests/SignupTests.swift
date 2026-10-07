import Foundation
import Testing
@testable import Attend

// Port of the logic half of Android's `SignupsTest`: spotting new signups between roster syncs,
// and how the notification words them.

@Suite struct SignupLogicTests {
    private let mia = Fixtures.person(1, "Mia Chen")
    private let ollie = Fixtures.person(2, "Ollie Smith")
    private let ava = Fixtures.person(3, "Ava Jones", status: "in_progress")

    @Test func newRegistrationsAreSignups() {
        let old = Roster(eventId: "e1", participants: [mia])
        let new = Roster(eventId: "e1", participants: [ava, mia, ollie])
        #expect(SignupLogic.newSignups(old: old, new: new).map(\.participantEventId) == ["pe3", "pe2"])
    }

    @Test func invitesWithdrawalsAndRejectionsAreNot() {
        let old = Roster(eventId: "e1", participants: [mia])
        let new = Roster(eventId: "e1", participants: [
            mia, Fixtures.person(2, "Invited", status: "invited"), Fixtures.person(3, "Gone", status: "withdrawn"),
            Fixtures.person(4, "No", status: "rejected"),
        ])
        #expect(SignupLogic.newSignups(old: old, new: new).isEmpty)
    }

    @Test func anInviteeWhoStartsRegisteringIsASignup() {
        var invited = Fixtures.person(2, "Ollie Smith", status: "invited")
        let old = Roster(eventId: "e1", participants: [mia, invited])
        invited.status = "in_progress"
        let new = Roster(eventId: "e1", participants: [mia, invited])
        #expect(SignupLogic.newSignups(old: old, new: new).map(\.participantEventId) == ["pe2"])
    }

    @Test func updatesToPeopleAlreadySignedUpAreNot() {
        let old = Roster(eventId: "e1", participants: [mia, ava])
        var checkedIn = mia
        checkedIn.checkedInAt = "2026-10-03T00:00:00Z"
        var complete = ava
        complete.status = "complete"
        #expect(SignupLogic.newSignups(old: old, new: Roster(eventId: "e1", participants: [checkedIn, complete])).isEmpty)
    }

    @Test func wording() {
        #expect(SignupLogic.title(count: 1, eventName: "Campfire") == "New signup for Campfire")
        #expect(SignupLogic.title(count: 3, eventName: "Campfire") == "3 new signups for Campfire")
        #expect(SignupLogic.title(count: 2, eventName: nil) == "2 new signups")
        // Fixtures.person uses the first name as the display name.
        #expect(SignupLogic.summary([mia]) == "Mia")
        #expect(SignupLogic.summary([mia, ollie]) == "Mia and Ollie")
        #expect(SignupLogic.summary([mia, ollie, ava]) == "Mia, Ollie and Ava")
        let five = [mia, ollie, ava, Fixtures.person(4, "Kai"), Fixtures.person(5, "Zoë")]
        #expect(SignupLogic.summary(five) == "Mia, Ollie, Ava and 2 others")
        #expect(SignupLogic.summary(Array(five.prefix(4))) == "Mia, Ollie, Ava and 1 other")
    }
}
