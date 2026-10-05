package au.ingo.betterattend.ui.rollcall

import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.RollCall
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.scan.RosterSearch
import au.ingo.betterattend.util.Time
import java.time.Duration
import java.time.Instant

/** The list filter on a running roll call. */
enum class RollCallFilter(val label: String) { Missing("Missing"), Accounted("Accounted"), All("All") }

/**
 * Where ticks are recorded. There's deliberately no "default scan point": recording at one is only
 * ever the user's explicit choice.
 */
sealed interface TickRecording {
    /** Nothing is sent; works offline. */
    data object PhoneOnly : TickRecording

    /** Each tick records a scan at this scan point (chosen by the user). */
    data class AtScanPoint(val contextId: String) : TickRecording
}

data class RollCallCounts(val accounted: Int, val total: Int) {
    val missing: Int get() = (total - accounted).coerceAtLeast(0)
    val progress: Float get() = if (total == 0) 0f else accounted.coerceAtMost(total) / total.toFloat()
}

/** One person on the roll call list. [participant] is null if they've since left the roster. */
data class RollCallRow(
    val id: String,
    val name: String,
    val participant: Participant?,
    val accounted: Boolean,
    /** Wasn't expected; added by hand. */
    val added: Boolean,
    /** Ticked at (ISO). */
    val tickedAt: String?,
    /** Unticked, but a scan at the roll call's scan point stays recorded for them. */
    val stillRecorded: Boolean,
    /** Ticked, but recording the tick as a scan failed or was refused. */
    val notRecorded: Boolean = false,
)

/** Pure roll call rules (kept out of Compose so they're unit-tested). */
object RollCallLogic {
    /** Who a new roll call would expect, sorted by name. */
    fun expected(participants: List<Participant>, mode: RollCallExpected): List<Participant> =
        participants.filter { it.isActive && (mode == RollCallExpected.Registered || it.isCheckedIn) }
            .sortedBy { it.name.lowercase() }

    /** Starts a roll call, freezing who's expected. [recording] must be the user's explicit choice. */
    fun start(
        eventId: String,
        participants: List<Participant>,
        mode: RollCallExpected,
        recording: TickRecording,
        contexts: List<ScanContext>,
        now: Instant = Instant.now(),
    ): RollCall {
        val people = expected(participants, mode)
        val ctx = (recording as? TickRecording.AtScanPoint)?.let { r -> contexts.firstOrNull { it.id == r.contextId } }
        require(recording == TickRecording.PhoneOnly || ctx != null) { "Unknown scan point" }
        return RollCall(
            eventId = eventId,
            startedAt = now.toString(),
            expected = mode,
            expectedIds = people.map { it.participantEventId },
            names = people.associate { it.participantEventId to (it.fullName?.takeIf { n -> n.isNotBlank() } ?: it.name) },
            scanContextId = ctx?.id,
            scanContextName = ctx?.name,
            scanContextChecksIn = ctx?.checksIn ?: false,
        )
    }

    fun counts(rc: RollCall): RollCallCounts {
        val all = rc.allIds
        return RollCallCounts(accounted = all.count { it in rc.accounted }, total = all.size)
    }

    fun filterCounts(rc: RollCall): Map<RollCallFilter, Int> {
        val c = counts(rc)
        return mapOf(RollCallFilter.Missing to c.missing, RollCallFilter.Accounted to c.accounted, RollCallFilter.All to c.total)
    }

    /** Everyone on the list, as rows, sorted by name. */
    fun allRows(rc: RollCall, roster: Roster?): List<RollCallRow> {
        val byId = roster?.byEventId.orEmpty()
        return rc.allIds.map { id ->
            val p = byId[id]
            RollCallRow(
                id = id,
                // Full names: a headcount has to tell the three Aishas apart.
                name = p?.fullName?.takeIf { it.isNotBlank() } ?: p?.name ?: rc.names[id] ?: "Unknown",
                participant = p,
                accounted = id in rc.accounted,
                added = !rc.isExpected(id),
                tickedAt = rc.accounted[id],
                stillRecorded = id in rc.stillRecorded && id !in rc.accounted,
                notRecorded = id in rc.notRecorded && id in rc.accounted,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** The rows a filter + search show. */
    fun rows(rc: RollCall, roster: Roster?, filter: RollCallFilter, query: String): List<RollCallRow> {
        val shown = allRows(rc, roster).filter {
            when (filter) {
                RollCallFilter.Missing -> !it.accounted
                RollCallFilter.Accounted -> it.accounted
                RollCallFilter.All -> true
            }
        }
        return search(shown, query)
    }

    private fun search(rows: List<RollCallRow>, query: String): List<RollCallRow> {
        if (query.isBlank()) return rows
        val known = rows.mapNotNull { it.participant }
        val matching = RosterSearch.filter(known, query, limit = Int.MAX_VALUE).map { it.participantEventId }.toSet()
        val terms = query.trim().lowercase().split(Regex("\\s+"))
        return rows.filter { r ->
            if (r.participant != null) r.id in matching else terms.all { t -> r.name.lowercase().contains(t) }
        }
    }

    /**
     * People who could be added: active, not already on the list. Matches [query] when given, else
     * everyone (by name). Present-but-not-expected people are usually not checked in yet.
     */
    fun addCandidates(rc: RollCall, roster: Roster?, query: String, limit: Int = 50): List<Participant> {
        val onList = rc.allIds.toSet()
        val pool = roster?.participants.orEmpty().filter { it.isActive && it.participantEventId !in onList }
        return if (query.isBlank()) pool.sortedBy { it.name.lowercase() }.take(limit)
        else RosterSearch.filter(pool, query, limit)
    }

    /** "12 min", "1 h 5 min". */
    fun duration(rc: RollCall, now: Instant = Instant.now()): String {
        val start = Time.parse(rc.startedAt) ?: return ""
        val end = Time.parse(rc.finishedAt) ?: now
        val minutes = Duration.between(start, end).toMinutes().coerceAtLeast(0)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** "Started 7:02 PM · Recording at Check-in desk" for the app bar. */
    fun subtitle(rc: RollCall, tz: String?): String = listOfNotNull(
        Time.time(rc.startedAt, tz)?.let { "Started $it" },
        rc.scanContextName?.let { "Recording at $it" } ?: "Only on this phone",
    ).joinToString(" · ")

    /** Plain-text list of who's missing, for sharing (Slack, SMS). */
    fun missingShareText(rc: RollCall, roster: Roster?, eventName: String, tz: String?, now: Instant = Instant.now()): String {
        val rows = allRows(rc, roster)
        val missing = rows.filter { !it.accounted }
        val counts = counts(rc)
        return buildString {
            append("Roll call: ").append(eventName).append('\n')
            val started = Time.time(rc.startedAt, tz)
            val at = Time.time(rc.finishedAt ?: now.toString(), tz)
            append(listOfNotNull(started?.let { "Started $it" }, at?.let { "as of $it" }).joinToString(", "))
            if (started != null || at != null) append('\n')
            append("${counts.accounted} / ${counts.total} accounted for · ${counts.missing} missing\n")
            if (missing.isEmpty()) {
                append("\nEveryone is accounted for.")
            } else {
                append("\nMissing:\n")
                missing.forEach { r ->
                    // Names only: the text leaves the app, so no contact details.
                    append("• ").append(r.participant?.fullName?.takeIf { it.isNotBlank() } ?: r.name).append('\n')
                }
            }
        }.trimEnd()
    }
}
