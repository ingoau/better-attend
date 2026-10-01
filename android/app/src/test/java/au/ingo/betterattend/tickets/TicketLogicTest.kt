package au.ingo.betterattend.tickets

import au.ingo.betterattend.data.model.TicketEvent
import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.tickets.TicketLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TicketLogicTest {
    // Campfire Canberra: 2026-10-02T22:00Z (Sat 3 Oct 8:00 AM Canberra) → 2026-10-04T06:00Z
    private val event = SampleData.ticketEvent

    @Test fun clockFormats() {
        assertEquals("2d 4h 10m", TicketLogic.clock(Duration.ofDays(2).plusHours(4).plusMinutes(10).plusSeconds(5)))
        assertEquals("4h 10m 5s", TicketLogic.clock(Duration.ofHours(4).plusMinutes(10).plusSeconds(5)))
        assertEquals("10m 5s", TicketLogic.clock(Duration.ofMinutes(10).plusSeconds(5)))
    }

    @Test fun countdownPhases() {
        assertTrue(TicketLogic.countdown(event, Instant.parse("2026-10-01T00:00:00Z")) is TicketLogic.Countdown.Upcoming)
        assertEquals(TicketLogic.Countdown.Live, TicketLogic.countdown(event, Instant.parse("2026-10-03T01:00:00Z")))
        assertEquals(TicketLogic.Countdown.Ended, TicketLogic.countdown(event, Instant.parse("2026-10-05T00:00:00Z")))
        assertEquals(TicketLogic.Countdown.Unknown, TicketLogic.countdown(event.copy(startsAt = null), Instant.now()))
    }

    @Test fun relativeLabelUsesEventTimezoneDays() {
        // 30 Sep 11:30 Canberra → 3 Oct is 3 calendar days away.
        assertEquals("in 3 days", TicketLogic.relativeLabel(event, Instant.parse("2026-09-30T01:30:00Z")))
        // 2 Oct 10:00 Canberra → tomorrow.
        assertTrue(TicketLogic.relativeLabel(event, Instant.parse("2026-10-02T00:00:00Z")).startsWith("Tomorrow · "))
        // 3 Oct 00:30 Canberra (still 2 Oct in UTC) → today, doors 8:00.
        assertTrue(TicketLogic.relativeLabel(event, Instant.parse("2026-10-02T14:30:00Z")).startsWith("Today · doors "))
        assertEquals("Starts in 30 min", TicketLogic.relativeLabel(event, Instant.parse("2026-10-02T21:30:00Z")))
        assertEquals("Happening now", TicketLogic.relativeLabel(event, Instant.parse("2026-10-03T01:00:00Z")))
        assertEquals("Ended", TicketLogic.relativeLabel(event, Instant.parse("2026-10-06T00:00:00Z")))
        assertEquals("in 5 weeks", TicketLogic.relativeLabel(event, Instant.parse("2026-08-25T00:00:00Z")))
        assertEquals("in 4 months", TicketLogic.relativeLabel(event, Instant.parse("2026-06-01T00:00:00Z")))
        assertEquals("Date to be announced", TicketLogic.relativeLabel(event.copy(startsAt = null)))
    }

    @Test fun statusMapping() {
        val t = SampleData.ticket
        assertEquals(TicketLogic.Status.Ready, TicketLogic.status(t))
        assertEquals(TicketLogic.Status.CheckedIn, TicketLogic.status(t.copy(checkedIn = true)))
        val parent = TicketLogic.status(t.copy(confirmed = false, status = "awaiting_guardian", displayStatus = "Awaiting Parent"))
        assertTrue(parent is TicketLogic.Status.Incomplete && parent.label == "Waiting on guardian")
        assertTrue(TicketLogic.status(t.copy(confirmed = false, status = "in_progress", displayStatus = "Awaiting Participant")) is TicketLogic.Status.Incomplete)
        assertTrue(TicketLogic.status(t.copy(confirmed = false, status = "withdrawn", displayStatus = "Withdrawn")) is TicketLogic.Status.Closed)
    }

    @Test fun sortingAndNext() {
        val now = Instant.parse("2026-09-30T00:00:00Z")
        val past = SampleData.ticket.copy(id = "past", event = event.copy(startsAt = "2026-06-01T00:00:00Z", endsAt = "2026-06-02T00:00:00Z"))
        val later = SampleData.ticket.copy(id = "later", event = event.copy(startsAt = "2026-12-01T00:00:00Z", endsAt = "2026-12-02T00:00:00Z"))
        val soon = SampleData.ticket.copy(id = "soon")
        val withdrawn = SampleData.ticket.copy(id = "w", confirmed = false, status = "withdrawn", displayStatus = "Withdrawn",
            event = event.copy(startsAt = "2026-09-30T06:00:00Z"))
        val (current, pastList) = TicketLogic.sorted(listOf(past, later, soon), now)
        assertEquals(listOf("soon", "later"), current.map { it.id })
        assertEquals(listOf("past"), pastList.map { it.id })
        assertEquals("soon", TicketLogic.next(listOf(later, withdrawn, soon, past), now)?.id)
        assertNull(TicketLogic.next(listOf(past), now))
    }

    @Test fun pagerIdsFollowListOrderAndSkipUnconfirmed() {
        val now = Instant.parse("2026-09-30T00:00:00Z")
        val past = SampleData.ticket.copy(id = "past", event = event.copy(startsAt = "2026-06-01T00:00:00Z", endsAt = "2026-06-02T00:00:00Z"))
        val later = SampleData.ticket.copy(id = "later", event = event.copy(startsAt = "2026-12-01T00:00:00Z", endsAt = "2026-12-02T00:00:00Z"))
        val soon = SampleData.ticket.copy(id = "soon")
        val pending = SampleData.ticket.copy(id = "pending", confirmed = false, event = event.copy(startsAt = "2026-11-01T00:00:00Z", endsAt = "2026-11-02T00:00:00Z"))
        val all = listOf(past, pending, later, soon)
        assertEquals(listOf("soon", "later", "past"), TicketLogic.pagerIds(all, "later", now))
        // An unconfirmed ticket only appears when it's the one opened.
        assertEquals(listOf("soon", "pending", "later", "past"), TicketLogic.pagerIds(all, "pending", now))
        // Until the list is known (or if the ticket isn't in it), just the opened one.
        assertEquals(listOf("x"), TicketLogic.pagerIds(null, "x", now))
        assertEquals(listOf("x"), TicketLogic.pagerIds(all, "x", now))
        assertEquals(listOf("soon"), TicketLogic.pagerIds(listOf(soon), "soon", now))
    }

    @Test fun directions() {
        assertEquals("geo:-35.2785,149.13?q=-35.2785,149.13(Campfire%20Canberra)", TicketLogic.geoUri(event))
        val noCoords = TicketEvent(id = "x", name = "X", locationAddress = "1 Example St", locationCity = "Canberra")
        assertEquals("geo:0,0?q=1%20Example%20St%2C%20Canberra", TicketLogic.geoUri(noCoords))
        assertNull(TicketLogic.geoUri(TicketEvent(id = "y", name = "Y")))
        assertEquals("Venue to be announced" to null, TicketLogic.venueLines(TicketEvent(id = "y", name = "Y")))
        assertTrue(TicketLogic.webDirectionsUrl(noCoords)!!.startsWith("https://www.google.com/maps/dir/?api=1&destination="))
    }
}
