package au.ingo.betterattend.ui.preview

import au.ingo.betterattend.data.model.*
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Realistic fake data for @Previews and screenshot tests. Never used at runtime. */
object SampleData {
    private val now: Instant = Instant.parse("2026-10-03T01:30:00Z")
    private fun iso(minutesFromNow: Long) = now.plus(minutesFromNow, ChronoUnit.MINUTES).toString()

    val event = Event(
        id = "0f5d1a64-2a0e-4d0c-8a3b-1e4c9a7b3c21", name = "Campfire Sydney", slug = "campfire-sydney",
        startsAt = "2026-10-02T22:00:00Z", endsAt = "2026-10-04T06:00:00Z", timezone = "Australia/Sydney",
        locationCity = "Sydney", role = "event_admin", canViewParticipantPii = true, canViewParticipants = true,
        canViewSensitiveData = true, travelEnabled = true,
    )
    val events = listOf(
        event,
        Event(id = "e2", name = "Scrapyard Melbourne", slug = "scrapyard-mel", startsAt = "2026-11-14T22:00:00Z", endsAt = "2026-11-15T08:00:00Z", timezone = "Australia/Melbourne", locationCity = "Melbourne", role = "ops"),
        Event(id = "e3", name = "Counterspell Brisbane", slug = "counterspell-bne", startsAt = "2026-06-01T22:00:00Z", endsAt = "2026-06-02T08:00:00Z", timezone = "Australia/Brisbane", locationCity = "Brisbane", role = "ops"),
    )

    val user = User(id = "u1", name = "Orpheus Dino", email = "orpheus@hackclub.com", globalAdmin = false, isOrganizer = true, isParticipant = true)

    val contexts = listOf(
        ScanContext(id = "c1", name = "Check-in desk", checksIn = true, position = 0),
        ScanContext(id = "c2", name = "Airport pickup", isTravelPickup = true, isAirport = true, position = 1),
        ScanContext(id = "c3", name = "Saturday lunch", position = 2, startsAt = "2026-10-04T12:00:00+10:00", endsAt = "2026-10-04T13:30:00+10:00"),
    )

    private val names = listOf(
        "Sam Lee" to "she/her", "Arjun Patel" to "he/him", "Maya Chen" to "she/her", "Leo Nguyen" to "he/him",
        "Priya Sharma" to "she/her", "Noah Williams" to "he/him", "Zara Ahmed" to null, "Kai Tanaka" to "they/them",
        "Ella Brown" to "she/her", "Oliver Smith" to "he/him", "Isla Wilson" to "she/her", "Jack Taylor" to "he/him",
    )

    val participants: List<Participant> = names.mapIndexed { i, (name, pronouns) ->
        val checkedIn = i % 3 != 0
        val first = name.substringBefore(' ')
        Participant(
            participantId = "a1b2c3d${i}-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
            participantEventId = "3b1f9a2${i}-8d7e-4f60-9a1b-2c3d4e5f6a7b",
            displayName = first, fullName = name, email = "${first.lowercase()}@example.com", phone = "+6140000000$i",
            pronouns = pronouns, status = if (i == 11) "awaiting_guardian" else if (i == 10) "withdrawn" else "complete",
            checkedInAt = if (checkedIn) iso(-15L * i) else null,
            hasAnaphylaxisRisk = i == 2, highSupportFlag = i == 5, requiresRefrigeration = i == 2,
            waiverSigned = i != 11, allergies = if (i == 2) "Peanuts (anaphylaxis)" else null,
            dietType = if (i % 4 == 0) "vegetarian" else "omnivore", nfcBadgeToken = "e4b1-$i", nfcBadgeAssigned = i % 2 == 0,
            slackUserId = "U0$i",
            scansByContext = if (checkedIn) listOf(ContextScanSummary("c1", "Check-in desk", checksIn = true, scanCount = 1, firstScannedAt = iso(-15L * i), lastScannedAt = iso(-15L * i))) else emptyList(),
            travelInbound = if (i < 6) Travel(direction = "inbound", mode = "plane", carrier = "Qantas", flightNumber = "QF40$i",
                departureCity = "Melbourne", arrivalCity = "Sydney", arrivalTime = iso(60L * i),
                legs = listOf(TravelLeg(flightCode = "QF40$i", departureAirport = "MEL", arrivalAirport = "SYD", arrivalTime = iso(60L * i), liveStatus = if (i < 2) "Landed" else "Scheduled"))) else null,
        )
    }

