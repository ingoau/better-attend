package au.ingo.betterattend.scan

import au.ingo.betterattend.ui.preview.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterSearchAndToneTest {
    private val people = SampleData.participants

    @Test fun matchesNamePrefixes() {
        val r = RosterSearch.filter(people, "may")
        assertEquals("Maya", r.first().displayName)
    }

    @Test fun allTermsMustMatch() {
        assertEquals(listOf("Sam Lee"), RosterSearch.filter(people, "sam lee").map { it.fullName })
        assertTrue(RosterSearch.filter(people, "sam patel").isEmpty())
    }

    @Test fun matchesShortCodeAndEmail() {
        val shortCode = people[3].shortCode
        assertEquals(people[3], RosterSearch.filter(people, shortCode.lowercase()).single())
        assertEquals(people[4], RosterSearch.filter(people, "priya@exa").single())
    }

    @Test fun blankQueryIsEmpty() {
        assertTrue(RosterSearch.filter(people, "   ").isEmpty())
    }

    @Test fun inactiveSortLast() {
        val withdrawn = people[10] // Isla, withdrawn
        val r = RosterSearch.filter(people.map { if (it == people[0]) it.copy(displayName = "Isabel", fullName = "Isabel Lee") else it }, "is")
        assertEquals(withdrawn.participantEventId, r.last().participantEventId)
    }

    @Test fun directInputAndShortCodes() {
        assertNotNull(RosterSearch.directInput("attend://checkin/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"))
        assertEquals("manual", RosterSearch.directInput("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")!!.source)
        assertNull(RosterSearch.directInput("A1B2C3D4"))
        assertTrue(RosterSearch.looksLikeShortCode("A1B2C3D4"))
        assertFalse(RosterSearch.looksLikeShortCode("Sam Lee"))
    }

    @Test fun wavHeaderAndLength() {
        val samples = ToneSynth.render(ToneSynth.notesFor(FeedbackKind.Success))
        val wav = ToneSynth.wav(samples)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + samples.size * 2, wav.size)
        // ~0.27 s at 44.1 kHz
        assertEquals((90 + 10 + 170) * 44_100 / 1000, samples.size)
    }

    @Test fun tonesStartAndEndSilentAndAreDistinct() {
        FeedbackKind.entries.forEach { kind ->
            val s = ToneSynth.render(ToneSynth.notesFor(kind))
            assertTrue(kotlin.math.abs(s.first().toInt()) < 200)
            assertTrue(kotlin.math.abs(s.last().toInt()) < 2000)
            assertTrue(s.maxOf { it.toInt() } > 10_000)
        }
        val all = FeedbackKind.entries.map { ToneSynth.wavFor(it).toList() }.toSet()
        assertEquals(FeedbackKind.entries.size, all.size)
    }
}
