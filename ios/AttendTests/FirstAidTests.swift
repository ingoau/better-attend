import Foundation
import Testing
@testable import Attend

private func patient(_ id: String, _ name: String, here: Bool = true, status: String = "complete") -> Participant {
    Participant(participantId: "p-\(id)", participantEventId: id, displayName: String(name.split(separator: " ")[0]), fullName: name,
                status: status, checkedInAt: here ? "2026-10-03T00:00:00Z" : nil)
}

/// A mix of everything the sheet cares about, deliberately out of order.
private let people: [Participant] = {
    var plain = patient("pe-plain", "Aaron Plain")
    plain.dietType = "vegan" // a diet alone doesn't put anyone on the sheet

    var asthma = patient("pe-asthma", "Bella Asthma", here: false)
    asthma.medicalConditions = "Asthma"
    asthma.medications = "Ventolin"

    var support = patient("pe-support", "Cara Support")
    support.highSupportFlag = true

    var fridge = patient("pe-fridge", "Dan Fridge")
    fridge.requiresRefrigeration = true
    fridge.medications = "Insulin"

    var nuts = patient("pe-nuts", "Zed Nuts", here: false)
    nuts.hasAnaphylaxisRisk = true
    nuts.allergies = "Peanuts"
    nuts.lifeThreateningAllergies = "Peanuts, tree nuts"

    var shellfish = patient("pe-shellfish", "Eve Shellfish")
    shellfish.lifeThreateningAllergies = "Shellfish" // life-threatening text without the flag

    var gluten = patient("pe-gluten", "Finn Gluten")
    gluten.crossContaminationRisk = true
    gluten.dietType = "gluten_free"

    var gone = patient("pe-gone", "Gus Gone", status: "withdrawn")
    gone.hasAnaphylaxisRisk = true

    var blank = patient("pe-blank", "Hana Blank")
    blank.allergies = "   "

    return [plain, asthma, support, fridge, nuts, shellfish, gluten, gone, blank]
}()

@Suite struct FirstAidLogicTests {
    @Test func includesFlagsAndMedicalTextForActivePeople() {
        let ids = Set(FirstAidLogic.entries(people, canViewSensitive: true).map(\.id))
        #expect(ids == ["pe-asthma", "pe-support", "pe-fridge", "pe-nuts", "pe-shellfish", "pe-gluten"])
    }

    @Test func medicalTextOnlyCountsWithSensitiveAccess() {
        let ids = Set(FirstAidLogic.entries(people, canViewSensitive: false).map(\.id))
        // Asthma and Shellfish are only known from medical text.
        #expect(ids == ["pe-support", "pe-fridge", "pe-nuts", "pe-gluten"])
        #expect(!FirstAidLogic.includes(people[1], canViewSensitive: false))
        #expect(FirstAidLogic.includes(people[1], canViewSensitive: true))
    }

    @Test func withdrawnAndBlankAreLeftOut() {
        #expect(!FirstAidLogic.includes(people[7], canViewSensitive: true))
        #expect(!FirstAidLogic.includes(people[8], canViewSensitive: true))
        #expect(!FirstAidLogic.includes(people[0], canViewSensitive: true))
    }

    @Test func mostUrgentFirstThenByName() {
        let order = FirstAidLogic.entries(people, canViewSensitive: true).map(\.id)
        // Life-threatening (Eve, Zed), refrigeration (Dan), high support (Cara), others (Bella, Finn).
        #expect(order == ["pe-shellfish", "pe-nuts", "pe-fridge", "pe-support", "pe-asthma", "pe-gluten"])
        // Without sensitive access, Eve's life-threatening text doesn't count (and she isn't listed).
        #expect(FirstAidLogic.entries(people, canViewSensitive: false).map(\.id) == ["pe-nuts", "pe-fridge", "pe-support", "pe-gluten"])
    }

    @Test func hereNowAndSearch() {
        let here = FirstAidLogic.entries(people, canViewSensitive: true, scope: .hereNow).map(\.id)
        #expect(here == ["pe-shellfish", "pe-fridge", "pe-support", "pe-gluten"])
        #expect(FirstAidLogic.entries(people, canViewSensitive: true, query: "zed").map(\.id) == ["pe-nuts"])
        #expect(FirstAidLogic.scopeCounts(people, canViewSensitive: true) == [.everyone: 6, .hereNow: 4])
    }

    @Test func sensitiveFieldsOnlyWithAccess() throws {
        let open = try #require(FirstAidLogic.entries(people, canViewSensitive: true).first { $0.id == "pe-nuts" })
        #expect(open.medicalLines.map(\.label) == ["Life-threatening allergies", "Allergies"])
        #expect(open.medicalLines.first?.urgent == true)
        #expect(open.medicalLines.first?.text == "Peanuts, tree nuts")

        let closed = try #require(FirstAidLogic.entries(people, canViewSensitive: false).first { $0.id == "pe-nuts" })
        #expect(closed.medicalLines.isEmpty)
        #expect(closed.contacts.isEmpty)
        #expect(closed.flags.map(\.label) == ["Anaphylaxis risk"])
    }

