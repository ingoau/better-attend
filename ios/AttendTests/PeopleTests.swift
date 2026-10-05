import Foundation
import Testing
@testable import Attend

// Ports of the Android `PeopleFilterTest`, the note part of `BadgeNdefTest`, and what's still
// meaningful of `ParticipantBrowseOrderTest` (browse order is now passed in the route).

extension Fixtures {
    /// The five people from Android's `PeopleFilterTest`.
    static func person(
        _ id: Int,
        _ name: String,
        status: String = "complete",
        checkedInAt: String? = nil,
        email: String? = nil,
        pronouns: String? = nil,
        anaphylaxis: Bool = false,
        highSupport: Bool = false,
        fridge: Bool = false,
        waiver: Bool = true,
        nfc: Bool = false,
        inbound: Travel? = nil,
        outbound: Travel? = nil,
        diet: String? = nil,
        scans: [ContextScanSummary] = []
    ) -> Participant {
        Participant(
            participantId: "0000000\(id)-aaaa-4bbb-8ccc-dddddddddddd",
            participantEventId: "pe\(id)",
            displayName: String(name.split(separator: " ").first ?? ""), fullName: name, email: email, pronouns: pronouns,
            status: status, checkedInAt: checkedInAt, nfcBadgeAssigned: nfc,
            hasAnaphylaxisRisk: anaphylaxis, requiresRefrigeration: fridge, highSupportFlag: highSupport,
            waiverSigned: waiver, dietType: diet, travelInbound: inbound, travelOutbound: outbound, scansByContext: scans
        )
    }

    static let zoe = person(1, "Zoë Adams", checkedInAt: "2026-10-03T09:00:00Z", email: "zoe@example.com", pronouns: "she/her",
                            scans: [ContextScanSummary(scanContextId: "c1", scanContextName: "Desk", checksIn: true, scanCount: 1,
                                                       firstScannedAt: "2026-10-03T09:00:00Z")])
    static let arjun = person(2, "arjun Patel", anaphylaxis: true, inbound: Travel(mode: "plane"), diet: "vegan")
    static let bea = person(3, "Bea Brown", status: "in_progress", waiver: false, nfc: true, outbound: Travel(mode: "train"))
    static let carl = person(4, "Carl Chen", status: "withdrawn", checkedInAt: "2026-10-03T08:00:00Z")
    static let dan = person(5, "Dan Diaz", checkedInAt: "2026-10-03T10:00:00Z", highSupport: true,
                            scans: [ContextScanSummary(scanContextId: "c2", scanContextName: "Airport", scanCount: 2)])
    static let filterPeople = [zoe, arjun, bea, carl, dan]
}

@Suite struct PeopleFilterTests {
    private func run(_ query: String = "", quick: QuickFilter = .all, options: FilterOptions = FilterOptions(),
                     sort: PeopleSort = .name, sensitive: Bool = true) -> FilterResult {
        PeopleFilter.apply(Fixtures.filterPeople, query: query, quick: quick, options: options, sort: sort, canViewSensitive: sensitive)
    }

    private func ids(_ r: FilterResult) -> [String] { r.participants.map(\.participantEventId) }
    private func ids(_ list: [Participant]) -> [String] { list.map(\.participantEventId) }

    @Test func quickFilterCounts() {
        let counts = run().counts
        #expect(counts[.all] == 4) // withdrawn hidden
        #expect(counts[.here] == 2) // zoe, dan (carl withdrawn)
        #expect(counts[.notHere] == 1) // arjun (bea isn't complete)
        #expect(counts[.needsAttention] == 2)
        #expect(counts[.notComplete] == 1)
        #expect(counts[.withdrawn] == 1)
    }

    @Test func withdrawnOnlyInWithdrawnChip() {
        #expect(!ids(run()).contains(Fixtures.carl.id))
        #expect(ids(run(quick: .withdrawn)) == [Fixtures.carl.id])
    }

