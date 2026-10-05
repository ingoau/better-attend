package au.ingo.betterattend.data.repo

import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.api.isTransient
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Who a roll call expects to find. */
@Serializable
enum class RollCallExpected(val label: String) {
    /** Active participants who've checked in. */
    @SerialName("checked_in") CheckedIn("Checked in"),

    /** Every active registration, here or not. */
    @SerialName("registered") Registered("Everyone registered"),
}

/**
 * One headcount, frozen when it starts: [expectedIds] never changes after that, so people checking
 * in mid-count don't move the goalposts. Kept in the encrypted cache so it survives restarts.
 */
@Serializable
data class RollCall(
    val eventId: String,
    val startedAt: String,
    val expected: RollCallExpected,
    /** participant_event ids, frozen at start. */
    val expectedIds: List<String>,
    /** Names at start, so the summary still reads if someone leaves the roster mid-count. */
    val names: Map<String, String> = emptyMap(),
    /** Scan point the user chose to record ticks at; null = only on this phone. */
    val scanContextId: String? = null,
    val scanContextName: String? = null,
    val scanContextChecksIn: Boolean = false,
    /** participant_event id → when they were ticked (ISO). */
    val accounted: Map<String, String> = emptyMap(),
    /** People who weren't expected but turned up, in the order they were added. */
    val added: List<String> = emptyList(),
    /** Unticked while offline: the scan recorded for them at [scanContextId] stays. */
    val stillRecorded: Set<String> = emptySet(),
    /** Ticks queued offline (participant_event id → client_scan_id), so an untick can drop them before they sync. */
    val queued: Map<String, String> = emptyMap(),
    val finishedAt: String? = null,
) {
    val recordsScans: Boolean get() = scanContextId != null
    val isFinished: Boolean get() = finishedAt != null

    /** Everyone on the list: the expected people, then anyone added. */
    val allIds: List<String> get() = expectedIds + added.filterNot { it in expectedIdSet }

    @Transient private val expectedIdSet: Set<String> = expectedIds.toSet()

    fun isExpected(id: String): Boolean = id in expectedIdSet

    /** Ticks or unticks [id]. Unticking someone who was added takes them off the list again. */
    fun toggle(id: String, at: String): RollCall =
        if (id in accounted) {
            copy(
                accounted = accounted - id,
                added = if (isExpected(id)) added else added - id,
                queued = queued - id,
            )
        } else if (isExpected(id) || id in added) {
            copy(accounted = accounted + (id to at), stillRecorded = stillRecorded - id)
        } else {
            this
        }

    /** Adds someone who wasn't expected, ticked. Expected people are just ticked. */
    fun add(id: String, name: String?, at: String): RollCall = when {
        id in accounted -> this
        isExpected(id) || id in added -> toggle(id, at)
        else -> copy(
            added = added + id,
            accounted = accounted + (id to at),
            names = if (name != null) names + (id to name) else names,
            stillRecorded = stillRecorded - id,
        )
    }
}

/** Something about recording a tick worth telling the user (shown as a snackbar). */
data class RollCallNotice(val eventId: String, val message: String)

/**
 * The active roll call per event (at most one), persisted through [JsonCache] so it survives the app
 * being killed. Without a Keystore key the cache writes nothing, so a roll call then lives only as
 * long as the process (the app's usual "online only" mode).
 *
 * When the roll call records at a scan point, ticks go through [ScanRepository.submit] (offline queue,
 * idempotent client_scan_id, original time) and unticks through undo. That work runs on the app
 * [scope], one person at a time and in order, so leaving the screen never drops a tick.
 */
