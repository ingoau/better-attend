package au.ingo.betterattend.ui.firstaid

import au.ingo.betterattend.data.model.EmergencyContact
import au.ingo.betterattend.data.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FirstAidLogicTest {
    private fun person(id: String, name: String, checkedIn: Boolean = true) = Participant(
        participantId = "$id-pid", participantEventId = id, displayName = name, fullName = name, status = "complete",
        checkedInAt = if (checkedIn) "2026-10-02T23:00:00Z" else null,
    )

    private val ana = person("a", "Ana").copy(hasAnaphylaxisRisk = true)
    private val bob = person("b", "Bob").copy(requiresRefrigeration = true)
    private val cat = person("c", "Cat", checkedIn = false).copy(highSupportFlag = true)
    private val dan = person("d", "Dan").copy(crossContaminationRisk = true)
    private val eve = person("e", "Eve").copy(medicalConditions = "Asthma") // sensitive text only
    private val fay = person("f", "Fay").copy(lifeThreateningAllergies = "Bees") // sensitive, life-threatening
    private val gus = person("g", "Gus").copy(allergies = "   ") // blank text doesn't count
    private val hal = person("h", "Hal").copy(hasAnaphylaxisRisk = true, status = "withdrawn")
    private val ivy = person("i", "Ivy")
    private val all = listOf(ivy, hal, gus, fay, eve, dan, cat, bob, ana)

    @Test fun include_flagsAlways_textOnlyWithSensitiveAccess() {
        assertEquals(setOf("a", "b", "c", "d"), all.filter { FirstAidLogic.include(it, sensitive = false) }.map { it.participantEventId }.toSet())
        assertEquals(setOf("a", "b", "c", "d", "e", "f"), all.filter { FirstAidLogic.include(it, sensitive = true) }.map { it.participantEventId }.toSet())
    }

    @Test fun include_neverWithdrawn() {
        assertFalse(FirstAidLogic.include(hal, sensitive = true))
    }

    @Test fun sort_lifeThreateningFirst_thenRefrigeration_highSupport_others_thenName() {
        assertEquals(listOf("Ana", "Fay", "Bob", "Cat", "Dan", "Eve"), FirstAidLogic.people(all, sensitive = true).map { it.name })
        assertEquals(listOf("Ana", "Bob", "Cat", "Dan"), FirstAidLogic.people(all, sensitive = false).map { it.name })
    }

    @Test fun filter_hereNow_andSearch() {
        val people = FirstAidLogic.people(all, sensitive = true)
        assertFalse(FirstAidLogic.filter(people, FirstAidFilter.HereNow, "").any { it.name == "Cat" })
        assertEquals(listOf("Cat"), FirstAidLogic.filter(people, FirstAidFilter.Everyone, "cat").map { it.name })
        assertEquals(mapOf(FirstAidFilter.HereNow to 5, FirstAidFilter.Everyone to 6), FirstAidLogic.counts(people))
        assertEquals(FirstAidFilter.HereNow, FirstAidLogic.defaultFilter(people))
        assertEquals(FirstAidFilter.Everyone, FirstAidLogic.defaultFilter(listOf(cat)))
    }

    @Test fun details_hiddenWithoutSensitiveAccess() {
        val p = ana.copy(allergies = "Peanuts", medications = "EpiPen", dietType = "vegetarian")
        assertTrue(FirstAidLogic.details(p, sensitive = false).isEmpty())
        assertTrue(FirstAidLogic.contacts(p.copy(parentGuardianName = "Mum", parentGuardianPhone = "1"), sensitive = false).isEmpty())
        assertEquals(listOf("Allergies", "Medications", "Diet"), FirstAidLogic.details(p, sensitive = true).map { it.label })
        assertEquals("Vegetarian", FirstAidLogic.details(p, sensitive = true).last().value)
    }

    @Test fun details_skipTrivialDiet() {
        assertTrue(FirstAidLogic.details(ivy.copy(dietType = "omnivore"), sensitive = true).isEmpty())
    }

    @Test fun contacts_emergencyByPriority_thenGuardianUnlessDuplicate() {
        val p = ana.copy(
            emergencyContacts = listOf(
                EmergencyContact(name = "Second", phone = "+61 2", priority = 2),
                EmergencyContact(name = "First", relationship = "Aunt", phone = "+61 1", priority = 1),
            ),
            parentGuardianName = "Jo", parentGuardianPhone = "+61 9",
        )
        assertEquals(listOf("First", "Second", "Jo"), FirstAidLogic.contacts(p, sensitive = true).map { it.name })
        val dup = p.copy(parentGuardianName = "First", parentGuardianPhone = "+611")
        assertEquals(listOf("First", "Second"), FirstAidLogic.contacts(dup, sensitive = true).map { it.name })
    }

    @Test fun escape_coversHtmlSpecials() {
        assertEquals("&lt;b&gt;Tom &amp; &quot;Jo&quot; &#39;x&#39;&lt;/b&gt;", FirstAidLogic.escape("<b>Tom & \"Jo\" 'x'</b>"))
    }

    @Test fun html_hasTitleFooterAndEscapesUserData() {
        val evil = person("x", "<script>alert(1)</script>").copy(hasAnaphylaxisRisk = true, allergies = "Nuts & <em>eggs</em>")
        val html = FirstAidLogic.html("Hack & Tell", listOf(evil), sensitive = true, tz = "Australia/Canberra", filterLabel = "Everyone",
            now = Instant.parse("2026-10-03T01:30:00Z"))
        assertTrue(html.contains("<title>Hack &amp; Tell — First-aid sheet</title>"))
        assertTrue(html.contains("<h1>Hack &amp; Tell — First-aid sheet</h1>"))
        assertTrue(html.contains("Generated "))
        assertTrue(html.contains(FirstAidLogic.PRINT_FOOTER))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("Nuts &amp; &lt;em&gt;eggs&lt;/em&gt;"))
        assertTrue(html.contains("Anaphylaxis risk"))
    }

    @Test fun html_withoutSensitiveAccess_hasNoMedicalText() {
        val p = ana.copy(allergies = "Peanuts", parentGuardianName = "Jo", parentGuardianPhone = "+61 9")
        val html = FirstAidLogic.html("Camp", listOf(p), sensitive = false, tz = null, filterLabel = "Here now")
        assertFalse(html.contains("Peanuts"))
        assertFalse(html.contains("+61 9"))
        assertTrue(html.contains(FirstAidLogic.NO_SENSITIVE_NOTICE))
    }

    @Test fun html_empty() {
        assertTrue(FirstAidLogic.html("Camp", emptyList(), sensitive = true, tz = null, filterLabel = "Everyone").contains(FirstAidLogic.EMPTY))
    }
}
