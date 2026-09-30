package au.ingo.betterattend.ui.travel

import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.util.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The first row of travel filter chips. */
enum class TravelFilter(val label: String) {
    All("All"),
    Arrivals("Arrivals"),
    Departures("Departures"),
    AwaitingPickup("Awaiting pickup"),
    PickedUp("Picked up"),
    Minors("Unaccompanied minors"),
}

/** Transport modes in display order. Entries with a null/unknown mode count as [Other]. */
enum class TravelMode(val wire: String, val label: String) {
    Plane("plane", "Plane"),
    Train("train", "Train"),
    Bus("bus", "Bus"),
    Car("car", "Car"),
    Other("other", "Other");

    companion object {
        fun of(entry: TravelEntry): TravelMode = entries.firstOrNull { it.wire == entry.mode } ?: Other
    }
}

/** One sticky-header section of the travel list. [date] is null for "Unscheduled". */
data class TravelSection(
    val key: String,
    val date: LocalDate?,
    /** "Today", "Tomorrow", "Yesterday", "Saturday 3 October" or "Unscheduled". */
    val title: String,
    /** Full date shown next to a relative title ("Saturday 3 October"), else null. */
    val subtitle: String?,
    val entries: List<TravelEntry>,
)

object TravelLogic {
    private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

    fun isArrival(e: TravelEntry) = e.direction == "inbound"
    fun isDeparture(e: TravelEntry) = e.direction == "outbound"
    fun isPickedUp(e: TravelEntry) = e.pickupState == "collected" || e.pickupState == "checked_in"

    fun matches(e: TravelEntry, filter: TravelFilter): Boolean = when (filter) {
        TravelFilter.All -> true
        TravelFilter.Arrivals -> isArrival(e)
        TravelFilter.Departures -> isDeparture(e)
        TravelFilter.AwaitingPickup -> e.pickupState == "awaiting_pickup"
        TravelFilter.PickedUp -> isPickedUp(e)
        TravelFilter.Minors -> e.isUnaccompaniedMinor
    }

    /** Every whitespace-separated term must appear in the name, preferred name, route, reference or notes. */
    fun matchesQuery(e: TravelEntry, query: String): Boolean {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = listOfNotNull(e.participantName, e.participantPreferredName, e.route, e.reference, e.details)
            .joinToString(" \u0000 ").lowercase()
        return terms.all { haystack.contains(it) }
    }

    fun filter(entries: List<TravelEntry>, query: String, filter: TravelFilter, mode: TravelMode?): List<TravelEntry> =
        entries.filter { matchesQuery(it, query) && matches(it, filter) && (mode == null || TravelMode.of(it) == mode) }

    /** Chip counts for the status row, reflecting the current search and mode (so numbers match what you'd see). */
    fun filterCounts(entries: List<TravelEntry>, query: String, mode: TravelMode?): Map<TravelFilter, Int> {
        val base = entries.filter { matchesQuery(it, query) && (mode == null || TravelMode.of(it) == mode) }
        return TravelFilter.entries.associateWith { f -> base.count { matches(it, f) } }
    }

    /** Mode chip counts, reflecting the current search and status filter. Only modes that occur are returned. */
    fun modeCounts(entries: List<TravelEntry>, query: String, filter: TravelFilter): Map<TravelMode, Int> {
        val base = entries.filter { matchesQuery(it, query) && matches(it, filter) }
        return TravelMode.entries.associateWith { m -> base.count { TravelMode.of(it) == m } }.filterValues { it > 0 }
    }

    /** Distinct transport modes present at all (the mode row only shows when there's more than one). */
    fun modesPresent(entries: List<TravelEntry>): Set<TravelMode> = entries.map { TravelMode.of(it) }.toSet()

    /**
     * Groups entries by agenda date (ascending) with the unscheduled ones last. Within a day entries are
     * ordered by time, then name. Titles are relative to [today] in the event's timezone.
     */
    fun sections(entries: List<TravelEntry>, today: LocalDate): List<TravelSection> {
        val (dated, undated) = entries.partition { parseDate(it.agendaDate) != null }
        val byDate = dated.groupBy { parseDate(it.agendaDate)!! }.toSortedMap()
        val out = byDate.map { (date, list) ->
            val relative = relativeLabel(date, today)
            TravelSection(
                key = date.toString(),
                date = date,
                title = relative ?: date.format(longDate),
                subtitle = if (relative != null) date.format(longDate) else null,
                entries = list.sortedWith(entryOrder),
            )
        }
        return if (undated.isEmpty()) out else out + TravelSection("unscheduled", null, "Unscheduled", null, undated.sortedWith(entryOrder))
    }

    private val entryOrder = compareBy<TravelEntry>({ Time.parse(it.primaryTimeAt) ?: Instant.MAX }, { it.name.lowercase() })

    fun relativeLabel(date: LocalDate, today: LocalDate): String? = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> null
    }

    fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Today's date in the event's zone. */
    fun today(tz: String?, now: Instant = Instant.now()): LocalDate = now.atZone(Time.zone(tz)).toLocalDate()

    /** "Sydney · GMT+10" style label for the timezone chip. */
    fun zoneLabel(tz: String?, now: Instant = Instant.now()): String? {
        val id = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        val city = tz.substringAfterLast('/').replace('_', ' ')
        val offset = id.rules.getOffset(now)
        val gmt = if (offset.totalSeconds == 0) "GMT" else "GMT" + offset.id.removeSuffix(":00").replace(Regex("^([+-])0"), "$1")
        return "$city · $gmt"
    }

    /** True when the device's zone shows different wall-clock time than the event's zone right now. */
    fun deviceZoneDiffers(tz: String?, now: Instant = Instant.now(), device: ZoneId = ZoneId.systemDefault()): Boolean {
        val id = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return false
        return id.rules.getOffset(now) != device.rules.getOffset(now)
    }

    fun zoneLongName(tz: String?): String? = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?.getDisplayName(TextStyle.FULL, Locale.getDefault())
}
