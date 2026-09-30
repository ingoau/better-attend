package au.ingo.betterattend.ui.preview

import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.data.model.Group
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.ScanContextRef
import au.ingo.betterattend.data.model.SlackBlast
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.model.TravelCounts
import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.data.repo.Roster
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Larger, event-sized fake data for the organizer Home / Travel / Announcements screens
 * (previews and screenshot tests only). "Now" is Saturday 3 Oct 2026, 11:30 in Sydney.
 */
object OrganizerSamples {
    val now: Instant = Instant.parse("2026-10-03T01:30:00Z")
    private fun iso(minutesFromNow: Long) = now.plus(minutesFromNow, ChronoUnit.MINUTES).toString()

    private val first = listOf(
        "Sam", "Arjun", "Maya", "Leo", "Priya", "Noah", "Zara", "Kai", "Ella", "Oliver", "Isla", "Jack", "Mia", "Ethan",
        "Aisha", "Lucas", "Chloe", "Ravi", "Grace", "Hugo", "Nina", "Theo", "Ivy", "Omar",
    )
    private val last = listOf("Lee", "Patel", "Chen", "Nguyen", "Sharma", "Williams", "Ahmed", "Tanaka", "Brown", "Smith", "Wilson", "Taylor")

    /**
     * 142 registrations: 120 complete (84 checked in, 12 of them in the last hour), 12 still onboarding,
     * 6 withdrawn, 4 rejected. 61 have had Saturday lunch; 22 were collected at the airport.
     */
    val participants: List<Participant> = (0 until 142).map { i ->
        val fn = first[i % first.size]
        val ln = last[(i / first.size + i) % last.size]
        val status = when {
            i < 120 -> "complete"
            i < 124 -> "invited"
            i < 129 -> "in_progress"
            i < 132 -> "awaiting_guardian"
            i < 138 -> "withdrawn"
            else -> "rejected"
        }
        val checkedIn = i < 84
        // The first 12 arrived in the last hour, most recent first.
        val checkedInAt = if (checkedIn) iso(if (i < 12) -(i * 4L + 2) else -(70L + i * 3)) else null
        val scans = buildList {
            if (checkedIn) add(ContextScanSummary("c1", "Check-in desk", checksIn = true, scanCount = 1, firstScannedAt = checkedInAt, lastScannedAt = checkedInAt))
            if (i in 40 until 62) add(ContextScanSummary("c2", "Airport pickup", isTravelPickup = true, scanCount = 1, firstScannedAt = iso(-200), lastScannedAt = iso(-200)))
            if (checkedIn && i % 4 != 3) add(ContextScanSummary("c3", "Saturday lunch", scanCount = 1, firstScannedAt = iso(-5), lastScannedAt = iso(-5)))
        }
        Participant(
            participantId = "%08x-e5f6-4a7b-8c9d-0e1f2a3b4c5d".format(0x5a1b2000 + i * 7919),
            participantEventId = "%08x-8d7e-4f60-9a1b-2c3d4e5f6a7b".format(0x3b1f9000 + i * 104729),
            displayName = fn, fullName = "$fn $ln", email = "${fn.lowercase()}.${ln.lowercase()}$i@example.com",
            status = status, checkedInAt = checkedInAt,
            hasAnaphylaxisRisk = i == 3 || i == 57 || i == 101,
            highSupportFlag = i == 8 || i == 90,
            requiresRefrigeration = i == 3,
            slackUserId = if (i % 30 == 29) null else "U%05d".format(i),
            scansByContext = scans,
        )
    }

    val roster = Roster(
        eventId = SampleData.event.id, participants = participants,
        syncedAt = iso(0), lastFullSyncAt = iso(-60), lastSyncAt = iso(0),
    )

    private val blue = Group("g1", "Team Blue", "#3b82f6")
    private val green = Group("g2", "Mentors", "#22c55e")

