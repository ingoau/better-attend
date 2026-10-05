import Foundation

/// Realistic, event-sized fake data for demo mode, previews and UI tests. Times are relative to
/// `now`, so the main event ("Campfire Sydney") is always live: 143 registrations, 121 complete,
/// 84 checked in (12 in the last hour), 61 at lunch, 22 collected from the airport.
struct DemoData: Sendable {
    let now: Date
    var user: User
    var events: [Event]
    var contexts: [String: [ScanContext]]
    var participants: [String: [Participant]]
    var notes: [String: [Note]] = [:]
    var travel: TravelCalendar
    var blasts: [SlackBlast]
    var tickets: [Ticket]

    static let mainEventId = "0f5d1a64-2a0e-4d0c-8a3b-1e4c9a7b3c21"
    static let upcomingEventId = "7c2e9b10-5d4f-4e8a-9b6c-3f1a2d4e5b60"
    static let pastEventId = "a93d4c27-1b8e-4f5a-8c7d-6e2f9a0b1c38"
    static let timezone = "Australia/Sydney"

    private static let first = ["Sam", "Arjun", "Maya", "Leo", "Priya", "Noah", "Zara", "Kai", "Ella", "Oliver", "Isla", "Jack",
                                "Mia", "Ethan", "Aisha", "Lucas", "Chloe", "Ravi", "Grace", "Hugo", "Nina", "Theo", "Ivy", "Omar"]
    private static let last = ["Lee", "Patel", "Chen", "Nguyen", "Sharma", "Williams", "Ahmed", "Tanaka", "Brown", "Smith", "Wilson", "Taylor"]
    private static let pronouns: [String?] = ["she/her", "he/him", "they/them", nil]
    private static let cities = [("Melbourne", "MEL", "QF"), ("Brisbane", "BNE", "VA"), ("Auckland", "AKL", "NZ"), ("Perth", "PER", "QF"), ("Adelaide", "ADL", "JQ")]