class RollCallRepository(
    private val cache: JsonCache,
    private val scans: ScanRepository,
    private val participants: ParticipantRepository,
    private val scope: CoroutineScope,
) {
    /** Event id → its roll call, or null when there's none. Events not read from disk yet are absent. */
    private val _sessions = MutableStateFlow<Map<String, RollCall?>>(emptyMap())
    val sessions: StateFlow<Map<String, RollCall?>> = _sessions.asStateFlow()

    private val _notices = MutableSharedFlow<RollCallNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<RollCallNotice> = _notices.asSharedFlow()

    private val mutex = Mutex()

    /** Per person, the last recording job, so a quick tick → untick happens in that order. */
    private val jobs = HashMap<String, Job>()

    suspend fun load(eventId: String): RollCall? {
        if (_sessions.value.containsKey(eventId)) return _sessions.value[eventId]
        val saved = cache.read(key(eventId), RollCall.serializer())
        _sessions.update { if (it.containsKey(eventId)) it else it + (eventId to saved) }
        return _sessions.value[eventId]
    }

    suspend fun start(rollCall: RollCall) = mutex.withLock { store(rollCall) }

    /** Ticks or unticks someone and, at a scan point, records or undoes their scan in the background. */
    suspend fun toggle(eventId: String, participantEventId: String) {
        val (before, after) = mutate(eventId) { it.toggle(participantEventId, Time.nowIso()) } ?: return
        record(before, after, participantEventId)
    }

    /** Marks someone who wasn't expected as present. */
    suspend fun add(eventId: String, participantEventId: String, name: String?) {
        val (before, after) = mutate(eventId) { it.add(participantEventId, name, Time.nowIso()) } ?: return
        record(before, after, participantEventId)
    }

    suspend fun finish(eventId: String) { mutate(eventId) { it.copy(finishedAt = Time.nowIso()) } }

    suspend fun resume(eventId: String) { mutate(eventId) { it.copy(finishedAt = null) } }

    /** Ends the roll call and forgets it. Scans it recorded stay recorded. */
    suspend fun end(eventId: String) = mutex.withLock {
        _sessions.update { it + (eventId to null) }
        cache.remove(key(eventId))
    }

    /** Sign-out: forget everything (the cache directory itself is wiped by [ParticipantRepository.clear]). */
    suspend fun clear() = mutex.withLock {
        val ids = _sessions.value.keys
        _sessions.value = emptyMap()
        ids.forEach { runCatching { cache.remove(key(it)) } }
    }

    private suspend fun mutate(eventId: String, transform: (RollCall) -> RollCall): Pair<RollCall, RollCall>? = mutex.withLock {
        val current = load(eventId) ?: return@withLock null
        val next = transform(current)
        if (next != current) store(next)
        current to next
    }

    private suspend fun store(rollCall: RollCall) {
        _sessions.update { it + (rollCall.eventId to rollCall) }
        cache.write(key(rollCall.eventId), RollCall.serializer(), rollCall)
    }

    private fun record(before: RollCall, after: RollCall, id: String) {
        val contextId = after.scanContextId ?: return
        val ticked = id in after.accounted && id !in before.accounted
        val unticked = id in before.accounted && id !in after.accounted
        if (!ticked && !unticked) return
        val queuedScan = before.queued[id]
        val name = after.names[id] ?: participants.roster(after.eventId)?.byEventId?.get(id)?.name ?: "Them"
        synchronized(jobs) {
            val previous = jobs[id]
            jobs[id] = scope.launch {
                previous?.join()
                if (ticked) recordTick(after, id, contextId, name) else recordUntick(after, id, contextId, name, queuedScan)
            }
        }
    }

    private suspend fun recordTick(rc: RollCall, id: String, contextId: String, name: String) {
        val outcome = try {
            // Staff are looking at the person, as with a check-in from their page: don't second-guess them.
            scans.submit(rc.eventId, ScanInput(participantId = id, source = "manual"), contextId, rc.scanContextName,
                checksIn = rc.scanContextChecksIn, enforceAdmission = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice(rc.eventId, "$name is ticked, but the scan wasn't recorded: ${e.friendlyMessage}")
            return
        }
        when (outcome) {
            is ScanOutcome.Scanned, is ScanOutcome.AlreadyScanned -> Unit
            is ScanOutcome.Queued -> mutex.withLock {
                val current = _sessions.value[rc.eventId]
                // Only if they're still ticked: an untick in the meantime already ran after this job.
                if (current != null && id in current.accounted) store(current.copy(queued = current.queued + (id to outcome.clientScanId)))
            }
            is ScanOutcome.Rejected ->
                // Already scanned there (offline pre-check) means the scan exists: nothing to report.
                if (outcome.reason != RejectReason.AlreadyCheckedIn) {
                    notice(rc.eventId, "$name is ticked, but the scan wasn't recorded: ${outcome.reason.short}")
                }
            is ScanOutcome.Failed -> notice(rc.eventId, "$name is ticked, but the scan wasn't recorded: ${outcome.message}")
        }
    }

    private suspend fun recordUntick(rc: RollCall, id: String, contextId: String, name: String, queuedScan: String?) {
        // A tick still waiting to sync is simply dropped.
        val droppedQueued = queuedScan != null && scans.pending.value.any { it.clientScanId == queuedScan }
        if (droppedQueued) scans.discardPending(queuedScan!!)
        try {
            scans.undo(rc.eventId, id, contextId)
            participants.applyUndo(rc.eventId, id, contextId, emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (droppedQueued) return // never reached Attend
            if (e.isTransient) {
                mutex.withLock {
                    val current = _sessions.value[rc.eventId]
                    if (current != null && id !in current.accounted) store(current.copy(stillRecorded = current.stillRecorded + id))
                }
                notice(rc.eventId, "Unticked $name. You're offline, so the scan at ${rc.scanContextName ?: "the scan point"} stays recorded.")
            } else {
                notice(rc.eventId, "Unticked $name, but the scan couldn't be removed: ${e.friendlyMessage}")
            }
        }
    }

    private fun notice(eventId: String, message: String) {
        _notices.tryEmit(RollCallNotice(eventId, message))
    }

    private fun key(eventId: String) = "rollcall_$eventId"
}
