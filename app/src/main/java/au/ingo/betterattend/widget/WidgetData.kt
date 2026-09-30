package au.ingo.betterattend.widget

import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.Ticket
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.ui.tickets.TicketLogic
import au.ingo.betterattend.util.Time
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Everything the home-screen widgets show, precomputed from the local caches so rendering never
 * touches the network. Persisted (encrypted) through JsonCache; a missing snapshot means "signed out".
 */
@Serializable
data class WidgetSnapshot(
    val signedIn: Boolean = false,
    /** The organizer's selected event, when the user is an organizer. */
    val organizer: OrganizerWidgetData? = null,
    /** The participant's next ticket, when they have one. */
    val ticket: TicketWidgetData? = null,
    /** Whether the user has the participant role at all (drives the ticket widget's empty copy). */
    val isParticipant: Boolean = false,
    val builtAt: String? = null,
) {
    /** Equality without the build timestamp, to skip redundant widget updates. */
    fun sameContentAs(other: WidgetSnapshot?): Boolean = other != null && copy(builtAt = null) == other.copy(builtAt = null)

    companion object {
        val SignedOut = WidgetSnapshot(signedIn = false)
    }
}

@Serializable
data class OrganizerWidgetData(
    val eventId: String,
    val eventName: String,
    val timezone: String? = null,
    /** False for roles that can't read the roster (read_only), or before the first sync. */
    val hasCounts: Boolean = false,
    val checkedIn: Int = 0,
    val expected: Int = 0,
    val notArrived: Int = 0,
    val lastHour: Int = 0,
    val contexts: List<ContextCount> = emptyList(),
    /** When the roster was last synced (ISO). */
    val updatedAt: String? = null,
    val travelEnabled: Boolean = false,
    val travel: TravelWidgetData? = null,
) {
    val progress: Float get() = if (expected == 0) 0f else (checkedIn.coerceAtMost(expected) / expected.toFloat())
}

@Serializable
data class ContextCount(
    val id: String,
    val name: String,
    val count: Int,
    val checksIn: Boolean = false,
    val isTravel: Boolean = false,
)

@Serializable
data class TravelWidgetData(
    val awaitingPickup: Int = 0,
    val collected: Int = 0,
    val checkedIn: Int = 0,
    val total: Int = 0,
    val nextArrivalName: String? = null,
    val nextArrivalAt: String? = null,
    val nextArrivalRoute: String? = null,
    val nextArrivalReference: String? = null,
    val nextArrivalMinor: Boolean = false,
)

@Serializable
data class TicketWidgetData(
    val id: String,
    val eventName: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val timezone: String? = null,
    val city: String? = null,
    val confirmed: Boolean = false,
    val checkedIn: Boolean = false,
    val shortCode: String? = null,
    /** e.g. "Ready", "Checked in", "Waiting on guardian". */
    val statusLabel: String = "",
)

/** Builds [WidgetSnapshot]s from repository state. Pure: no I/O. */
object WidgetSnapshots {

    fun build(
        user: User?,
        event: Event?,
        roster: Roster?,
        contexts: List<ScanContext>?,
        travel: TravelCalendar?,
        tickets: List<Ticket>?,
        isOrganizer: Boolean,
        now: Instant = Instant.now(),
    ): WidgetSnapshot {
        if (user == null) return WidgetSnapshot.SignedOut
        return WidgetSnapshot(
            signedIn = true,
            organizer = if (isOrganizer && event != null) organizer(event, roster, contexts, travel, now) else null,
            ticket = tickets?.let { ticket(it, now) },
            isParticipant = user.isParticipant || !tickets.isNullOrEmpty(),
            builtAt = now.toString(),
        )
    }

    fun organizer(event: Event, roster: Roster?, contexts: List<ScanContext>?, travel: TravelCalendar?, now: Instant = Instant.now()): OrganizerWidgetData {
        val usableRoster = roster?.takeIf { event.canViewParticipants && it.syncedAt != null }
        val stats = usableRoster?.let { EventStats.from(it.participants, now) }
        return OrganizerWidgetData(
            eventId = event.id,
            eventName = event.name,
            timezone = event.timezone,
            hasCounts = stats != null,
            checkedIn = stats?.checkedIn ?: 0,
            expected = stats?.expected ?: 0,
            notArrived = stats?.notArrived ?: 0,
            lastHour = stats?.checkedInLastHour ?: 0,
            contexts = stats?.let { contextCounts(it, usableRoster, contexts) }.orEmpty(),
            updatedAt = usableRoster?.lastSyncAt,
            travelEnabled = event.travelEnabled,
            travel = if (event.travelEnabled) travel?.let { travel(it, now) } else null,
        )
    }

    /** Per-context unique-participant counts, in the event's context order. Falls back to names seen in the roster. */
    fun contextCounts(stats: EventStats, roster: Roster, contexts: List<ScanContext>?): List<ContextCount> {
        if (!contexts.isNullOrEmpty()) {
            return contexts.sortedBy { it.position }.map { c ->
                ContextCount(c.id, c.name, stats.perContext[c.id] ?: 0, c.checksIn, c.isTravelPickup || c.isAirport)
            }
        }
        val seen = LinkedHashMap<String, ContextCount>()
        roster.participants.forEach { p ->
            p.scansByContext.forEach { s ->
                if (s.scanContextId !in seen) {
                    seen[s.scanContextId] = ContextCount(s.scanContextId, s.scanContextName ?: "Scan point", stats.perContext[s.scanContextId] ?: 0, s.checksIn, s.isTravelPickup)
                }
            }
        }
        return seen.values.sortedByDescending { it.count }
    }

    fun travel(cal: TravelCalendar, now: Instant = Instant.now()): TravelWidgetData {
        val grace = now.minus(Duration.ofMinutes(30))
        val next = cal.entries
            .filter { it.direction == "inbound" && it.pickupState != "collected" && it.pickupState != "checked_in" }
            .mapNotNull { e -> Time.parse(e.primaryTimeAt)?.takeIf { !it.isBefore(grace) }?.let { e to it } }
            .minByOrNull { it.second }?.first
        return TravelWidgetData(
            awaitingPickup = cal.counts.awaitingPickup,
            collected = cal.counts.collected,
            checkedIn = cal.counts.checkedIn,
            total = cal.counts.inbound.takeIf { it > 0 } ?: cal.counts.total,
            nextArrivalName = next?.name,
            nextArrivalAt = next?.primaryTimeAt,
            nextArrivalRoute = next?.route,
            nextArrivalReference = next?.reference,
            nextArrivalMinor = next?.isUnaccompaniedMinor == true,
        )
    }

    fun ticket(tickets: List<Ticket>, now: Instant = Instant.now()): TicketWidgetData? {
        val t = TicketLogic.next(tickets, now) ?: return null
        val label = when (val s = TicketLogic.status(t)) {
            TicketLogic.Status.Ready -> "Ready"
            TicketLogic.Status.CheckedIn -> "Checked in"
            is TicketLogic.Status.Incomplete -> s.label
            is TicketLogic.Status.Closed -> s.label
        }
        return TicketWidgetData(
            id = t.id, eventName = t.event.name, startsAt = t.event.startsAt, endsAt = t.event.endsAt,
            timezone = t.event.timezone, city = t.event.locationCity, confirmed = t.confirmed,
            checkedIn = t.checkedIn, shortCode = t.shortCode, statusLabel = label,
        )
    }
}
