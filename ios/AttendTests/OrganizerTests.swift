import Foundation
import Testing
@testable import Attend

// Edit participant details, invite a walk-in, remove a registration, and event staff.

@Suite struct ParticipantEditTests {
    private func detailed() -> Participant {
        var p = Fixtures.person(1, "Zoë Adams", email: "zoe@example.com", pronouns: "she/her")
        p.phone = "+61400000001"
        p.tshirtSize = "M"
        p.personal = Personal(legalFirstName: "Zoë", legalLastName: "Adams", preferredName: "Zo", tshirtSize: "L",
                              dateOfBirth: "2009-04-12")
        return p
    }

    @Test func formIsPrefilledFromTheDetailedParticipant() {
        let form = ParticipantEditForm(detailed())
        #expect(form.legalFirstName == "Zoë")
        #expect(form.legalLastName == "Adams")
        #expect(form.preferredName == "Zo")
        #expect(form.email == "zoe@example.com")
        #expect(form.phone == "+61400000001")
        #expect(form.pronouns == "she/her")
        // The profile's size wins over the roster copy.
        #expect(form.tshirtSize == "L")
        #expect(form.dateOfBirth == "2009-04-12")
    }

    @Test func formFallsBackToRosterSizeAndHandlesMissingProfile() {
        var p = Fixtures.person(2, "Arjun Patel")
        p.tshirtSize = "XL"
        let form = ParticipantEditForm(p)
        #expect(form.tshirtSize == "XL")
        #expect(form.legalFirstName == "")
        #expect(form.dateOfBirth == nil)
    }

    @Test func unchangedFormSendsNothing() {
        let form = ParticipantEditForm(detailed())
        #expect(ParticipantEditLogic.diff(original: form, edited: form, includePII: true).isEmpty)
    }

    @Test func onlyChangedFieldsAreSentTrimmed() {
        let original = ParticipantEditForm(detailed())
        var edited = original
        edited.preferredName = "  Zoey "
        edited.pronouns = "she/they"
        edited.email = "zoe@example.com " // whitespace only: not a change
        let edit = ParticipantEditLogic.diff(original: original, edited: edited, includePII: true)
        #expect(edit == ParticipantEdit(preferredName: "Zoey", pronouns: "she/they"))
    }

    @Test func clearingAFieldSendsEmptyString() {
        let original = ParticipantEditForm(detailed())
        var edited = original
        edited.pronouns = ""
        edited.dateOfBirth = nil
        let edit = ParticipantEditLogic.diff(original: original, edited: edited, includePII: true)
        #expect(edit.pronouns == "")
        #expect(edit.dateOfBirth == "")
        #expect(edit.legalFirstName == nil)
    }

    @Test func phoneAndBirthdayOnlySentWithPIIAccess() {
        let original = ParticipantEditForm(detailed())
        var edited = original
        edited.phone = "+61499999999"
        edited.dateOfBirth = "2010-01-02"
        edited.tshirtSize = "S"
        let withPII = ParticipantEditLogic.diff(original: original, edited: edited, includePII: true)
        #expect(withPII == ParticipantEdit(phone: "+61499999999", tshirtSize: "S", dateOfBirth: "2010-01-02"))
        let without = ParticipantEditLogic.diff(original: original, edited: edited, includePII: false)
        #expect(without == ParticipantEdit(tshirtSize: "S"))
    }

    @Test func diffEncodesOnlyChangedKeys() throws {
        let original = ParticipantEditForm(detailed())
        var edited = original
        edited.legalLastName = "Adams-Lee"
        let data = try AttendJSON.encoder().encode(ParticipantEditLogic.diff(original: original, edited: edited, includePII: true))
        let obj = try #require(JSONSerialization.jsonObject(with: data) as? [String: String])
        #expect(obj == ["legal_last_name": "Adams-Lee"])
    }