    @Test func searchIsAccentAndCaseInsensitiveAndMultiTerm() {
        #expect(ids(run("zoe")) == [Fixtures.zoe.id])
        #expect(ids(run("ADAMS she")) == [Fixtures.zoe.id])
        #expect(ids(run("example.com")) == [Fixtures.zoe.id])
        #expect(run("zoe nobody").participants.isEmpty)
        #expect(ids(run("  ZOË  ")) == [Fixtures.zoe.id])
    }

    @Test func searchMatchesShortCode() {
        #expect(ids(run("00000002")) == [Fixtures.arjun.id])
    }

    @Test func countsFollowSearch() {
        let counts = run("dan").counts
        #expect(counts[.all] == 1)
        #expect(counts[.here] == 1)
        #expect(counts[.notHere] == 0)
    }

    @Test func scannedAtContext() {
        #expect(ids(run(options: FilterOptions(scannedAtContextId: "c2"))) == [Fixtures.dan.id])
        #expect(ids(run(options: FilterOptions(scannedAtContextId: "c2", notScannedAtContext: true)))
            == ids([Fixtures.arjun, Fixtures.bea, Fixtures.zoe]))
    }

    @Test func travelFilters() {
        #expect(ids(run(options: FilterOptions(inboundTravel: .yes))) == [Fixtures.arjun.id])
        #expect(ids(run(options: FilterOptions(travelModes: ["train"]))) == [Fixtures.bea.id])
        #expect(ids(run(options: FilterOptions(inboundTravel: .no))) == ids([Fixtures.bea, Fixtures.dan, Fixtures.zoe]))
    }

    @Test func waiverNfcStatus() {
        #expect(ids(run(options: FilterOptions(waiverSigned: .no))) == [Fixtures.bea.id])
        #expect(ids(run(options: FilterOptions(nfcAssigned: .yes))) == [Fixtures.bea.id])
        #expect(ids(run(options: FilterOptions(statuses: ["in_progress"]))) == [Fixtures.bea.id])
        #expect(FilterOptions(statuses: ["x"], waiverSigned: .no, nfcAssigned: .yes).activeCount == 3)
        #expect(FilterOptions().activeCount == 0)
    }

    @Test func dietFilterIgnoredWithoutSensitiveAccess() {
        #expect(ids(run(options: FilterOptions(dietTypes: ["vegan"]))) == [Fixtures.arjun.id])
        #expect(run(options: FilterOptions(dietTypes: ["vegan"]), sensitive: false).participants.count == 4)
    }

    @Test func sortByName() {
        #expect(ids(run()) == ids([Fixtures.arjun, Fixtures.bea, Fixtures.dan, Fixtures.zoe]))
    }

    @Test func sortByRecentCheckIn() {
        #expect(ids(run(sort: .arrival)) == ids([Fixtures.dan, Fixtures.zoe, Fixtures.arjun, Fixtures.bea]))
    }

    @Test func sortByStatus() {
        // Checked in first, then complete, then in progress.
        #expect(ids(run(sort: .status)) == ids([Fixtures.dan, Fixtures.zoe, Fixtures.arjun, Fixtures.bea]))
    }

    @Test func sectionsOnlyWhenSortedByName() {
        let sections = PeopleFilter.sections(run().participants, .name)
        #expect(sections.map(\.letter) == ["A", "B", "D", "Z"])
        #expect(sections.flatMap(\.participants).count == 4)
        let flat = PeopleFilter.sections(run().participants, .status)
        #expect(flat.count == 1 && flat[0].letter == nil && flat[0].participants.count == 4)
        #expect(PeopleFilter.sections([], .status).isEmpty)
    }

    @Test func nonLetterNamesGoUnderHash() {
        #expect(PeopleFilter.sectionLetter(Fixtures.person(9, "42 Robot")) == "#")
        #expect(PeopleFilter.sectionLetter(Fixtures.person(9, "Élodie Martin")) == "E")
        #expect(PeopleFilter.sectionLetter(Fixtures.person(9, "Øyvind Berg")) == "#")
    }

    @Test func checkInScanPicksEarliestCheckInContext() {
        let x = Fixtures.person(7, "X", scans: [
            ContextScanSummary(scanContextId: "a", scanContextName: "Lunch", checksIn: false, firstScannedAt: "2026-10-03T07:00:00Z"),
            ContextScanSummary(scanContextId: "b", scanContextName: "Late desk", checksIn: true, firstScannedAt: "2026-10-03T09:00:00Z"),
            ContextScanSummary(scanContextId: "c", scanContextName: "Desk", checksIn: true, firstScannedAt: "2026-10-03T08:00:00Z"),
        ])
        #expect(PeopleFilter.checkInScan(x)?.scanContextName == "Desk")
    }

    @Test func checkInLine() {
        #expect(PeopleFilter.checkInLine(Fixtures.arjun, tz: "Australia/Sydney") == nil)
        let line = PeopleFilter.checkInLine(Fixtures.zoe, tz: "Australia/Sydney")
        #expect(line?.hasPrefix("Checked in ") == true)
        #expect(line?.hasSuffix(" · Desk") == true)
        #expect(line?.contains(Time.time("2026-10-03T09:00:00Z", tz: "Australia/Sydney")!) == true)
    }

    @Test func normalizeStripsAccentsAndCase() {
        #expect(PeopleFilter.normalize("  Zoë ÅNGSTRÖM ") == "zoe angstrom")
        #expect(PeopleFilter.normalize("Renée") == "renee")
    }

    @Test func worksOnTheSharedFixtures() {
        let r = PeopleFilter.apply(Fixtures.participants, query: "", quick: .all, options: FilterOptions(), sort: .name, canViewSensitive: true)
        #expect(r.counts[.withdrawn] == 1) // Isla
        #expect(r.counts[.needsAttention] == 2) // Maya (anaphylaxis), Noah (high support)
        #expect(!r.participants.contains { $0.status == "withdrawn" })
    }
}