    init(now: Date = Date()) {
        self.now = now
        func iso(_ minutes: Double) -> String { Time.iso(now.addingTimeInterval(minutes * 60)) }

        user = User(id: "u-orpheus", name: "Orpheus Dino", email: "orpheus@hackclub.com", globalAdmin: false, isOrganizer: true, isParticipant: true)

        events = [
            Event(id: Self.mainEventId, name: "Campfire Sydney", slug: "campfire-sydney", startsAt: iso(-180), endsAt: iso(60 * 30),
                  timezone: Self.timezone, locationCity: "Sydney", role: "event_admin", canViewParticipantPii: true,
                  canViewParticipants: true, canViewSensitiveData: true, travelEnabled: true),
            Event(id: Self.upcomingEventId, name: "Scrapyard Melbourne", slug: "scrapyard-melbourne", startsAt: iso(60 * 24 * 12),
                  endsAt: iso(60 * 24 * 12 + 60 * 34), timezone: "Australia/Melbourne", locationCity: "Melbourne", role: "ops",
                  canViewParticipantPii: true, canViewParticipants: true),
            Event(id: Self.pastEventId, name: "Counterspell Brisbane", slug: "counterspell-brisbane", startsAt: iso(-60 * 24 * 40),
                  endsAt: iso(-60 * 24 * 39), timezone: "Australia/Brisbane", locationCity: "Brisbane", role: "read_only",
                  canViewParticipants: false),
        ]

        contexts = [
            Self.mainEventId: [
                ScanContext(id: "c1", name: "Check-in desk", checksIn: true, position: 0),
                ScanContext(id: "c2", name: "Airport pickup", isTravelPickup: true, isAirport: true, position: 1),
                ScanContext(id: "c3", name: "Saturday lunch", position: 2, startsAt: iso(-30), endsAt: iso(60)),
                ScanContext(id: "c4", name: "Hotel bus", position: 3, startsAt: iso(60 * 9), endsAt: iso(60 * 10)),
            ],
            Self.upcomingEventId: [ScanContext(id: "m1", name: "Check-in", checksIn: true, position: 0)],
            Self.pastEventId: [ScanContext(id: "b1", name: "Check-in", checksIn: true, position: 0)],
        ]

        var main: [Participant] = (0..<142).map { i in
            let fn = Self.first[i % Self.first.count]
            let ln = Self.last[(i / Self.first.count + i) % Self.last.count]
            let status: String = switch i {
            case ..<120: "complete"
            case ..<124: "invited"
            case ..<129: "in_progress"
            case ..<132: "awaiting_guardian"
            case ..<138: "withdrawn"
            default: "rejected"
            }
            let checkedIn = i < 84
            // The first 12 arrived in the last hour, most recent first.
            let checkedInAt = checkedIn ? iso(i < 12 ? -(Double(i) * 4 + 2) : -(70 + Double(i) * 3)) : nil
            var scans: [ContextScanSummary] = []
            if let checkedInAt {
                scans.append(ContextScanSummary(scanContextId: "c1", scanContextName: "Check-in desk", checksIn: true, scanCount: 1,
                                                firstScannedAt: checkedInAt, lastScannedAt: checkedInAt))
            }
            if (40..<62).contains(i) {
                scans.append(ContextScanSummary(scanContextId: "c2", scanContextName: "Airport pickup", isTravelPickup: true, scanCount: 1,
                                                firstScannedAt: iso(-200), lastScannedAt: iso(-200)))
            }
            if checkedIn && i % 4 != 3 {
                scans.append(ContextScanSummary(scanContextId: "c3", scanContextName: "Saturday lunch", scanCount: 1,
                                                firstScannedAt: iso(-Double(i % 25) - 1), lastScannedAt: iso(-Double(i % 25) - 1)))
            }
            var p = Participant(
                participantId: String(format: "%08x-e5f6-4a7b-8c9d-0e1f2a3b4c5d", 0x5a1b_2000 + i * 7919),
                participantEventId: String(format: "%08x-8d7e-4f60-9a1b-2c3d4e5f6a7b", 0x3b1f_9000 + i * 104_729),
                displayName: fn, fullName: "\(fn) \(ln)", email: "\(fn.lowercased()).\(ln.lowercased())\(i)@example.com",
                phone: String(format: "+614%08d", 12_345_000 + i * 37), slackUserId: i % 30 == 29 ? nil : String(format: "U%05d", i),
                pronouns: Self.pronouns[i % Self.pronouns.count], tshirtSize: ["S", "M", "L", "XL"][i % 4],
                status: status, checkedInAt: checkedInAt,
                nfcBadgeToken: i % 3 == 0 ? String(format: "b%07x-badge", i * 7717) : nil, nfcBadgeAssigned: i % 3 == 0,
                hasAnaphylaxisRisk: [3, 57, 101].contains(i), requiresRefrigeration: i == 3, highSupportFlag: [8, 90].contains(i),
                canLeaveUnaccompanied: i % 5 == 0, waiverSigned: i < 125,
                updatedAt: iso(-300),
                allergies: [3, 57, 101].contains(i) ? ["Peanuts (anaphylaxis)", "Shellfish (anaphylaxis)", "Bee stings (anaphylaxis)"][[3, 57, 101].firstIndex(of: i)!] : (i % 11 == 0 ? "Hay fever" : nil),
                medicalConditions: i % 17 == 0 ? "Asthma" : nil,
                medications: i % 17 == 0 ? "Ventolin as needed" : ([3, 57, 101].contains(i) ? "EpiPen" : nil),
                dietType: ["omnivore", "vegetarian", "vegan", "halal", "omnivore", "gluten_free"][i % 6],
                scansByContext: scans,
                groups: i % 9 == 0 ? [ParticipantGroup(id: "g1", name: "Team Blue", color: "#3b82f6")] : (i % 13 == 0 ? [ParticipantGroup(id: "g2", name: "Mentors", color: "#22c55e")] : [])
            )
            if (40..<62).contains(i) || (70..<76).contains(i) {
                let (city, code, airline) = Self.cities[i % Self.cities.count]
                let arrive = (40..<62).contains(i) ? iso(-230) : iso(Double(i - 69) * 35)
                p.travelInbound = Travel(
                    direction: "inbound", mode: "plane", isUnaccompaniedMinor: i == 50, carrier: airline == "QF" ? "Qantas" : "Virgin Australia",
                    flightNumber: "\(airline)\(400 + i)", departureCity: city, arrivalCity: "Sydney", arrivalTime: arrive,
                    legs: [TravelLeg(position: 0, flightCode: "\(airline)\(400 + i)", departureAirport: code, arrivalAirport: "SYD",
                                     departureTime: iso(-400), arrivalTime: arrive, liveStatus: (40..<62).contains(i) ? "Landed" : "On time",
                                     travelPickedUpAt: (40..<62).contains(i) ? iso(-200) : nil)]
                )
                p.travelOutbound = Travel(direction: "outbound", mode: "plane", carrier: "Qantas", flightNumber: "QF\(500 + i)",
                                          departureCity: "Sydney", arrivalCity: city, departureTime: iso(60 * 32))
            }
            return p
        }
        main = main.enumerated().map { i, p in Self.withFirstAidDetails(p, index: i) }
        // The signed-in organizer is also registered, so scanning their own ticket works.
        main.append(Participant(
            participantId: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", participantEventId: "3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b",
            displayName: "Orpheus", fullName: "Orpheus Dino", email: "orpheus@hackclub.com", phone: "+61400000000",
            slackUserId: "U0ORPHEUS", pronouns: "they/them", status: "complete", waiverSigned: true, dietType: "omnivore"
        ))
        participants = [
            Self.mainEventId: Roster.sortedByName(main),
            Self.upcomingEventId: Roster.sortedByName(main.prefix(40).enumerated().map { i, p in
                var q = p
                q.participantEventId = String(format: "%08x-1111-4f60-9a1b-2c3d4e5f6a7b", 0x7a00_0000 + i * 104_729)
                q.checkedInAt = nil
                q.scansByContext = []
                q.status = i < 30 ? "complete" : "in_progress"
                return q
            }),
        ]

        let t: [(Int, String, String, Double?, String, String?, String?, Bool, [ParticipantGroup])] = [
            (0, "inbound", "plane", -95, "MEL → SYD", "QF401", "collected", false, [ParticipantGroup(id: "g1", name: "Team Blue", color: "#3b82f6")]),
            (1, "inbound", "plane", -60, "BNE → SYD", "VA914", "checked_in", false, []),
            (2, "inbound", "plane", 20, "AKL → SYD", "NZ103", "awaiting_pickup", true, [ParticipantGroup(id: "g1", name: "Team Blue", color: "#3b82f6"), ParticipantGroup(id: "g2", name: "Mentors", color: "#22c55e")]),
            (3, "inbound", "train", 45, "Central → Venue", "NSW TrainLink", "awaiting_pickup", false, []),
            (4, "inbound", "plane", 75, "PER → SYD", "QF566 · QF412", "awaiting_pickup", false, []),
            (5, "inbound", "car", 120, "Parent drop-off", nil, "pickup_not_needed", false, []),
            (6, "inbound", "bus", 150, "Canberra → Sydney", "Murrays", "awaiting_pickup", false, []),
            (7, "outbound", "plane", 60 * 24 + 360, "SYD → MEL", "JQ508", nil, false, []),
            (8, "outbound", "plane", 60 * 48 - 60, "SYD → AKL", "NZ104", nil, true, []),
            (9, "outbound", "train", 60 * 48 + 30, "Venue → Central", nil, nil, false, []),
            (10, "inbound", "plane", nil, "Details pending", nil, "awaiting_pickup", false, []),
        ]
        let zone = Time.zone(Self.timezone)
        let byIndex = main
        let entries = t.map { i, dir, mode, minutes, route, ref, pickup, um, groups in
            let p = byIndex[i * 5 + 40]
            let at = minutes.map { now.addingTimeInterval($0 * 60) }
            return TravelEntry(
                id: "tr\(i)", participantId: p.participantId, participantEventId: p.participantEventId,
                participantName: p.fullName, participantPreferredName: p.displayName, direction: dir, mode: mode,
                primaryTimeAt: at.map(Time.iso), agendaDate: at.map { CalendarDay($0, in: zone).description },
                route: route, reference: ref, pickupState: pickup, isUnaccompaniedMinor: um, groups: groups
            )
        }
        travel = TravelCalendar(
            eventTimezone: Self.timezone,
            dates: Array(Set(entries.compactMap(\.agendaDate))).sorted(),
            entries: entries,
            counts: DemoData.counts(entries)
        )

        blasts = [
            SlackBlast(id: "b3", message: "Buses to the hotel leave from the main entrance at <b>9pm sharp</b>.<br>Bring everything with you!",
                       status: "completed", recipientCount: 116, sentCount: 116, failedCount: 0, createdAt: iso(-12), sentBy: "Heidi"),
            SlackBlast(id: "b1", message: "Lunch is ready in the atrium! 🌮 Vegetarian and allergen-free options are on the left table.",
                       status: "completed", recipientCount: 120, sentCount: 118, failedCount: 2, createdAt: iso(-40), sentBy: "Orpheus Dino"),
            SlackBlast(id: "b0", message: "Welcome to Campfire Sydney! Check-in opens at 9am in the foyer. Please have your ticket QR ready.",
                       status: "completed", recipientCount: 120, sentCount: 120, failedCount: 0, createdAt: iso(-60 * 20), sentBy: "Orpheus Dino"),
            SlackBlast(id: "bf", message: "Test message", status: "failed", recipientCount: 0, sentCount: 0, failedCount: 0,
                       createdAt: iso(-60 * 26), sentBy: "Heidi"),
        ]

        let campfire = TicketEvent(id: Self.mainEventId, name: "Campfire Sydney", slug: "campfire-sydney", startsAt: events[0].startsAt,
                                   endsAt: events[0].endsAt, timezone: Self.timezone, locationCity: "Sydney",
                                   locationAddress: "Sydney Startup Hub, 11 York St", locationCountry: "Australia",
                                   locationLatitude: -33.8651, locationLongitude: 151.2055)
        tickets = [
            Ticket(id: "3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b", participantId: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", status: "complete",
                   displayStatus: "Complete", confirmed: true, checkedIn: false, attendeeName: "Orpheus Dino",
                   qrPayload: "attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", shortCode: "A1B2C3D4",
                   appleWalletUrl: "/api/v1/tickets/3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b/wallet",
                   onboardingUrl: "https://attend.hackclub.com/onboarding", event: campfire, canDownloadTicket: true,
                   travelInbound: TicketTravel(direction: "inbound", mode: "plane", carrier: "Qantas", flightNumber: "QF401",
                                               departureCity: "Melbourne", arrivalCity: "Sydney", departureTime: iso(-420), arrivalTime: iso(-330),
                                               legs: [TicketTravelLeg(flightCode: "QF401", departureAirport: "MEL", arrivalAirport: "SYD",
                                                                      departureTime: iso(-420), arrivalTime: iso(-330))]),
                   messages: [
                       TicketMessage(id: "m2", subject: "Lunch is ready", body: "<p>Lunch is ready in the atrium! 🌮</p>", senderName: "Orpheus", deliveredAt: iso(-40)),
                       TicketMessage(id: "m1", subject: "Welcome to Campfire!", body: "<p>Doors open at 9am. Bring your laptop charger!</p><p>See you soon.</p>",
                                     senderName: "Orpheus", deliveredAt: iso(-60 * 24 * 3)),
                   ]),
            Ticket(id: "t-scrapyard", participantId: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", status: "awaiting_guardian",
                   displayStatus: "Awaiting Parent", confirmed: false, attendeeName: "Orpheus Dino",
                   qrPayload: "attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", shortCode: "A1B2C3D4",
                   onboardingUrl: "https://attend.hackclub.com/onboarding",
                   event: TicketEvent(id: Self.upcomingEventId, name: "Scrapyard Melbourne", startsAt: events[1].startsAt, endsAt: events[1].endsAt,
                                      timezone: "Australia/Melbourne", locationCity: "Melbourne", locationCountry: "Australia")),
            Ticket(id: "t-counterspell", participantId: "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", status: "complete", displayStatus: "Complete",
                   confirmed: true, checkedIn: true, attendeeName: "Orpheus Dino",
                   qrPayload: "attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", shortCode: "A1B2C3D4",
                   event: TicketEvent(id: Self.pastEventId, name: "Counterspell Brisbane", startsAt: events[2].startsAt, endsAt: events[2].endsAt,
                                      timezone: "Australia/Brisbane", locationCity: "Brisbane", locationCountry: "Australia")),
        ]
    }