    @Test func firstNameRequiredOnlyIfItWasThere() {
        let original = ParticipantEditForm(detailed())
        var edited = original
        edited.legalFirstName = "  "
        #expect(ParticipantEditLogic.validate(original: original, edited: edited)[.legalFirstName] != nil)

        var blankBefore = original
        blankBefore.legalFirstName = ""
        #expect(ParticipantEditLogic.validate(original: blankBefore, edited: blankBefore).isEmpty)
    }

    @Test func emailMustLookLikeAnEmail() {
        let original = ParticipantEditForm(detailed())
        for bad in ["zoe", "zoe@", "@example.com", "zoe@example", "zo e@example.com", "zoe@@example.com", "zoe@example."] {
            var edited = original
            edited.email = bad
            #expect(ParticipantEditLogic.validate(original: original, edited: edited)[.email] != nil, "\(bad)")
        }
        var cleared = original
        cleared.email = ""
        #expect(ParticipantEditLogic.validate(original: original, edited: cleared)[.email] != nil)
        var good = original
        good.email = "zoe.adams+campfire@example.com.au"
        #expect(ParticipantEditLogic.validate(original: original, edited: good).isEmpty)
    }

    @Test func sizeOptionsKeepNonStandardValue() {
        #expect(ParticipantEditLogic.sizeOptions(current: "M") == ParticipantEditLogic.tshirtSizes)
        #expect(ParticipantEditLogic.sizeOptions(current: "") == ParticipantEditLogic.tshirtSizes)
        #expect(ParticipantEditLogic.sizeOptions(current: "Youth M").last == "Youth M")
        #expect(ParticipantEditLogic.sizeOptions(current: "Youth M").count == ParticipantEditLogic.tshirtSizes.count + 1)
    }

    @Test func birthdayRoundTripsThroughThePicker() throws {
        let zone = try #require(TimeZone(identifier: "Australia/Sydney"))
        let date = try #require(ParticipantEditLogic.pickerDate("2009-04-12", in: zone))
        #expect(ParticipantEditLogic.isoDay(date, in: zone) == "2009-04-12")
        #expect(ParticipantEditLogic.pickerDate(nil, in: zone) == nil)
    }
}

@Suite struct InviteTests {
    @Test func emailIsRequiredAndChecked() {
        #expect(InviteLogic.emailProblem("") != nil)
        #expect(InviteLogic.emailProblem("sam") != nil)
        #expect(InviteLogic.emailProblem(" sam@example.com ") == nil)
    }

    @Test func blankNamesAreNotSent() {
        #expect(InviteLogic.optionalName("  ") == nil)
        #expect(InviteLogic.optionalName(" Sam ") == "Sam")
    }

    @Test func heldInvitationsSaySo() {
        let held = InviteResult(held: true, participantEventId: "pe9", status: "invited")
        #expect(InviteLogic.successMessage(held, email: "a@b.co").contains("releases invitations"))
        let sent = InviteResult(participantEventId: "pe9", status: "invited")
        #expect(InviteLogic.successMessage(sent, email: " a@b.co ").contains("a@b.co."))
    }

    @Test func inviteResultDecodesUpstreamShape() throws {
        let json = #"{"success":true,"held":false,"message":"Invitation sent to a@b.co","event":"Campfire","participant_id":"p1","participant_event_id":"pe1","status":"invited"}"#
        let r = try AttendJSON.decoder().decode(InviteResult.self, from: Data(json.utf8))
        #expect(r.participantEventId == "pe1")
        #expect(!r.held)
        #expect(r.status == "invited")
    }
}

@Suite struct ParticipantActionVisibilityTests {
    private func event(_ role: String?, pii: Bool = true, participants: Bool = true) -> Event {
        var e = Fixtures.event
        e.role = role
        e.canViewParticipantPii = pii
        e.canViewParticipants = participants
        return e
    }

    @Test func eventAdminSeesEverything() {
        let v = ParticipantActionVisibility(event("event_admin"))
        #expect(v.edit && v.editPII && v.withdraw && v.remove && v.invite && v.showsRegistrationSection)
    }

