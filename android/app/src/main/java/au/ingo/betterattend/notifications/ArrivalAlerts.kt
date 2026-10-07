package au.ingo.betterattend.notifications

import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.util.Time
import java.time.Duration
import java.time.Instant

/** Everyone arriving at one time, and when to remind about picking them up. */
data class ArrivalSlot(val arrivesAt: Instant, val remindAt: Instant, val entries: List<TravelEntry>) {
    /** Stable for the same people at the same time; changes when either does. */
    val key: String get() = arrivesAt.toString() + ":" + entries.map { it.id }.sorted().joinToString(",")
}

/** An arrival awaiting pickup whose time moved since its reminder was scheduled. */
data class ArrivalChange(val entry: TravelEntry, val was: Instant, val now: Instant)

/** Pickup reminders built from the travel calendar, and how they're worded. */
object ArrivalAlerts {
    val LEAD: Duration = Duration.ofMinutes(30)
    /** Smaller shifts than this are noise (rounding, a gate change), not worth a notification. */
    val CHANGE_THRESHOLD: Duration = Duration.ofMinutes(15)
    /** Only the next few are scheduled; each travel refresh schedules the next ones. */
    const val MAX_SLOTS = 20

    /** Arrivals still to come that someone has to collect. */
    fun awaitingPickup(calendar: TravelCalendar, now: Instant): Map<TravelEntry, Instant> =
        calendar.entries
            .filter { it.direction == "inbound" && it.pickupState == "awaiting_pickup" }
            .mapNotNull { e -> Time.parse(e.primaryTimeAt)?.takeIf { it.isAfter(now) }?.let { e to it } }
            .toMap()

    /**
     * One reminder per arrival time, [LEAD] before it (or now, if that's already passed), soonest
     * first. People already reminded about at that same time ([reminded], see [remindedKey]) are left out.
     */
    fun slots(calendar: TravelCalendar, now: Instant, reminded: Set<String> = emptySet()): List<ArrivalSlot> =
        awaitingPickup(calendar, now)
            .filter { (e, at) -> remindedKey(e.id, at) !in reminded }
            .entries.groupBy({ it.value }, { it.key })
            .map { (at, entries) ->
                val remindAt = at.minus(LEAD).let { if (it.isBefore(now)) now else it }
                ArrivalSlot(at, remindAt, entries.sortedBy { it.name.lowercase() })
            }
            .sortedBy { it.arrivesAt }
            .take(MAX_SLOTS)

    fun remindedKey(entryId: String, at: Instant) = "$entryId@$at"

    /** Arrivals scheduled before ([previous]: entry id → time) that moved by at least [CHANGE_THRESHOLD]. */
    fun changes(previous: Map<String, String>, calendar: TravelCalendar, now: Instant): List<ArrivalChange> =
        awaitingPickup(calendar, now).mapNotNull { (e, at) ->
            val was = Time.parse(previous[e.id]) ?: return@mapNotNull null
            if (Duration.between(was, at).abs() < CHANGE_THRESHOLD) null else ArrivalChange(e, was, at)
        }.sortedBy { it.now }

    /** "Pickup at 10:45: Mia Chen" / "3 arrivals to collect at 10:45" */
    fun reminderTitle(slot: ArrivalSlot, tz: String?): String {
        val time = Time.time(slot.arrivesAt.toString(), tz).orEmpty()
        return if (slot.entries.size == 1) "Pickup at $time: ${slot.entries.single().name}"
        else "${slot.entries.size} arrivals to collect at $time"
    }

    /** "QF1471 · SYD → CBR" for one person; their names when there are several. */
    fun reminderText(slot: ArrivalSlot): String =
        if (slot.entries.size == 1) slot.entries.single().let { e -> listOfNotNull(e.reference, e.route).joinToString(" · ") }
        else RosterAlerts.summary(slot.entries.map { it.name })

    /** "Mia Chen now arrives at 11:30 (45 min later)" */
    fun changeLine(change: ArrivalChange, tz: String?): String {
        val minutes = Duration.between(change.was, change.now).toMinutes()
        val shift = when {
            minutes >= 120 -> "${minutes / 60} h later"
            minutes > 0 -> "$minutes min later"
            minutes <= -120 -> "${-minutes / 60} h earlier"
            else -> "${-minutes} min earlier"
        }
        return "${change.entry.name} now arrives at ${Time.time(change.now.toString(), tz)} ($shift)"
    }

    fun changeTitle(count: Int): String = if (count == 1) "Arrival time changed" else "$count arrival times changed"
}