@Suite struct ParticipantVisualTests {
    @Test func statusVisuals() {
        #expect(StatusVisual.of(Fixtures.zoe).kind == .here)
        #expect(StatusVisual.of(Fixtures.arjun).label == "Not here")
        #expect(StatusVisual.of(Fixtures.bea).label == "Registering")
        #expect(StatusVisual.of(Fixtures.carl).label == "Withdrawn") // withdrawn wins over checked in
        #expect(StatusVisual.of(Fixtures.person(8, "Ivy", status: "awaiting_guardian")).label == "Awaiting parent")
        #expect(StatusVisual.of(Fixtures.person(8, "Ivy", status: "invited")).kind == .invited)
    }

    @Test func labels() {
        #expect(PeopleText.status("in_progress") == "Registering")
        #expect(PeopleText.status(nil) == "Unknown")
        #expect(PeopleText.status("on_hold") == "On hold")
        #expect(PeopleText.humanize("custom_document") == "Custom document")
        #expect(PeopleText.humanize("  ") == nil)
        #expect(PeopleText.consent("medical_release") == "Medical release")
        #expect(PeopleText.consent(nil) == "Document")
        #expect(PeopleText.travelMode("plane") == "Flight")
        #expect(PeopleText.travelMode("boat") == "Other")
        #expect(PeopleText.date("2026-10-03")?.contains("2026") == true)
        #expect(PeopleText.date("soon") == "soon")
    }

    @Test func listTitleTellsNamesakesApart() {
        #expect(PeopleText.listTitle(Fixtures.zoe) == "Zoë Adams")
        var p = Fixtures.person(9, "Samantha Lee")
        p.displayName = "Sam"
        #expect(PeopleText.listTitle(p) == "Samantha Lee")
        p.displayName = "Tilly"
        #expect(PeopleText.listTitle(p) == "Tilly (Samantha Lee)")
        p.fullName = nil
        #expect(PeopleText.listTitle(p) == "Tilly")
    }