    @Test func opsCanEditButNotRemoveOrInvite() {
        let v = ParticipantActionVisibility(event("ops"))
        #expect(v.edit && v.withdraw)
        #expect(!v.remove && !v.invite)
    }

    @Test func limitedWithoutPIIDoesNotEditPhoneOrBirthday() {
        let v = ParticipantActionVisibility(event("limited", pii: false))
        #expect(v.edit)
        #expect(!v.editPII)
    }

    @Test func seriesMemberInvitesButCannotEditOrRemove() {
        let v = ParticipantActionVisibility(event("series_member"))
        #expect(v.invite)
        #expect(!v.edit && !v.remove && !v.showsRegistrationSection)
    }

    @Test func readOnlyAndSafeguardingSeeNoActions() {
        for role in ["read_only", "safeguarding_lead", nil] {
            let v = ParticipantActionVisibility(event(role))
            #expect(!v.edit && !v.remove && !v.invite && !v.withdraw, "\(role ?? "nil")")
        }
        #expect(ParticipantActionVisibility(nil) == ParticipantActionVisibility(event(nil)))
    }

    @Test func inviteNeedsRosterAccess() {
        #expect(!ParticipantActionVisibility(event("event_admin", participants: false)).invite)
    }
}

@Suite struct StaffLogicTests {
    private let roles = [
        StaffRole(role: "event_admin", label: "Event Admin", summary: "Full control of this event."),
        StaffRole(role: "ops", label: "Ops", summary: "Day-to-day logistics and operations."),
        StaffRole(role: "limited", label: "Limited"),
        StaffRole(role: "safeguarding_lead", label: "Safeguarding Lead"),
        StaffRole(role: "read_only", label: "Read Only"),
    ]

    private func member(_ id: String, _ role: String, _ name: String?, email: String? = nil, series: Bool = false,
                        seriesRole: String? = nil, globalAdmin: Bool = false) -> StaffMember {
        StaffMember(id: id, role: role, inheritedFromSeries: series, seriesRole: seriesRole,
                    user: StaffUser(id: "u\(id)", email: email ?? "\(id)@example.com", name: name, globalAdmin: globalAdmin))
    }

    @Test func sortsByRoleThenName() {
        let staff = [
            member("1", "read_only", "Ana"),
            member("2", "ops", "zed"),
            member("3", "event_admin", "Orpheus"),
            member("4", "ops", "Bea"),
            member("5", "mystery_role", "Aaron"),
            member("6", "event_admin", nil, email: "alex@example.com"),
        ]
        let ids = StaffLogic.sorted(staff, roles: roles).map(\.id)
        #expect(ids == ["6", "3", "4", "2", "1", "5"])
    }

    @Test func sectionsFollowTheCatalogue() {
        let staff = [member("1", "ops", "Bea"), member("2", "event_admin", "Orpheus"), member("3", "ops", "Al")]
        let sections = StaffLogic.sections(staff, roles: roles)
        #expect(sections.map(\.title) == ["Event Admin", "Ops"])
        #expect(sections[1].members.map(\.id) == ["3", "1"])
        #expect(sections[0].summary == "Full control of this event.")
    }

    @Test func labelsPreferServerThenCatalogue() {
        var m = member("1", "safeguarding_lead", "Priya")
        #expect(StaffLogic.roleLabel(m, roles: roles) == "Safeguarding Lead")
        m.roleLabel = "Welfare"
        #expect(StaffLogic.roleLabel(m, roles: roles) == "Welfare")
        #expect(StaffLogic.roleLabel("brand_new", roles: roles) == "Brand new")
    }

    @Test func seriesRowsAreLabelledAndLocked() {
        let inherited = member("1", "event_admin", "Jamie", series: true, seriesRole: "organizer")
        #expect(StaffLogic.seriesLabel(inherited) == "Series organizer")
        #expect(!EventPermissions.canChangeStaffMember(inherited))
        #expect(StaffLogic.seriesLabel(member("2", "ops", "Bea")) == nil)
    }

