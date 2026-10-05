package au.ingo.betterattend.ui.preview

import au.ingo.betterattend.data.model.EmergencyContact
import au.ingo.betterattend.data.repo.RollCall
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.data.repo.Roster
import java.time.temporal.ChronoUnit

/**
 * Fake data for the roll call and first-aid screens (previews and screenshot tests only).
 * "Now" is [OrganizerSamples.now], Saturday 3 Oct 2026, 11:30 in Canberra.
 */
object SafetySamples {
    private val now = OrganizerSamples.now
    private fun iso(minutesFromNow: Long) = now.plus(minutesFromNow, ChronoUnit.MINUTES).toString()

    /** The small sample roster with medical details and contacts filled in for a few people. */
    val firstAidRoster: Roster = run {
        val people = SampleData.participants.mapIndexed { i, p ->
            when (i) {
                2 -> p.copy( // Maya: anaphylaxis + EpiPen
                    lifeThreateningAllergies = "Peanuts and tree nuts",
                    allergies = "Peanuts (anaphylaxis), latex",
                    medications = "EpiPen ×2 (blue bag; first aid has a spare)",
                    emergencyContacts = listOf(EmergencyContact(name = "Jordan Chen", relationship = "Parent", phone = "+61 411 111 111", priority = 1)),
                    parentGuardianName = "Jordan Chen", parentGuardianPhone = "+61411111111", parentGuardianEmail = "jordan@example.com",
                )
                4 -> p.copy( // Priya: asthma (sensitive text only)
                    medicalConditions = "Asthma",
                    medications = "Ventolin inhaler (carries her own)",
                    parentGuardianName = "Ravi Sharma", parentGuardianPhone = "+61 422 222 222",
                )
                5 -> p.copy( // Noah: high support
                    medicalConditions = "Autistic; may need a quiet space if overwhelmed",
                    emergencyContacts = listOf(EmergencyContact(name = "Alex Williams", relationship = "Mum", phone = "+61 433 333 333", priority = 1)),
                )
                7 -> p.copy( // Kai: coeliac
                    crossContaminationRisk = true,
                    allergies = "Gluten (coeliac disease)",
                    dietType = "gluten_free",
                )
                9 -> p.copy( // Oliver: insulin, not here yet
                    requiresRefrigeration = true,
                    medicalConditions = "Type 1 diabetes",
                    medications = "Insulin pens, kept in the kitchen fridge",
                    emergencyContacts = listOf(
                        EmergencyContact(name = "Sarah Smith", relationship = "Mum", phone = "+61 444 444 444", priority = 1),
                        EmergencyContact(name = "Dr Lee (GP)", relationship = "Doctor", phone = "+61 2 6200 0000", priority = 2),
                    ),
                )
                else -> p
            }
        }
        Roster(SampleData.event.id, people, syncedAt = iso(-3), lastFullSyncAt = iso(-60), lastSyncAt = iso(-3))
    }

    /** Checked-in people still missing on the sample roll call (includes the anaphylaxis and high-support people). */
    private val missingIndexes = setOf(3, 8, 15, 22, 29, 36, 47, 50, 61, 64, 70, 77, 81, 83, 5)

    /**
     * A roll call of the 84 people checked in on [OrganizerSamples.roster], recording at Saturday lunch:
     * 69 ticked (one not recorded at the scan point) plus one walk-in, 15 missing (one still scanned there).
     */
    val rollCall: RollCall = run {
        val people = OrganizerSamples.participants
        val expected = people.filter { it.isActive && it.isCheckedIn }.sortedBy { it.name.lowercase() }
        val walkIn = people[121] // invited, not checked in
        val ticked = people.withIndex().filter { (i, p) -> p in expected && i !in missingIndexes }
            .mapIndexed { n, (_, p) -> p.participantEventId to iso(-12L + n / 6) }.toMap()
        RollCall(
            eventId = SampleData.event.id,
            startedAt = iso(-14),
            expected = RollCallExpected.CheckedIn,
            expectedIds = expected.map { it.participantEventId },
            names = (expected + walkIn).associate { it.participantEventId to it.name },
            scanContextId = "c3", scanContextName = "Saturday lunch",
            accounted = ticked + (walkIn.participantEventId to iso(-2)),
            added = listOf(walkIn.participantEventId),
            stillRecorded = setOf(people[22].participantEventId),
            // One tick Attend couldn't record (e.g. a network error that wasn't queued).
            notRecorded = setOf(expected.filter { it.participantEventId in ticked }.sortedBy { (it.fullName ?: it.name).lowercase() }[1].participantEventId),
        )
    }

    val finishedRollCall: RollCall = rollCall.copy(finishedAt = iso(0))
}
