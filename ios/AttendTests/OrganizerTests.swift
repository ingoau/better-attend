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
        #expect(StaffLogic.losesStaffAccess(mine, newRole: "ops", user: me, event: event))
        #expect(StaffLogic.losesStaffAccess(mine, newRole: nil, user: me, event: event))
        #expect(!StaffLogic.losesStaffAccess(mine, newRole: "event_admin", user: me, event: event))
        // Someone else's row never warns.
        #expect(!StaffLogic.losesStaffAccess(member("2", "event_admin", "Bea"), newRole: nil, user: me, event: event))
        // Series members and global admins keep access through their other role.
        event.role = "series_member"
        #expect(!StaffLogic.losesStaffAccess(mine, newRole: nil, user: me, event: event))
        event.role = "event_admin"
        var admin = me
        admin.globalAdmin = true
        #expect(!StaffLogic.losesStaffAccess(mine, newRole: "read_only", user: admin, event: event))
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
