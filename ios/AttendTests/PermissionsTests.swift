import Foundation
import Testing
@testable import Attend

@Suite struct EventPermissionsTests {
    private let roles: [String?] = ["global_admin", "series_member", "event_admin", "safeguarding_lead", "ops", "limited", "read_only", nil]

    private func event(_ role: String?, pii: Bool = true, participants: Bool = true) -> Event {
        var e = Fixtures.event
        e.role = role
        e.canViewParticipantPii = pii
        e.canViewParticipants = participants
        return e
    }

    private func allowed(_ check: (Event?) -> Bool) -> [String?] { roles.filter { check(event($0)) } }

    @Test func editMatchesParticipantEventPolicyCanEdit() {
        #expect(allowed(EventPermissions.canEditParticipant) == ["global_admin", "event_admin", "ops", "limited"])
    }

    @Test func inviteAndStaffIncludeSeriesMembers() {
        #expect(allowed(EventPermissions.canInviteParticipants) == ["global_admin", "series_member", "event_admin"])
        #expect(allowed(EventPermissions.canManageStaff) == ["global_admin", "series_member", "event_admin"])
    }

    @Test func removeIsDirectEventAdminsOnly() {
        #expect(allowed(EventPermissions.canRemoveParticipants) == ["global_admin", "event_admin"])
    }

    @Test func readOnlyCannotViewParticipants() {
        #expect(!EventPermissions.canViewParticipants(event("read_only")))
        #expect(!EventPermissions.canViewParticipants(event("ops", participants: false)))
        #expect(EventPermissions.canViewParticipants(event("safeguarding_lead")))
    }

    @Test func piiEditNeedsPiiAccess() {
        #expect(!EventPermissions.canEditPII(event("limited", pii: false)))
        #expect(EventPermissions.canEditPII(event("ops")))
        #expect(!EventPermissions.canEditPII(event("safeguarding_lead")))
    }

    @Test func nilEventAllowsNothing() {
        #expect(!EventPermissions.canEditParticipant(nil))
        #expect(!EventPermissions.canManageStaff(nil))
        #expect(!EventPermissions.canViewSensitiveData(nil))
    }

    @Test func seriesInheritedStaffAreLocked() {
        let user = StaffUser(id: "u", email: "a@b.c")
        #expect(!EventPermissions.canChangeStaffMember(StaffMember(id: "1", role: "event_admin", inheritedFromSeries: true, user: user)))
        #expect(EventPermissions.canChangeStaffMember(StaffMember(id: "2", role: "ops", user: user)))
    }

    @Test func participantEditEncodesOnlySetFieldsInSnakeCase() throws {
        let data = try AttendJSON.encoder().encode(ParticipantEdit(preferredName: "Sam", tshirtSize: "M"))
        let obj = try #require(JSONSerialization.jsonObject(with: data) as? [String: String])
        #expect(obj == ["preferred_name": "Sam", "tshirt_size": "M"])
    }
}
