package au.ingo.betterattend.data.repo

import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
data class Roster(
    val eventId: String,
    val participants: List<Participant> = emptyList(),
    val syncedAt: String? = null,
    val lastFullSyncAt: String? = null,
    val lastSyncAt: String? = null,
) {
    val byEventId: Map<String, Participant> by lazy { participants.associateBy { it.participantEventId } }

    /** Finds by participant_event id, participant id, NFC token, or QR payload. */
    fun find(identifier: String): Participant? {
        val id = identifier.trim().removePrefix("attend://checkin/").removePrefix("attend:P:")
        return byEventId[id]
            ?: participants.firstOrNull { it.participantId.equals(id, true) || it.nfcBadgeToken?.equals(id, true) == true }
    }
}

/** Counts for dashboards and widgets, all derived from the roster (the API has no stats endpoint). */
data class EventStats(
    val registered: Int = 0,
    val confirmed: Int = 0,
    val checkedIn: Int = 0,
    val notArrived: Int = 0,
    val withdrawn: Int = 0,
    val byStatus: Map<String, Int> = emptyMap(),
    /** scan context id -> unique participants scanned there */
    val perContext: Map<String, Int> = emptyMap(),
    val anaphylaxis: Int = 0,
    val highSupport: Int = 0,
    val checkedInLastHour: Int = 0,
) {
    val progress: Float get() = if (confirmed == 0) 0f else checkedIn.coerceAtMost(confirmed) / confirmed.toFloat()

    companion object {
        fun from(participants: List<Participant>, now: Instant = Instant.now()): EventStats {
            val active = participants.filter { it.isActive }
            val confirmed = active.filter { it.status == "complete" }
            val checkedIn = active.filter { it.isCheckedIn }
            val perContext = HashMap<String, Int>()
            active.forEach { p -> p.scansByContext.forEach { s -> perContext.merge(s.scanContextId, 1, Int::plus) } }
            val hourAgo = now.minus(Duration.ofHours(1))
            return EventStats(
                registered = active.size,
                confirmed = confirmed.size,
                checkedIn = checkedIn.size,
                notArrived = confirmed.count { !it.isCheckedIn },
                withdrawn = participants.size - active.size,
                byStatus = participants.groupingBy { it.status ?: "unknown" }.eachCount(),
                perContext = perContext,
                anaphylaxis = active.count { it.hasAnaphylaxisRisk },
                highSupport = active.count { it.highSupportFlag },
                checkedInLastHour = checkedIn.count { Time.parse(it.checkedInAt)?.isAfter(hourAgo) == true },
            )
        }
    }
}

class ParticipantRepository(
    private val api: AttendApi,
    private val cache: JsonCache,
) {
    private val _rosters = MutableStateFlow<Map<String, Roster>>(emptyMap())
    val rosters: StateFlow<Map<String, Roster>> = _rosters.asStateFlow()

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    private val mutex = Mutex()

    fun roster(eventId: String): Roster? = _rosters.value[eventId]

    suspend fun load(eventId: String): Roster? {
        _rosters.value[eventId]?.let { return it }
        val cached = cache.read(key(eventId), Roster.serializer()) ?: return null
        _rosters.update { if (it.containsKey(eventId)) it else it + (eventId to cached) }
        return _rosters.value[eventId]
    }

    /**
     * Full sync the first time (and every few hours to prune deletions), deltas via
     * `updated_since` otherwise. Throws on failure; the cached roster stays intact.
     */
    suspend fun sync(eventId: String, forceFull: Boolean = false): Roster = mutex.withLock {
        _syncing.update { it + eventId }
        try {
            val existing = load(eventId)
            val lastFull = Time.parse(existing?.lastFullSyncAt)
            val needFull = forceFull || existing?.syncedAt == null || lastFull == null ||
                Duration.between(lastFull, Instant.now()) > FULL_SYNC_INTERVAL
            val now = Time.nowIso()
            val roster = if (needFull) {
                val res = api.participants(eventId)
                Roster(eventId, res.participants.sortedBy { it.name.lowercase() }, res.syncedAt, now, now)
            } else {
                val res = api.participants(eventId, existing!!.syncedAt)
                val merged = existing.byEventId.toMutableMap()
                res.participants.forEach { p -> merged[p.participantEventId] = mergeKeepingDetail(merged[p.participantEventId], p) }
                existing.copy(
                    participants = merged.values.sortedBy { it.name.lowercase() },
                    syncedAt = res.syncedAt ?: existing.syncedAt,
                    lastSyncAt = now,
                )
            }
            _rosters.update { it + (eventId to roster) }
            cache.write(key(eventId), Roster.serializer(), roster)
            roster
        } finally {
            _syncing.update { it - eventId }
        }
    }

    /** Merge a participant we learned about elsewhere (scan response, detail fetch) into the roster. */
    suspend fun upsert(eventId: String, participant: Participant) {
        val roster = load(eventId) ?: Roster(eventId)
        val merged = roster.byEventId.toMutableMap()
        merged[participant.participantEventId] = mergeKeepingDetail(merged[participant.participantEventId], participant)
        val updated = roster.copy(participants = merged.values.sortedBy { it.name.lowercase() })
        _rosters.update { it + (eventId to updated) }
        cache.write(key(eventId), Roster.serializer(), updated)
    }

    /** Reflects an undo locally so lists update instantly (the next delta sync confirms it). */
    suspend fun applyUndo(eventId: String, participantEventId: String, scanContextId: String?, contexts: List<ScanContext>) {
        val roster = load(eventId) ?: return
        val p = roster.byEventId[participantEventId] ?: return
        val remaining = if (scanContextId == null) emptyList() else p.scansByContext.filter { it.scanContextId != scanContextId }
        val checkInIds = contexts.filter { it.checksIn }.map { it.id }.toSet()
        val checkedInAt = remaining.filter { it.checksIn || it.scanContextId in checkInIds }
            .mapNotNull { it.firstScannedAt }.minByOrNull { Time.parse(it) ?: Instant.MAX }
        upsert(eventId, p.copy(scansByContext = remaining, checkedInAt = checkedInAt))
    }

    suspend fun detail(eventId: String, participantEventId: String): Participant {
        val full = api.participant(eventId, participantEventId)
        upsert(eventId, full)
        return full
    }

    suspend fun search(eventId: String, q: String): List<Participant> = api.searchParticipants(eventId, q)

    suspend fun clear() {
        _rosters.value = emptyMap()
        cache.clear()
    }

    private fun key(eventId: String) = "roster_$eventId"

    companion object {
        val FULL_SYNC_INTERVAL: Duration = Duration.ofHours(3)

        /** Newer list data wins, but detail-only blocks survive a list refresh. */
        fun mergeKeepingDetail(old: Participant?, new: Participant): Participant {
            if (old == null) return new
            return new.copy(
                personal = new.personal ?: old.personal,
                accommodation = new.accommodation ?: old.accommodation,
                consents = new.consents ?: old.consents,
                guardians = new.guardians ?: old.guardians,
                medicalDetail = new.medicalDetail ?: old.medicalDetail,
                dietaryDetail = new.dietaryDetail ?: old.dietaryDetail,
                accessibility = new.accessibility ?: old.accessibility,
                safeguardingDetail = new.safeguardingDetail ?: old.safeguardingDetail,
                groups = new.groups.ifEmpty { old.groups },
                tshirtSize = new.tshirtSize ?: old.tshirtSize,
                headshotUrl = new.headshotUrl ?: old.headshotUrl,
            )
        }
    }
}

