package au.ingo.betterattend.ui.dashboard

import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.util.Time
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** One "Saturday lunch 61 / 120" row. */
data class ContextProgress(val context: ScanContext, val count: Int, val total: Int, val active: Boolean) {
    val fraction: Float get() = if (total <= 0) 0f else (count.toFloat() / total).coerceIn(0f, 1f)
}

/** Arrivals card numbers from the server-computed travel counts, plus who's landing next. */
data class ArrivalsSummary(
    val awaitingPickup: Int,
    val collected: Int,
    val checkedIn: Int,
    val next: List<TravelEntry>,
)

/** What a role without participant access sees instead of roster stats. */
data class ScanFeedSummary(
    /** Scans made today (event timezone) among the latest feed page. */
    val today: Int,
    /** True when every scan in the (capped) feed is from today, so the real number may be higher. */
    val capped: Boolean,
    val uniquePeopleToday: Int,
    val recent: List<Scan>,
)

object DashboardLogic {
    /** The API returns at most this many scans without `since`. */
    const val SCAN_FEED_CAP = 100

    /** "Sat 3 – Sun 4 Oct · Canberra" */
    fun subtitle(event: Event): String? =
        listOfNotNull(Time.range(event.startsAt, event.endsAt, event.timezone), event.locationCity?.takeIf { it.isNotBlank() })
            .joinToString(" · ").ifEmpty { null }

    /** Checked in among expected participants, so "84 / 120" and "36 not here yet" always add up. */
    fun checkedInConfirmed(stats: EventStats): Int = (stats.expected - stats.notArrived).coerceAtLeast(0)

    /** Registrations still onboarding (invited / in progress / awaiting guardian). */
    fun notComplete(stats: EventStats): Int = (stats.registered - stats.confirmed).coerceAtLeast(0)

    /** Unique participants with a safety flag worth knowing about before they arrive. */
    fun needsAttention(participants: List<Participant>): Int =
        participants.count { it.isActive && (it.hasAnaphylaxisRisk || it.highSupportFlag) }

    /** Progress per scan context in the organizer's order; the context whose window contains now is marked active. */
    fun contextProgress(contexts: List<ScanContext>, stats: EventStats, now: Instant): List<ContextProgress> =
        contexts.sortedBy { it.position }.map { c ->
            val s = Time.parse(c.startsAt)
            val e = Time.parse(c.endsAt)
            val active = s != null && e != null && !now.isBefore(s) && !now.isAfter(e)
            ContextProgress(c, stats.perContext[c.id] ?: 0, stats.expected, active)
        }

    /** Most recent check-ins first. */
    fun recentCheckIns(participants: List<Participant>, limit: Int = 5): List<Participant> =
        participants.asSequence()
            .filter { it.isActive && it.checkedInAt != null }
            .mapNotNull { p -> Time.parse(p.checkedInAt)?.let { p to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()

    /**
     * Next inbound journeys still needing a pickup, soonest first. Arrivals up to 3 h late still count
     * (they may be waiting at the airport); unscheduled ones are left out.
     */
    fun arrivals(calendar: TravelCalendar?, now: Instant, limit: Int = 3): ArrivalsSummary? {
        calendar ?: return null
        val cutoff = now.minus(Duration.ofHours(3))
        val next = calendar.entries.asSequence()
            .filter { it.direction == "inbound" && it.pickupState == "awaiting_pickup" }
            .mapNotNull { e -> Time.parse(e.primaryTimeAt)?.let { e to it } }
            .filter { it.second.isAfter(cutoff) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
        val c = calendar.counts
        return ArrivalsSummary(c.awaitingPickup, c.collected, c.checkedIn, next)
    }

    fun scanFeed(scans: List<Scan>, tz: String?, now: Instant, recentLimit: Int = 6): ScanFeedSummary {
        val zone = Time.zone(tz)
        val today = now.atZone(zone).toLocalDate()
        val sorted = scans.mapNotNull { s -> Time.parse(s.scannedAt)?.let { s to it } }.sortedByDescending { it.second }
        val todays = sorted.filter { it.second.atZone(zone).toLocalDate() == today }
        return ScanFeedSummary(
            today = todays.size,
            capped = scans.size >= SCAN_FEED_CAP && todays.size == sorted.size,
            uniquePeopleToday = todays.mapNotNull { it.first.participantEventId ?: it.first.participantId }.toSet().size,
            recent = sorted.take(recentLimit).map { it.first },
        )
    }

    /** "Starts in 20 min", "Starts in 5 h", "Starts tomorrow", "Starts in 3 days" (calendar days in the event's zone). */
    fun countdown(startsAt: String?, tz: String?, now: Instant): String? {
        val start = Time.parse(startsAt) ?: return null
        if (!start.isAfter(now)) return null
        val zone = Time.zone(tz)
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), start.atZone(zone).toLocalDate())
        val d = Duration.between(now, start)
        return when {
            days == 0L && d.toMinutes() < 60 -> "Starts in ${d.toMinutes().coerceAtLeast(1)} min"
            days == 0L -> "Starts in ${d.toHours()} h"
            days == 1L -> "Starts tomorrow"
            else -> "Starts in $days days"
        }
    }

    /** "Ended today", "Ended yesterday", "Ended 4 days ago". */
    fun ended(endsAt: String?, tz: String?, now: Instant): String? {
        val end = Time.parse(endsAt) ?: return null
        if (end.isAfter(now)) return null
        val zone = Time.zone(tz)
        val days = ChronoUnit.DAYS.between(end.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
        return when (days) {
            0L -> "Ended today"
            1L -> "Ended yesterday"
            else -> "Ended $days days ago"
        }
    }

    /** "Updated just now" / "Updated 5 min ago". */
    fun updated(at: Instant?, now: Instant): String? = at?.let { "Updated ${Time.ago(it.toString(), now)}" }

    fun roleLabel(role: String?): String? = when (role) {
        "global_admin" -> "Global admin"
        "series_member" -> "Series member"
        "event_admin" -> "Event admin"
        "safeguarding_lead" -> "Safeguarding lead"
        "ops" -> "Ops"
        "limited" -> "Limited"
        "read_only" -> "Read only"
        null -> null
        else -> role.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
