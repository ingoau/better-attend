package au.ingo.betterattend.ui.tickets

import au.ingo.betterattend.data.model.Ticket
import au.ingo.betterattend.data.model.TicketEvent
import au.ingo.betterattend.util.Time
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Pure ticket helpers shared by the Tickets screens and the "My ticket" widget. */
object TicketLogic {

    /** What the attendee needs to know about a registration at a glance. */
    sealed interface Status {
        data object Ready : Status
        data object CheckedIn : Status
        /** Registration isn't finished; [reason] explains who needs to act. */
        data class Incomplete(val label: String, val reason: String) : Status
        data class Closed(val label: String, val reason: String) : Status
    }

    fun status(t: Ticket): Status = when {
        t.checkedIn -> Status.CheckedIn
        t.confirmed -> Status.Ready
        t.displayStatus.equals("Withdrawn", true) || t.status == "withdrawn" ->
            Status.Closed("Withdrawn", "You've withdrawn from this event. Contact the organisers if that's a mistake.")
        t.displayStatus.equals("Rejected", true) || t.status == "rejected" ->
            Status.Closed("Not accepted", "This registration wasn't accepted. The organisers can tell you more.")
        t.displayStatus.equals("Awaiting Parent", true) || t.status == "awaiting_guardian" ->
            Status.Incomplete("Waiting on guardian", "A parent or guardian still needs to complete their part of the form.")
        else -> Status.Incomplete("Finish registration", "You still have a few steps left in your registration form.")
    }

    // ---------- Countdown ----------

    sealed interface Countdown {
        data class Upcoming(val remaining: Duration) : Countdown
        data object Live : Countdown
        data object Ended : Countdown
        data object Unknown : Countdown
    }

    fun countdown(event: TicketEvent, now: Instant = Instant.now()): Countdown {
        val start = Time.parse(event.startsAt) ?: return Countdown.Unknown
        val end = Time.parse(event.endsAt) ?: start.plus(Duration.ofDays(1))
        return when {
            now.isBefore(start) -> Countdown.Upcoming(Duration.between(now, start))
            now.isAfter(end) -> Countdown.Ended
            else -> Countdown.Live
        }
    }

    /** "2d 4h 10m" / "4h 10m 5s" / "10m 5s" — the ticking countdown on the ticket detail. */
    fun clock(d: Duration): String {
        val days = d.toDays()
        val hours = d.toHours() % 24
        val minutes = d.toMinutes() % 60
        val seconds = d.seconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            else -> "${minutes}m ${seconds}s"
        }
    }

    /**
     * Short relative label for chips and widgets: "Happening now", "Today · doors 9:00 AM",
     * "Tomorrow · 9:00 AM", "in 3 days", "in 5 weeks", "Ended". Calendar days are counted in the
     * event's own timezone so "tomorrow" means tomorrow at the venue.
     */
    fun relativeLabel(event: TicketEvent, now: Instant = Instant.now()): String =
        relativeLabel(event.startsAt, event.endsAt, event.timezone, now)

    fun relativeLabel(startIso: String?, endIso: String?, tz: String?, now: Instant = Instant.now()): String {
        val start = Time.parse(startIso) ?: return "Date to be announced"
        val end = Time.parse(endIso) ?: start.plus(Duration.ofDays(1))
        if (!now.isBefore(start)) return if (now.isAfter(end)) "Ended" else "Happening now"
        val zone = Time.zone(tz)
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), start.atZone(zone).toLocalDate())
        val time = Time.time(startIso, tz)
        return when {
            days <= 0L -> {
                val mins = Duration.between(now, start).toMinutes()
                if (mins < 60) "Starts in ${mins.coerceAtLeast(1)} min" else "Today · doors $time"
            }
            days == 1L -> "Tomorrow · $time"
            days < 14 -> "in $days days"
            days < 60 -> "in ${days / 7} weeks"
            else -> {
                val months = days / 30
                if (months <= 1) "in a month" else "in $months months"
            }
        }
    }

    /** Upcoming and live first (soonest first), then past events (most recent first). */
    fun sorted(tickets: List<Ticket>, now: Instant = Instant.now()): Pair<List<Ticket>, List<Ticket>> {
        val (past, current) = tickets.partition { countdown(it.event, now) == Countdown.Ended }
        return current.sortedBy { Time.parse(it.event.startsAt) ?: Instant.MAX } to
            past.sortedByDescending { Time.parse(it.event.startsAt) ?: Instant.MIN }
    }

    /**
     * The passes the detail screen can swipe between: confirmed tickets in the same order as the tickets list,
     * always including [openedId] (even if it isn't confirmed). Just [openedId] until the list is known or when
     * it isn't in the list.
     */
    fun pagerIds(tickets: List<Ticket>?, openedId: String, now: Instant = Instant.now()): List<String> {
        if (tickets == null || tickets.none { it.id == openedId }) return listOf(openedId)
        val (current, past) = sorted(tickets, now)
        return (current + past).filter { it.confirmed || it.id == openedId }.map { it.id }.distinct()
    }

    /** The ticket to feature (e.g. in the widget): live or next upcoming, confirmed ones preferred. */
    fun next(tickets: List<Ticket>, now: Instant = Instant.now()): Ticket? {
        val current = sorted(tickets, now).first.filter { status(it) !is Status.Closed }
        return current.firstOrNull { countdown(it.event, now) == Countdown.Live }
            ?: current.firstOrNull()
    }

    // ---------- Venue ----------

    fun venueLines(e: TicketEvent): Pair<String, String?> {
        val address = e.locationAddress?.takeIf { it.isNotBlank() }
        val cityCountry = listOfNotNull(e.locationCity?.takeIf { it.isNotBlank() }, e.locationCountry?.takeIf { it.isNotBlank() })
            .joinToString(", ").ifBlank { null }
        return when {
            address != null -> address to cityCountry
            cityCountry != null -> cityCountry to null
            else -> "Venue to be announced" to null
        }
    }

    fun hasVenue(e: TicketEvent): Boolean =
        (e.latitude != null && e.longitude != null) || !e.locationAddress.isNullOrBlank() || !e.locationCity.isNullOrBlank()

    /** A `geo:` URI any maps app can handle (Google Maps, Organic Maps, OsmAnd…). */
    fun geoUri(e: TicketEvent): String? {
        val label = enc(e.name)
        if (e.latitude != null && e.longitude != null) {
            return "geo:${e.latitude},${e.longitude}?q=${e.latitude},${e.longitude}($label)"
        }
        val q = listOfNotNull(e.locationAddress, e.locationCity, e.locationCountry).filter { it.isNotBlank() }.joinToString(", ")
        return if (q.isBlank()) null else "geo:0,0?q=${enc(q)}"
    }

    /** Web fallback when no app handles `geo:`. */
    fun webDirectionsUrl(e: TicketEvent): String? {
        val dest = if (e.latitude != null && e.longitude != null) "${e.latitude},${e.longitude}"
        else listOfNotNull(e.locationAddress, e.locationCity, e.locationCountry).filter { it.isNotBlank() }.joinToString(", ").ifBlank { return null }
        return "https://www.google.com/maps/dir/?api=1&destination=${enc(dest)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    const val INCIDENT_URL = "https://hack.club/incident"
    const val HOTLINE_TEL = "tel:+18556254225"
    const val HOTLINE_DISPLAY = "+1 (855) 625 4225"
}