    static func counts(_ entries: [TravelEntry]) -> TravelCounts {
        TravelCounts(
            total: entries.count,
            inbound: entries.count(where: { $0.direction == "inbound" }),
            outbound: entries.count(where: { $0.direction == "outbound" }),
            scheduled: entries.count(where: { $0.primaryTimeAt != nil }),
            unscheduled: entries.count(where: { $0.primaryTimeAt == nil }),
            awaitingPickup: entries.count(where: { $0.pickupState == "awaiting_pickup" }),
            collected: entries.count(where: { $0.pickupState == "collected" }),
            checkedIn: entries.count(where: { $0.pickupState == "checked_in" }),
            pickupNotNeeded: entries.count(where: { $0.pickupState == "pickup_not_needed" })
        )
    }

    /// The sensitive fields the list endpoint includes for safeguarding leads and admins, so the
    /// first-aid sheet has realistic medical needs and contacts to show. Safety flags (and so the
    /// "Need attention" count) are left as they are.
    static func withFirstAidDetails(_ p: Participant, index i: Int) -> Participant {
        var p = p
        switch i {
        case 3: p.lifeThreateningAllergies = "Peanuts, tree nuts"
        case 57: p.lifeThreateningAllergies = "Shellfish"
        case 101: p.lifeThreateningAllergies = "Bee and wasp stings"
        case 23, 65:
            p.crossContaminationRisk = true
            p.allergies = p.allergies ?? "Coeliac disease (gluten)"
        case 46:
            p.medicalConditions = "Type 1 diabetes"
            p.medications = "Insulin pump, glucose tablets in backpack"
        case 8: p.medicalConditions = "Anxiety; may need a quiet space"
        default: break
        }
        let needsFirstAid = p.hasAnaphylaxisRisk || p.requiresRefrigeration || p.crossContaminationRisk || p.highSupportFlag
            || [p.lifeThreateningAllergies, p.allergies, p.medicalConditions, p.medications].contains { $0 != nil }
        guard needsFirstAid else { return p }
        let last = p.fullName?.split(separator: " ").last.map(String.init) ?? "Lee"
        p.emergencyContacts = [
            EmergencyContact(name: "Jordan \(last)", phone: String(format: "+61411%06d", 100_000 + i * 37), relationship: "Parent", priority: 1),
            EmergencyContact(name: "Sam \(last)", phone: String(format: "+61422%06d", 200_000 + i * 41), relationship: "Aunt", priority: 2),
        ]
        if i % 2 == 1 {
            p.parentGuardianName = "Alex \(last)"
            p.parentGuardianPhone = String(format: "+61433%06d", 300_000 + i * 43)
            p.parentGuardianEmail = "alex.\(last.lowercased())@example.com"
        }
        return p
    }