    val participantDetail: Participant = participants[2].copy(
        personal = Personal(legalFirstName = "Maya", legalLastName = "Chen", preferredName = "Maya", age = 16, tshirtSize = "M", dateOfBirth = "2010-04-12"),
        accommodation = Accommodation(assignedRoom = "204", venueName = "Hotel X", checkInDate = "2026-10-03", checkOutDate = "2026-10-05"),
        guardians = listOf(Guardian(name = "Jordan Chen", phone = "+61411111111", email = "jordan@example.com", relationship = "Parent", isPrimary = true, status = "completed", mediaPermission = true, photoPermission = true, travelPermission = true)),
        consents = listOf(Consent(consentType = "waiver", status = "signed", signedAt = iso(-5000)), Consent(consentType = "media", status = "sent", sentAt = iso(-6000))),
        medicalDetail = MedicalDetail(allergySeverity = "severe", emergencyActionPlan = "EpiPen in blue bag; first aid has a spare."),
        safeguardingDetail = SafeguardingDetail(authorizedPickupAdults = "Jordan Chen"),
    )

    val notes = listOf(
        Note(id = "n1", content = "Arrived with parent, EpiPen handed to first aid.", noteType = "ops", createdAt = iso(-30), author = NoteAuthor(name = "Orpheus Dino")),
        Note(id = "n2", content = "Prefers quiet room.", noteType = "logistical", sensitivity = "restricted", createdAt = iso(-300), author = NoteAuthor(name = "Heidi")),
    )

    val ticketEvent = TicketEvent(
        id = event.id, name = event.name, slug = event.slug, startsAt = event.startsAt, endsAt = event.endsAt, timezone = event.timezone,
        locationCity = "Sydney", locationAddress = "1 Example St, Sydney NSW", locationCountry = "AU", latitude = -33.8688, longitude = 151.2093,
    )
    val ticket = Ticket(
        id = "3b1f9a2c-8d7e-4f60-9a1b-2c3d4e5f6a7b", participantId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", status = "complete",
        displayStatus = "Complete", confirmed = true, checkedIn = false, attendeeName = "Sam",
        qrPayload = "attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", shortCode = "A1B2C3D4",
        onboardingUrl = "https://attend.hackclub.com/onboarding", event = ticketEvent,
        travelInbound = TicketTravel(direction = "inbound", mode = "plane", carrier = "Qantas", flightNumber = "QF401", departureCity = "Melbourne", arrivalCity = "Sydney",
            departureTime = "2026-10-02T20:00:00Z", arrivalTime = "2026-10-02T21:25:00Z", legs = listOf(TicketTravelLeg("QF401", "MEL", "SYD", "2026-10-02T20:00:00Z", "2026-10-02T21:25:00Z"))),
        messages = listOf(TicketMessage(id = "m1", subject = "Welcome to Campfire!", body = "<p>Doors open at 9am. Bring your laptop charger!</p>", senderName = "Orpheus", deliveredAt = "2026-09-30T02:00:00Z")),
    )
    val tickets = listOf(
        ticket,
        ticket.copy(id = "t2", confirmed = false, status = "in_progress", displayStatus = "Awaiting Parent",
            event = ticketEvent.copy(id = "e2", name = "Scrapyard Melbourne", startsAt = "2026-11-14T22:00:00Z", locationCity = "Melbourne")),
    )

    val travel = TravelCalendar(
        eventTimezone = "Australia/Sydney",
        dates = listOf("2026-10-03", "2026-10-05"),
        entries = participants.take(6).mapIndexed { i, p ->
            TravelEntry(
                id = "tr$i", participantId = p.participantId, participantEventId = p.participantEventId,
                participantName = p.fullName, participantPreferredName = p.displayName,
                direction = if (i < 4) "inbound" else "outbound", mode = if (i == 3) "train" else "plane",
                primaryTimeAt = iso(45L * i), agendaDate = if (i < 4) "2026-10-03" else "2026-10-05",
                route = if (i == 3) "Central → Venue" else "MEL → SYD", reference = "QF40$i",
                pickupState = when (i) { 0 -> "collected"; 1 -> "checked_in"; 2, 3 -> "awaiting_pickup"; else -> null },
                isUnaccompaniedMinor = i == 2, groups = if (i == 1) listOf(Group("g1", "Team Blue", "#3b82f6")) else emptyList(),
            )
        },
        counts = TravelCounts(total = 6, inbound = 4, outbound = 2, scheduled = 6, awaitingPickup = 2, collected = 1, checkedIn = 1),
    )

    val blasts = listOf(
        SlackBlast(id = "b1", message = "Lunch is ready in the atrium! 🌮", status = "completed", recipientCount = 120, sentCount = 118, failedCount = 2, createdAt = iso(-40), sentBy = "Orpheus Dino"),
        SlackBlast(id = "b2", message = "Buses leave for the hotel at 9pm sharp.", status = "in_progress", recipientCount = 120, sentCount = 64, createdAt = iso(-2), sentBy = "Heidi"),
    )
}
