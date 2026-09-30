package au.ingo.betterattend.data

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.EventStats
import org.junit.Assert.assertEquals
import org.junit.Test

class EventStatsTest {
    private fun p(i: Int, status: String, checkedIn: Boolean = false) = Participant(
        participantId = "p$i", participantEventId = "pe$i", status = status,
        checkedInAt = if (checkedIn) "2026-10-03T01:00:00Z" else null,
    )

    @Test fun expectedIsConfirmedWhenAnyoneIsComplete() {
        val s = EventStats.from(listOf(p(1, "complete", true), p(2, "complete"), p(3, "in_progress"), p(4, "withdrawn")))
        assertEquals(2, s.expected)
        assertEquals(1, s.checkedIn)
        assertEquals(1, s.notArrived)
    }

    @Test fun statusColumnLagging_fallsBackToActiveRegistrations() {
        // Nobody marked "complete" (Attend's status column lags) must not read as 0 / 0.
        val s = EventStats.from(listOf(p(1, "in_progress", true), p(2, "invited"), p(3, "awaiting_guardian"), p(4, "rejected")))
        assertEquals(3, s.expected)
        assertEquals(1, s.checkedIn)
        assertEquals(2, s.notArrived)
    }

    @Test fun checkedInNeverExceedsExpected() {
        val s = EventStats.from(listOf(p(1, "complete", true), p(2, "in_progress", true), p(3, "in_progress", true)))
        assertEquals(3, s.expected)
        assertEquals(1f, s.progress)
    }
}
