package au.ingo.betterattend.screenshots

import au.ingo.betterattend.ui.preview.SampleData
import au.ingo.betterattend.ui.tickets.FullScreenQr
import au.ingo.betterattend.ui.tickets.TicketDetailContent
import au.ingo.betterattend.ui.tickets.TicketsContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TicketsScreenshots : ScreenshotTest() {
    /** Three days before Campfire Sydney opens. */
    private val before = Instant.parse("2026-09-30T01:30:00Z")
    private val during = Instant.parse("2026-10-03T01:30:00Z")

    // SampleData's second ticket keeps the first event's end date; give it a sensible one.
    private val pending = SampleData.tickets[1].let { it.copy(event = it.event.copy(endsAt = "2026-11-15T08:00:00Z", timezone = "Australia/Melbourne")) }

    private val list = listOf(SampleData.tickets[0], pending) + SampleData.ticket.copy(
        id = "t3", checkedIn = true,
        event = SampleData.ticketEvent.copy(id = "e3", name = "Counterspell Brisbane", startsAt = "2026-06-01T22:00:00Z", endsAt = "2026-06-02T08:00:00Z",
            timezone = "Australia/Brisbane", locationCity = "Brisbane"),
    )

    @Test fun ticketsList() = snap("tickets_list") {
        TicketsContent(list, refreshing = false, error = null, user = SampleData.user, showAccount = true, now = before,
            onRefresh = {}, onOpen = {}, onAccount = {})
    }

    @Test fun ticketsOffline() = snap("tickets_offline") {
        TicketsContent(list, refreshing = false, error = "You're offline", user = SampleData.user, showAccount = false, now = during,
            onRefresh = {}, onOpen = {}, onAccount = {})
    }

    @Test fun ticketsEmpty() = snap("tickets_empty") {
        TicketsContent(emptyList(), refreshing = false, error = null, user = SampleData.user, showAccount = true, now = before,
            onRefresh = {}, onOpen = {}, onAccount = {})
    }

    @Test fun ticketDetail() = snap("ticket_detail") {
        TicketDetailContent(SampleData.ticket, loading = false, error = null, now = before)
    }

    @Config(qualifiers = "w411dp-h2200dp-xxhdpi")
    @Test fun ticketDetailFull() = snap("ticket_detail_full") {
        TicketDetailContent(SampleData.ticket, loading = false, error = null, now = before)
    }

    @Config(qualifiers = "w411dp-h1800dp-xxhdpi")
    @Test fun ticketDetailLive() = snap("ticket_detail_live") {
        TicketDetailContent(SampleData.ticket.copy(checkedIn = true), loading = false, error = null, now = during)
    }

    @Test fun ticketDetailUnconfirmed() = snap("ticket_detail_unconfirmed") {
        TicketDetailContent(pending.copy(travelInbound = null, messages = emptyList()), loading = false, error = null, now = before)
    }

    @Test fun ticketFullScreenQr() = snap("ticket_fullscreen_qr") { FullScreenQr(SampleData.ticket, onClose = {}) }
}