    @Test func safetyFlagsForRows() {
        #expect(Safety.flags(Fixtures.arjun).map(\.label) == ["Anaphylaxis risk"])
        #expect(Safety.flags(Fixtures.arjun).first?.danger == true)
        var um = Fixtures.bea
        um.travelOutbound?.isUnaccompaniedMinor = true
        #expect(Safety.flags(um).map(\.label) == ["Unaccompanied minor travel"])
        #expect(Safety.flags(Fixtures.zoe).isEmpty)
    }

    @Test func anaphylaxisDetailMergesAllergyFieldsWhenAllowed() {
        var p = Fixtures.arjun
        p.allergies = "Peanuts (anaphylaxis)"
        p.lifeThreateningAllergies = "Peanuts"
        #expect(Safety.alerts(p, canViewSensitive: true).first?.detail == "Peanuts (anaphylaxis)")
        p.lifeThreateningAllergies = "Shellfish"
        #expect(Safety.alerts(p, canViewSensitive: true).first?.detail == "Shellfish · Peanuts (anaphylaxis)")
        // Without sensitive access: a generic prompt, never the allergy itself.
        #expect(Safety.alerts(p, canViewSensitive: false).first?.detail == "Check their allergy plan with first aid.")
    }

    @Test func minorsWhoCantLeaveAlone() {
        var p = Fixtures.zoe
        p.personal = Personal(age: 15)
        p.safeguardingDetail = SafeguardingDetail(authorizedPickupAdults: "Jordan Adams")
        let alert = Safety.alerts(p, canViewSensitive: false).first
        #expect(alert?.title == "Can't leave unaccompanied")
        #expect(alert?.detail == "Pickup: Jordan Adams")
        p.canLeaveUnaccompanied = true
        #expect(Safety.alerts(p, canViewSensitive: false).isEmpty)
        p.canLeaveUnaccompanied = false
        p.personal = Personal(age: 19)
        #expect(Safety.alerts(p, canViewSensitive: false).isEmpty)
    }

    @Test func alertsOrderSafetyFirst() {
        var p = Fixtures.dan
        p.hasAnaphylaxisRisk = true
        p.requiresRefrigeration = true
        p.crossContaminationRisk = true
        p.medications = "EpiPen"
        p.travelInbound = Travel(isUnaccompaniedMinor: true)
        let titles = Safety.alerts(p, canViewSensitive: true).map(\.title)
        #expect(titles == ["Anaphylaxis risk", "Medication must be refrigerated", "High support needs", "Cross-contamination risk", "Unaccompanied minor"])
        #expect(Safety.alerts(p, canViewSensitive: true)[1].detail == "EpiPen")
        #expect(Safety.alerts(p, canViewSensitive: false)[1].detail == nil)
        #expect(!Safety.alerts(p, canViewSensitive: false).map(\.title).contains("Cross-contamination risk"))
    }
}

@Suite struct ParticipantDetailLogicTests {
    @Test func canChangeStatusFollowsRole() {
        var e = Fixtures.event
        #expect(ParticipantDetailLogic.canChangeStatus(e))
        e.role = "safeguarding_lead"
        #expect(!ParticipantDetailLogic.canChangeStatus(e))
        e.role = "ops"
        #expect(ParticipantDetailLogic.canChangeStatus(e))
        #expect(!ParticipantDetailLogic.canChangeStatus(nil))
    }

    @Test func defaultUndoSelection() {
        let desk = ContextScanSummary(scanContextId: "c1", checksIn: true, scanCount: 1)
        let lunch = ContextScanSummary(scanContextId: "c3", scanCount: 1)
        #expect(ParticipantDetailLogic.defaultUndoSelection([lunch]) == "c3")
        #expect(ParticipantDetailLogic.defaultUndoSelection([lunch, desk]) == "c1")
        #expect(ParticipantDetailLogic.defaultUndoSelection([lunch, ContextScanSummary(scanContextId: "c4")]) == nil)
    }