    @Test func selfIsMatchedByEmailIgnoringCase() {
        let me = Fixtures.user // orpheus@hackclub.com
        #expect(StaffLogic.isSelf(member("1", "event_admin", "O", email: "Orpheus@HackClub.com"), user: me))
        #expect(!StaffLogic.isSelf(member("2", "event_admin", "O"), user: me))
        #expect(!StaffLogic.isSelf(member("3", "event_admin", "O", email: "orpheus@hackclub.com"), user: nil))
    }

    @Test func warnsBeforeDemotingOrRemovingYourself() {
        var event = Fixtures.event
        event.role = "event_admin"
        let me = Fixtures.user
        let mine = member("1", "event_admin", "Orpheus", email: me.email)
        let others = [member("2", "event_admin", "Bea"), member("3", "ops", "Al")]
        let staff = [mine] + others
        #expect(StaffLogic.losesStaffAccess(mine, newRole: "ops", user: me, event: event, staff: staff))
        #expect(StaffLogic.losesStaffAccess(mine, newRole: nil, user: me, event: event, staff: staff))
        #expect(!StaffLogic.losesStaffAccess(mine, newRole: "event_admin", user: me, event: event, staff: staff))
        // Someone else's row never warns.
        #expect(!StaffLogic.losesStaffAccess(others[0], newRole: nil, user: me, event: event, staff: staff))
        // Series members and global admins keep access through their other role.
        event.role = "series_member"
        #expect(!StaffLogic.losesStaffAccess(mine, newRole: nil, user: me, event: event, staff: staff))
        event.role = "event_admin"
        var admin = me
        admin.globalAdmin = true
        #expect(!StaffLogic.losesStaffAccess(mine, newRole: "read_only", user: admin, event: event, staff: staff))
    }

    @Test func onlyYourEventAdminRowWarns() {
        var event = Fixtures.event
        event.role = "event_admin"
        let me = Fixtures.user
        let adminRow = member("1", "event_admin", "Orpheus", email: me.email)
        let opsRow = member("2", "ops", "Orpheus", email: me.email.uppercased())
        // Changing or removing a lesser row of yours leaves your event admin row in place.
        #expect(!StaffLogic.losesStaffAccess(opsRow, newRole: "read_only", user: me, event: event, staff: [adminRow, opsRow]))
        #expect(!StaffLogic.losesStaffAccess(opsRow, newRole: nil, user: me, event: event, staff: [adminRow, opsRow]))
        // A second event admin row (or a series one) of yours keeps you managing staff.
        let secondAdmin = member("3", "event_admin", "Orpheus", email: me.email)
        #expect(!StaffLogic.losesStaffAccess(adminRow, newRole: nil, user: me, event: event, staff: [adminRow, secondAdmin]))
        let seriesRow = member("4", "read_only", "Orpheus", email: me.email, series: true, seriesRole: "organizer")
        #expect(!StaffLogic.losesStaffAccess(adminRow, newRole: "ops", user: me, event: event, staff: [adminRow, seriesRow]))
        #expect(StaffLogic.losesStaffAccess(adminRow, newRole: "ops", user: me, event: event, staff: [adminRow, opsRow]))
    }

    @Test func rolesSomeoneHoldsCantBePickedAgain() {
        let staff = [
            member("1", "ops", "Bea", email: "bea@example.com"),
            member("2", "safeguarding_lead", "Bea", email: "BEA@example.com"),
            member("3", "ops", "Al", email: "al@example.com"),
        ]
        // Changing Bea's ops row: her safeguarding row's role is taken, ops (this row) isn't.
        #expect(StaffLogic.rolesHeld(byEmail: "bea@example.com", in: staff, excluding: "1") == ["safeguarding_lead"])
        // Adding by email, matched case-insensitively and trimmed.
        #expect(StaffLogic.rolesHeld(byEmail: "  Bea@Example.com ", in: staff) == ["ops", "safeguarding_lead"])
        #expect(StaffLogic.rolesHeld(byEmail: "new@example.com", in: staff).isEmpty)
        #expect(StaffLogic.rolesHeld(byEmail: "", in: staff).isEmpty)
        #expect(StaffLogic.alreadyHolds("ops", roles: roles) == "Already Ops")
    }