    /// The detail-only blocks the list endpoint leaves out.
    func detail(of p: Participant) -> Participant {
        var d = p
        let seed = p.participantEventId.utf8.reduce(0) { ($0 &* 31 &+ Int($1)) & 0x7fff_ffff }
        let age = 13 + seed % 6
        let first = p.displayName ?? "Alex"
        let last = p.fullName?.split(separator: " ").last.map(String.init) ?? "Lee"
        d.personal = Personal(legalFirstName: first, legalLastName: last, preferredName: first, age: age, tshirtSize: p.tshirtSize,
                              dateOfBirth: "20\(String(format: "%02d", 26 - age))-04-12",
                              address: Address(line1: "\(10 + seed % 90) Example St", city: "Melbourne", state: "VIC", postalCode: "3000", country: "Australia"))
        d.accommodation = Accommodation(checkInDate: CalendarDay(now, in: Time.zone(Self.timezone)).description,
                                        checkOutDate: CalendarDay(now, in: Time.zone(Self.timezone)).adding(days: 2).description,
                                        assignedRoom: "\(200 + seed % 40)", venueName: "Harbour Hotel",
                                        quietRoomPreference: seed % 3 == 0)
        d.guardians = age < 18 ? [Guardian(name: "Jordan \(last)", email: "jordan.\(last.lowercased())@example.com", phone: "+61411111111",
                                           relationship: "Parent", isPrimary: true, status: "completed", mediaPermission: true,
                                           photoPermission: seed % 4 != 0, travelPermission: true, emergencyMedicalConsent: true,
                                           otcMedicationConsent: seed % 2 == 0,
                                           emergencyContacts: [EmergencyContact(name: "Sam \(last)", phone: "+61422222222", relationship: "Aunt", priority: 1)])] : []
        d.consents = [
            Consent(consentType: "waiver", status: p.waiverSigned ? "signed" : "sent", sentAt: Time.iso(now.addingTimeInterval(-86_400 * 20)),
                    signedAt: p.waiverSigned ? Time.iso(now.addingTimeInterval(-86_400 * 18)) : nil),
            Consent(consentType: "media_release", status: "signed", signedAt: Time.iso(now.addingTimeInterval(-86_400 * 18))),
        ]
        d.emergencyContacts = [EmergencyContact(name: "Jordan \(last)", phone: "+61411111111", relationship: "Parent", priority: 1)]
        d.parentGuardianName = age < 18 ? "Jordan \(last)" : nil
        d.parentGuardianPhone = age < 18 ? "+61411111111" : nil
        if p.hasAnaphylaxisRisk {
            d.medicalDetail = MedicalDetail(allergySeverity: "severe", emergencyActionPlan: "EpiPen in the blue bag; first aid has a spare. Call 000 after use.")
            d.lifeThreateningAllergies = p.allergies
        }
        d.dietaryDetail = DietaryDetail(intolerances: seed % 5 == 0 ? "Lactose" : nil, notes: nil)
        if seed % 4 == 0 {
            d.accessibility = Accessibility(noiseSensitivity: true, needsCaptioning: seed % 8 == 0, neurodivergentNotes: "Prefers written instructions.",
                                            hasAutism: seed % 8 == 0, prayerSpaceRequired: seed % 12 == 0)
        }
        if p.highSupportFlag {
            d.safeguardingDetail = SafeguardingDetail(highSupportNotes: "Check in with them at meal times.",
                                                      authorizedPickupAdults: "Jordan \(last)", otherInstructions: "Not to leave the venue unaccompanied.")
        }
        return d
    }
}