    @Test func scanPointsListEveryContextPlusRemovedOnes() {
        var p = Fixtures.zoe
        p.scansByContext.append(ContextScanSummary(scanContextId: "gone", scanContextName: "Old desk", scanCount: 1))
        let points = ParticipantDetailLogic.scanPoints(p, contexts: Array(Fixtures.contexts.reversed()))
        #expect(points.map(\.id) == ["c1", "c2", "c3", "gone"]) // by position, removed ones last
        #expect(points[0].scan?.scanContextName == "Desk")
        #expect(points[1].isTravelPickup && points[1].scan == nil)
        #expect(points[3].name == "Old desk" && points[3].scan != nil)
        #expect(ParticipantDetailLogic.scanPoints(Fixtures.arjun, contexts: []).isEmpty)
    }

    @Test func anEditIsShownWithoutBringingBackWhatItCleared() {
        var detail = Fixtures.participants[2]
        detail.phone = "+61 400 000 000"
        detail.pronouns = "she/her"
        detail.tshirtSize = "M"
        detail.lifeThreateningAllergies = "Peanuts"
        detail.personal = Personal(legalFirstName: "Maya", legalLastName: "Chen", preferredName: "May", age: 15,
                                   tshirtSize: "M", dateOfBirth: "2011-01-01")
        // Upstream's PATCH answer is the roster shape: no detail sections, and a cleared phone is just null.
        var live = Fixtures.participants[2]
        live.phone = nil
        live.pronouns = nil
        live.tshirtSize = nil
        let edit = ParticipantEdit(legalLastName: "Chen-Li", preferredName: "", phone: "", pronouns: "", tshirtSize: "", dateOfBirth: "2010-12-31")
        let now = Time.parse("2026-10-03T00:00:00Z")!
        let p = ParticipantDetailLogic.applying(edit, live: live, to: detail, now: now)
        #expect(p.phone == nil, "cleared, not restored from the old profile")
        #expect(p.pronouns == nil)
        #expect(p.tshirtSize == nil)
        #expect(p.personal?.tshirtSize == nil)
        #expect(p.personal?.legalFirstName == "Maya", "untouched fields stay")
        #expect(p.personal?.legalLastName == "Chen-Li")
        #expect(p.personal?.preferredName == nil)
        #expect(p.personal?.dateOfBirth == "2010-12-31")
        #expect(p.personal?.age == 15)
        #expect(p.lifeThreateningAllergies == "Peanuts", "detail-only fields stay")

        // Fields the edit didn't touch keep the old profile's values when the PATCH answer leaves them out.
        let pronounsOnly = ParticipantDetailLogic.applying(ParticipantEdit(pronouns: "they/them"), live: live, to: detail, now: now)
        #expect(pronounsOnly.pronouns == "they/them")
        #expect(pronounsOnly.phone == "+61 400 000 000")
        #expect(pronounsOnly.personal == detail.personal)
    }

    @Test func overlayKeepsDetailOnlyFieldsAfterADeltaSync() {
        var detail = Fixtures.participants[2]
        detail.lifeThreateningAllergies = "Peanuts"
        detail.personal = Personal(age: 16)
        detail.emergencyContacts = [EmergencyContact(name: "Jordan", phone: "+61400000000")]
        var live = Fixtures.participants[2]
        live.checkedInAt = "2026-10-03T02:00:00Z"
        let merged = ParticipantDetailLogic.overlay(live: live, detail: detail)
        #expect(merged?.checkedInAt == "2026-10-03T02:00:00Z") // live data wins
        #expect(merged?.lifeThreateningAllergies == "Peanuts")
        #expect(merged?.personal?.age == 16)
        #expect(merged?.emergencyContacts?.first?.name == "Jordan")
        #expect(ParticipantDetailLogic.overlay(live: nil, detail: detail) == detail)
        #expect(ParticipantDetailLogic.overlay(live: live, detail: nil) == live)
        // A detail for someone else is ignored.
        #expect(ParticipantDetailLogic.overlay(live: live, detail: Fixtures.participants[3]) == live)
    }