    @Test func yourOwnAccessFollowsYourRemainingRows() throws {
        var event = Fixtures.event
        event.role = "event_admin"
        event.canViewParticipants = true
        event.canViewParticipantPii = true
        let me = Fixtures.user
        let mine = { (id: String, role: String) in self.member(id, role, "Orpheus", email: me.email) }
        let someoneElse = member("9", "safeguarding_lead", "Priya")

        // Demoted to ops (plus a safeguarding row): safeguarding lead outranks ops, and sees medical details.
        let both = try #require(StaffLogic.ownAccess(after: [mine("1", "ops"), mine("2", "safeguarding_lead"), someoneElse], user: me, event: event))
        #expect(both.role == "safeguarding_lead")
        #expect(both.canViewParticipants && both.canViewParticipantPii && both.canViewSensitiveData)

        let limited = try #require(StaffLogic.ownAccess(after: [mine("1", "limited")], user: me, event: event))
        #expect(limited.role == "limited")
        #expect(limited.canViewParticipants && !limited.canViewParticipantPii && !limited.canViewSensitiveData)

        let readOnly = try #require(StaffLogic.ownAccess(after: [mine("1", "read_only")], user: me, event: event))
        #expect(readOnly.role == "read_only")
        #expect(!readOnly.canViewParticipants)
        #expect(!EventPermissions.canManageStaff(readOnly))

        // Removed your last row: no access left.
        let none = try #require(StaffLogic.ownAccess(after: [someoneElse], user: me, event: event))
        #expect(none.role == nil)
        #expect(!none.canViewParticipants && !none.canViewParticipantPii && !none.canViewSensitiveData)

        // Global admins and series members aren't decided by their event rows.
        var admin = me
        admin.globalAdmin = true
        #expect(StaffLogic.ownAccess(after: [], user: admin, event: event) == nil)
        var series = event
        series.role = "series_member"
        #expect(StaffLogic.ownAccess(after: [], user: me, event: series) == nil)
        #expect(StaffLogic.ownAccess(after: [], user: nil, event: event) == nil)
    }