    val travel = TravelCalendar(
        eventTimezone = "Australia/Sydney",
        dates = listOf("2026-10-03", "2026-10-04", "2026-10-05"),
        entries = listOf(
            travelEntry(0, "inbound", "plane", -95, "2026-10-03", "MEL → SYD", "QF401", "collected", groups = listOf(blue)),
            travelEntry(1, "inbound", "plane", -60, "2026-10-03", "BNE → SYD", "VA914", "checked_in"),
            travelEntry(2, "inbound", "plane", 20, "2026-10-03", "AKL → SYD", "NZ103", "awaiting_pickup", um = true, groups = listOf(blue, green)),
            travelEntry(3, "inbound", "train", 45, "2026-10-03", "Central → Venue", "NSW TrainLink", "awaiting_pickup"),
            travelEntry(4, "inbound", "plane", 75, "2026-10-03", "PER → SYD", "QF566 · QF412", "awaiting_pickup"),
            travelEntry(5, "inbound", "car", 120, "2026-10-03", "Parent drop-off", null, "pickup_not_needed"),
            travelEntry(6, "inbound", "bus", 150, "2026-10-03", "Canberra → Sydney", "Murrays", "awaiting_pickup"),
            travelEntry(7, "outbound", "plane", 60 * 24 + 360, "2026-10-04", "SYD → MEL", "JQ508", null),
            travelEntry(8, "outbound", "plane", 60 * 48 - 60, "2026-10-05", "SYD → AKL", "NZ104", null, um = true),
            travelEntry(9, "outbound", "train", 60 * 48 + 30, "2026-10-05", "Venue → Central", null, null),
            travelEntry(10, "inbound", "plane", null, null, "Details pending", null, "awaiting_pickup"),
        ),
        counts = TravelCounts(total = 11, inbound = 8, outbound = 3, scheduled = 10, unscheduled = 1, awaitingPickup = 5, collected = 1, checkedIn = 1, pickupNotNeeded = 1),
    )

    private fun travelEntry(
        i: Int, direction: String, mode: String, minutes: Long?, date: String?, route: String, ref: String?, pickup: String?,
        um: Boolean = false, groups: List<Group> = emptyList(),
    ): TravelEntry {
        val p = participants[i * 5 + 40]
        return TravelEntry(
            id = "tr$i", participantId = p.participantId, participantEventId = p.participantEventId,
            participantName = p.fullName, participantPreferredName = p.displayName, direction = direction, mode = mode,
            primaryTimeAt = minutes?.let { iso(it) }, agendaDate = date, route = route, reference = ref,
            pickupState = pickup, isUnaccompaniedMinor = um, groups = groups,
        )
    }

    /** Latest scans feed (what a read-only role sees instead of the roster). */
    val scans: List<Scan> = (0 until 40).map { i ->
        val ctx = when {
            i % 5 == 0 -> ScanContextRef("c2", "Airport pickup", isTravelPickup = true)
            i % 3 == 0 -> ScanContextRef("c1", "Check-in desk", checksIn = true)
            else -> ScanContextRef("c3", "Saturday lunch")
        }
        // Every other scan is someone with travel, so the read-only feed can show their name.
        val p = participants[if (i % 2 == 0) (i / 2 % 11) * 5 + 40 else (i * 7) % 120]
        Scan(
            id = "s$i", participantId = p.participantId, participantEventId = p.participantEventId,
            scannedAt = iso(-(i * 6L + 1)), scannedBy = if (i % 2 == 0) "Heidi" else "Orpheus Dino", scanContext = ctx,
        )
    } + (0 until 20).map { i ->
        // Yesterday's scans shouldn't count towards "today".
        Scan(id = "y$i", participantEventId = participants[i].participantEventId, scannedAt = iso(-(60L * 24 + i)), scanContext = ScanContextRef("c1", "Check-in desk", checksIn = true))
    }

    val blasts = listOf(
        SlackBlast(id = "b3", message = "Buses to the hotel leave from the main entrance at <b>9pm sharp</b>.<br>Bring everything with you!", status = "in_progress", recipientCount = 116, sentCount = 71, failedCount = 0, createdAt = iso(-1), sentBy = "Heidi"),
        SlackBlast(id = "b1", message = "Lunch is ready in the atrium! 🌮 Vegetarian and allergen-free options are on the left table.", status = "completed", recipientCount = 120, sentCount = 118, failedCount = 2, createdAt = iso(-40), sentBy = "Orpheus Dino"),
        SlackBlast(id = "b0", message = "Welcome to Campfire Sydney! Check-in opens at 9am in the foyer. Please have your ticket QR ready.", status = "completed", recipientCount = 120, sentCount = 120, failedCount = 0, createdAt = iso(-60 * 20), sentBy = "Orpheus Dino"),
        SlackBlast(id = "bf", message = "Test message", status = "failed", recipientCount = 0, sentCount = 0, failedCount = 0, createdAt = iso(-60 * 26), sentBy = "Heidi"),
    )
}