    @Test func webURL() {
        #expect(ParticipantDetailLogic.webURL(event: Fixtures.event, participantEventId: "pe1")?.absoluteString
            == "https://attend.hackclub.com/admin/events/campfire-sydney/participants/pe1")
        #expect(ParticipantDetailLogic.webURL(event: nil, participantEventId: "pe1") == nil)
    }
}

@Suite struct BrowseOrderTests {
    @Test func openedFromListPagesThroughThatList() {
        let pages = BrowseOrder.pages(["a", "b", "c"], current: "b")
        #expect(pages == ["a", "b", "c"])
        #expect(BrowseOrder.previous(pages, current: "b") == "a")
        #expect(BrowseOrder.next(pages, current: "b") == "c")
        #expect(BrowseOrder.position(pages, current: "b") == "2 of 3")
    }

    @Test func endsHaveNoNeighbour() {
        let pages = ["a", "b", "c"]
        #expect(BrowseOrder.previous(pages, current: "a") == nil)
        #expect(BrowseOrder.next(pages, current: "c") == nil)
    }

    @Test func nothingPassedIsASinglePage() {
        #expect(BrowseOrder.pages([], current: "x") == ["x"])
        #expect(BrowseOrder.position(["x"], current: "x") == nil)
    }

    @Test func listWithoutThisPersonIsIgnored() {
        #expect(BrowseOrder.pages(["a", "b"], current: "z") == ["z"])
    }

    @Test func duplicatesDropped() {
        #expect(BrowseOrder.pages(["a", "b", "a"], current: "a") == ["a", "b"])
    }
}

@Suite struct NoteRulesTests {
    @Test func validation() {
        #expect(NoteRules.validate("   ", type: "ops", sensitivity: "normal") == "Write something first.")
        #expect(NoteRules.validate("Hello", type: "safeguarding", sensitivity: "restricted") == nil)
        #expect(NoteRules.validate("Hello", type: "medical", sensitivity: "normal") == "Pick a note type.")
        #expect(NoteRules.validate("Hello", type: "ops", sensitivity: "secret") == "Pick a sensitivity.")
        #expect(NoteRules.validate(String(repeating: "x", count: NoteRules.maxLength), type: "ops", sensitivity: "normal") == nil)
        #expect(NoteRules.validate(String(repeating: "x", count: NoteRules.maxLength + 1), type: "ops", sensitivity: "normal")
            == "Notes can be up to \(NoteRules.maxLength) characters.")
        // Surrounding whitespace doesn't count towards the limit.
        #expect(NoteRules.validate("  " + String(repeating: "x", count: NoteRules.maxLength) + "\n", type: "ops", sensitivity: "normal") == nil)
    }

    @Test func labels() {
        #expect(NoteRules.typeLabel("logistical") == "Logistics")
        #expect(NoteRules.typeLabel("anything") == "Ops")
    }

    @Test func badgeRecordsRoundTripForTheWriter() {
        // The writer verifies a write by reading the token back out of the same records.
        let records = NfcBadgeFormat.badgeRecords(slackUserId: "U01ABCDEF", token: " tok-123 ")
        #expect(NfcBadgeFormat.readToken(records) == "tok-123")
    }
}

@Suite struct ContactLinkTests {
    @Test func phoneLinks() {
        #expect(ContactLinks.dialable("+61 (400) 000-123") == "+61400000123")
        #expect(ContactLinks.call("+61 400 000 123")?.absoluteString == "tel:+61400000123")
        #expect(ContactLinks.sms("0400 000 123")?.absoluteString == "sms:0400000123")
        #expect(ContactLinks.faceTime("+61400000123")?.absoluteString == "facetime:+61400000123")
        #expect(ContactLinks.whatsApp("+61 400 000 123")?.absoluteString == "https://wa.me/61400000123")
        #expect(ContactLinks.call("n/a") == nil)
        #expect(ContactLinks.call("+") == nil)
    }

    @Test func emailLinks() {
        #expect(ContactLinks.email(" maya@example.com ")?.absoluteString == "mailto:maya@example.com")
        #expect(ContactLinks.email("not an email") == nil)
    }
}