    @Test func staffResponseDecodesUpstreamShape() throws {
        let json = #"""
        {"staff":[{"id":"a1","role":"event_admin","role_label":"Event Admin","inherited_from_series":true,"series_role":"owner",
        "created_at":"2026-10-01T00:00:00Z","user":{"id":"u1","email":"jamie@example.com","name":null,"global_admin":false}}],
        "roles":[{"role":"event_admin","label":"Event Admin","summary":"Full control of this event."}]}
        """#
        let res = try AttendJSON.decoder().decode(StaffResponse.self, from: Data(json.utf8))
        #expect(res.staff.first?.inheritedFromSeries == true)
        #expect(res.staff.first?.user.displayName == "jamie")
        #expect(res.roles.first?.summary == "Full control of this event.")
    }
}

@MainActor
@Suite struct RosterRemovalTests {
    @Test func removingAParticipantDropsItFromTheCachedRoster() async {
        let cache = JsonCache(directory: FileManager.default.temporaryDirectory.appending(path: "organizer-tests-\(UUID().uuidString)"),
                              cipher: IdentityCipher())
        let api = AttendAPI(tokens: MemoryTokenStore(token: "t"), baseURL: URL(string: "http://127.0.0.1:1")!)
        let repo = ParticipantRepository(api: api, cache: cache)
        let a = Fixtures.person(1, "Zoë Adams"), b = Fixtures.person(2, "Arjun Patel")
        await cache.write("roster_e1", Roster(eventId: "e1", participants: [a, b], syncedAt: "2026-10-03T00:00:00Z"))
        await repo.remove("e1", participantEventId: b.participantEventId)
        #expect(repo.roster("e1")?.participants.map(\.participantEventId) == [a.participantEventId])
        // It's written through to the disk cache too.
        let saved = await cache.read("roster_e1", as: Roster.self)
        #expect(saved?.participants.count == 1)
        // Unknown ids and events are no-ops (and never invent a roster).
        await repo.remove("e1", participantEventId: "nope")
        await repo.remove("e2", participantEventId: a.participantEventId)
        #expect(repo.roster("e1")?.participants.count == 1)
        #expect(repo.roster("e2") == nil)
    }
}

/// The demo backend's staff and invite rules (a fresh backend per test, not the shared one).
@Suite struct DemoOrganizerTests {
    private func send(_ demo: DemoBackend, _ method: String, _ path: String, _ body: [String: String]? = nil) -> (status: Int, json: [String: Any]) {
        let url = URL(string: "https://attend.hackclub.com/api/v1\(path)")!
        let data = body.flatMap { try? JSONSerialization.data(withJSONObject: $0) }
        guard case let .success(reply) = demo.handle(method: method, url: url, body: data) else { return (0, [:]) }
        return (reply.status, (try? JSONSerialization.jsonObject(with: reply.body) as? [String: Any]) ?? [:])
    }

    @Test func someoneCanHoldSeveralRolesButEachOnlyOnce() {
        let demo = DemoBackend()
        let e = DemoData.mainEventId
        // Heidi Park is ops already.
        #expect(send(demo, "POST", "/events/\(e)/staff", ["email": "heidi.park@example.com", "role": "safeguarding_lead"]).status == 201)
        let again = send(demo, "POST", "/events/\(e)/staff", ["email": "HEIDI.park@example.com", "role": "ops"])
        #expect(again.status == 422)
        #expect(again.json["error"] as? String == "Role has already been taken")
        // Marcus Webb's ops row can't become a role he holds in another row either.
        #expect(send(demo, "POST", "/events/\(e)/staff", ["email": "marcus.webb@example.com", "role": "read_only"]).status == 201)
        let marcusOps = String(format: "5e0a%04x-1c2d-4e3f-8a9b-0c1d2e3f4a5b", 4)
        #expect(send(demo, "PATCH", "/events/\(e)/staff/\(marcusOps)", ["role": "read_only"]).status == 422)
        #expect(send(demo, "PATCH", "/events/\(e)/staff/\(marcusOps)", ["role": "limited"]).status == 200)
    }

    @Test func yourRoleFollowsYourRemainingRows() {
        let demo = DemoBackend()
        let e = DemoData.upcomingEventId
        let mine = "5e0b0000-1c2d-4e3f-8a9b-0c1d2e3f4a5b"
        func myEvent() -> [String: Any]? {
            (send(demo, "GET", "/events").json["events"] as? [[String: Any]])?.first { $0["id"] as? String == e }
        }
        #expect(myEvent()?["role"] as? String == "event_admin")
        #expect(send(demo, "POST", "/events/\(e)/staff", ["email": "orpheus@hackclub.com", "role": "safeguarding_lead"]).status == 201)
        #expect(send(demo, "PATCH", "/events/\(e)/staff/\(mine)", ["role": "ops"]).status == 200)
        #expect(myEvent()?["role"] as? String == "safeguarding_lead")
        #expect(myEvent()?["can_view_sensitive_data"] as? Bool == true)
    }

    @Test func theUpcomingEventHoldsInvitations() {
        let demo = DemoBackend()
        let held = send(demo, "POST", "/events/\(DemoData.upcomingEventId)/participants", ["email": "walk.in@example.com"])
        #expect(held.status == 201)
        #expect(held.json["held"] as? Bool == true)
        let sent = send(demo, "POST", "/events/\(DemoData.mainEventId)/participants", ["email": "walk.in@example.com"])
        #expect(sent.json["held"] as? Bool == false)
        let upcoming = DemoData().events.first { $0.id == DemoData.upcomingEventId }
        #expect(ParticipantActionVisibility(upcoming).invite, "the demo organizer can reach it")
    }
}