    @Test func flagsIncludeCrossContamination() {
        #expect(FirstAidLogic.flags(people[6]).map(\.label) == ["Cross-contamination risk"])
    }

    @Test func dietLabels() {
        #expect(FirstAidLogic.dietLabel("gluten_free") == "Gluten free")
        #expect(FirstAidLogic.dietLabel("omnivore") == nil)
        #expect(FirstAidLogic.dietLabel(nil) == nil)
        #expect(FirstAidLogic.dietLabel(" ") == nil)
    }

    @Test func contactsByPriorityThenGuardian() {
        var p = patient("pe-c", "Cal Contacts")
        p.emergencyContacts = [
            EmergencyContact(name: "Second", phone: "+61 400 000 002", relationship: "Aunt", priority: 2),
            EmergencyContact(name: "First", phone: "+61 400 000 001", relationship: "Parent", priority: 1),
            EmergencyContact(name: " ", phone: nil),
        ]
        p.parentGuardianName = "Gwen Guardian"
        p.parentGuardianPhone = "+61 400 000 009"
        p.parentGuardianEmail = "gwen@example.com"
        let contacts = FirstAidLogic.contacts(p)
        #expect(contacts.map(\.name) == ["First", "Second", "Gwen Guardian"])
        #expect(contacts.last?.relationship == "Guardian")
        #expect(contacts.last?.email == "gwen@example.com")

        // The guardian already listed as an emergency contact isn't repeated.
        p.parentGuardianName = "First"
        p.parentGuardianPhone = "+61400000001"
        #expect(FirstAidLogic.contacts(p).map(\.name) == ["First", "Second"])
    }

    @Test func escapesHTML() {
        #expect(FirstAidLogic.escape(#"<b>"Tom" & 'Jerry'</b>"#) == "&lt;b&gt;&quot;Tom&quot; &amp; &#39;Jerry&#39;&lt;/b&gt;")
        #expect(FirstAidLogic.escape("Zoë") == "Zoë")
    }

    @Test func htmlHasTitleFooterAndEscapedData() {
        var evil = patient("pe-evil", "<script>alert(1)</script> Smith")
        evil.hasAnaphylaxisRisk = true
        evil.allergies = "Nuts & <seeds>"
        evil.emergencyContacts = [EmergencyContact(name: "Mum \"M\"", phone: "+61 400", relationship: "Parent")]
        let entries = FirstAidLogic.entries([evil], canViewSensitive: true)
        let html = FirstAidLogic.html(eventName: "Camp & Co", entries: entries, canViewSensitive: true, scope: .everyone,
                                      tz: "Australia/Sydney", rosterAt: Fixtures.iso(-10), now: Fixtures.now)
        #expect(html.contains("<title>Camp &amp; Co — First-aid sheet</title>"))
        #expect(html.contains("<h1>Camp &amp; Co — First-aid sheet</h1>"))
        #expect(html.contains("Generated "))
        #expect(html.contains("Roster synced 10 min ago"))
        #expect(html.contains(FirstAidLogic.sensitiveFooter))
        #expect(!html.contains("<script>"))
        #expect(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        #expect(html.contains("Nuts &amp; &lt;seeds&gt;"))
        #expect(html.contains("Mum &quot;M&quot; (Parent) +61 400"))
        #expect(html.contains("Anaphylaxis risk"))
        #expect(!html.contains(FirstAidLogic.restrictedNotice))
    }

    @Test func htmlWithoutSensitiveAccessHasFlagsOnly() {
        let entries = FirstAidLogic.entries(people, canViewSensitive: false)
        let html = FirstAidLogic.html(eventName: "Campfire", entries: entries, canViewSensitive: false, scope: .hereNow,
                                      tz: nil, rosterAt: nil, now: Fixtures.now)
        #expect(html.contains(FirstAidLogic.restrictedNotice))
        // Upstream sends medical details only to safeguarding leads and global admins.
        #expect(FirstAidLogic.restrictedNotice == "Medical details are visible to safeguarding leads and global admins.")
        #expect(html.contains("People here now"))
        #expect(!html.contains("Insulin"))
        #expect(!html.contains("Peanuts"))
        #expect(html.contains("Refrigerated medication"))
    }

    @Test func emptyHTMLSaysSo() {
        let html = FirstAidLogic.html(eventName: "Campfire", entries: [], canViewSensitive: true, scope: .everyone,
                                      tz: nil, rosterAt: nil, now: Fixtures.now)
        #expect(html.contains("No one has medical or safety flags."))
        #expect(html.contains("0 people"))
    }
}
