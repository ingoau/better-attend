package au.ingo.betterattend.ui.people

import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.util.Time
import java.text.Normalizer
import java.time.Instant

/** The chips above the list. Each is a preset that's cheap to count for every participant. */
enum class QuickFilter(val label: String) {
    All("All"),
    Here("Here"),
    NotHere("Not here"),
    NeedsAttention("Needs attention"),
    NotComplete("Not complete"),
    Withdrawn("Withdrawn");

    fun matches(p: Participant): Boolean = when (this) {
        All -> p.isActive
        Here -> p.isActive && p.isCheckedIn
        NotHere -> p.isActive && p.status == "complete" && !p.isCheckedIn
        NeedsAttention -> p.isActive && p.hasSafetyAlert
        NotComplete -> p.isActive && p.status != "complete"
        Withdrawn -> !p.isActive
    }
}

/** Three-way switch used by the filter sheet ("Any / Yes / No"). */
enum class TriState { Any, Yes, No;
    fun test(value: Boolean): Boolean = when (this) { Any -> true; Yes -> value; No -> !value }
}

enum class SortOrder(val label: String) { Name("Name"), RecentCheckIn("Arrival"), Status("Status") }

/** Advanced filters from the sheet. Everything defaults to "don't care". */
data class FilterOptions(
    /** Only people scanned at this context (or, with [notScannedAtContext], people who weren't). */
    val scannedAtContextId: String? = null,
    val notScannedAtContext: Boolean = false,
    /** Empty = any status. */
    val statuses: Set<String> = emptySet(),
    val inboundTravel: TriState = TriState.Any,
    val outboundTravel: TriState = TriState.Any,
    /** Empty = any mode; otherwise either direction uses one of these. */
    val travelModes: Set<String> = emptySet(),
    val waiverSigned: TriState = TriState.Any,
    val nfcAssigned: TriState = TriState.Any,
    /** Empty = any diet. Only honoured when the viewer can see sensitive data. */
    val dietTypes: Set<String> = emptySet(),
) {
    /** How many independent rules are on (for the "Filters · 3" badge). */
    val activeCount: Int get() = listOf(
        scannedAtContextId != null, statuses.isNotEmpty(), inboundTravel != TriState.Any, outboundTravel != TriState.Any,
        travelModes.isNotEmpty(), waiverSigned != TriState.Any, nfcAssigned != TriState.Any, dietTypes.isNotEmpty(),
    ).count { it }

    fun matches(p: Participant): Boolean {
        if (scannedAtContextId != null) {
            val scanned = p.scansByContext.any { it.scanContextId == scannedAtContextId && it.scanCount > 0 }
            if (scanned == notScannedAtContext) return false
        }
        if (statuses.isNotEmpty() && (p.status ?: "unknown") !in statuses) return false
        if (!inboundTravel.test(p.travelInbound != null)) return false
        if (!outboundTravel.test(p.travelOutbound != null)) return false
        if (travelModes.isNotEmpty()) {
            val modes = listOfNotNull(p.travelInbound?.mode, p.travelOutbound?.mode)
            if (modes.none { it in travelModes }) return false
        }
        if (!waiverSigned.test(p.waiverSigned)) return false
        if (!nfcAssigned.test(p.nfcBadgeAssigned)) return false
        if (dietTypes.isNotEmpty() && (p.dietType ?: "none") !in dietTypes) return false
        return true
    }
}

/** A row in the list: either an alphabetical header or a participant. */
sealed interface PeopleListItem {
    val key: String
    data class Header(val letter: String) : PeopleListItem { override val key get() = "h_$letter" }
    data class Person(val participant: Participant) : PeopleListItem { override val key get() = participant.participantEventId }
}

/**
 * For each item, its position in the run of rows between headers, as (index, run size), so rows
 * can be drawn as one segmented group per letter. Null for headers.
 */
fun segmentPositions(items: List<PeopleListItem>): List<Pair<Int, Int>?> {
    val out = arrayOfNulls<Pair<Int, Int>>(items.size)
    var start = 0
    while (start < items.size) {
        if (items[start] is PeopleListItem.Header) { start++; continue }
        var end = start
        while (end < items.size && items[end] is PeopleListItem.Person) end++
        for (i in start until end) out[i] = (i - start) to (end - start)
        start = end
    }
    return out.toList()
}

data class FilterResult(
    val participants: List<Participant>,
    /** Live counts for each chip, after search and advanced filters (but before the chip itself). */
    val counts: Map<QuickFilter, Int>,
)

object PeopleFilter {

    /** Lower-cases and strips accents so "zoe" finds "Zoë". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    /**
     * Every whitespace-separated term must appear in one of: display name, full name, email,
     * pronouns, or the short code / ids (so a ticket code typed at the desk works).
     */
    fun matchesQuery(p: Participant, query: String): Boolean {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = listOfNotNull(p.displayName, p.fullName, p.email, p.pronouns)
            .joinToString(" ") { normalize(it) } + " " + p.shortCode.lowercase() + " " + p.participantEventId.lowercase() + " " + p.participantId.lowercase()
        return terms.all { haystack.contains(it) }
    }

    fun apply(
        participants: List<Participant>,
        query: String,
        quick: QuickFilter,
        options: FilterOptions,
        sort: SortOrder,
        canViewSensitive: Boolean,
    ): FilterResult {
        val effective = if (canViewSensitive) options else options.copy(dietTypes = emptySet())
        val base = participants.filter { matchesQuery(it, query) && effective.matches(it) }
        val counts = QuickFilter.entries.associateWith { f -> base.count(f::matches) }
        return FilterResult(sorted(base.filter(quick::matches), sort), counts)
    }

    private val statusRank = mapOf(
        "complete" to 1, "awaiting_guardian" to 2, "in_progress" to 3, "invited" to 4, "withdrawn" to 5, "rejected" to 6,
    )

    fun sorted(list: List<Participant>, sort: SortOrder): List<Participant> {
        val byName = compareBy<Participant> { normalize(it.name) }.thenBy { normalize(it.fullName ?: "") }
        return when (sort) {
            SortOrder.Name -> list.sortedWith(byName)
            SortOrder.RecentCheckIn -> list.sortedWith(
                compareByDescending<Participant> { Time.parse(it.checkedInAt) ?: Instant.MIN }.then(byName)
            )
            SortOrder.Status -> list.sortedWith(
                compareBy<Participant> { if (it.isActive && it.isCheckedIn) 0 else statusRank[it.status] ?: 9 }.then(byName)
            )
        }
    }

    /** Letter used for sticky headers: A–Z, else "#". */
    fun sectionLetter(p: Participant): String {
        val c = normalize(p.name).firstOrNull()?.uppercaseChar() ?: return "#"
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    /** Adds alphabetical headers when sorted by name; otherwise a flat list. */
    fun withHeaders(list: List<Participant>, sort: SortOrder): List<PeopleListItem> {
        if (sort != SortOrder.Name) return list.map { PeopleListItem.Person(it) }
        val out = ArrayList<PeopleListItem>(list.size + 26)
        var last: String? = null
        list.forEach { p ->
            val letter = sectionLetter(p)
            if (letter != last) { out += PeopleListItem.Header(letter); last = letter }
            out += PeopleListItem.Person(p)
        }
        return out
    }

    /** The check-in scan (earliest in a checks_in context), for "Checked in 9:41 · Check-in desk". */
    fun checkInScan(p: Participant): ContextScanSummary? =
        p.scansByContext.filter { it.checksIn && it.firstScannedAt != null }
            .minByOrNull { Time.parse(it.firstScannedAt) ?: Instant.MAX }
}
