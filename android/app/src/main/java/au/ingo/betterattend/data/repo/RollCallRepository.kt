package au.ingo.betterattend.data.repo

import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.api.isTransient
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

/** A scan the roll call itself made: Attend confirmed it as the person's first at the scan point. */
@Serializable
data class RecordedScan(val clientScanId: String, val scannedAt: String? = null)

/**
 * One headcount, frozen when it starts: [expectedIds] never changes after that, so people checking
 * in mid-count don't move the goalposts. Kept in the encrypted cache so it survives restarts.
 *
 * When ticks are recorded at a scan point, an untick only ever takes back a scan the roll call made
 * itself ([recorded] / [queued]); anyone already scanned there keeps that scan.
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
    /** Unticked, but a scan at [scanContextId] stays recorded for them (shown on the row). */
    val stillRecorded: Set<String> = emptySet(),
    /** Ticks queued offline (participant_event id → client_scan_id), so an untick can drop them before they sync. */
    val queued: Map<String, String> = emptyMap(),
    /** Ticks Attend confirmed as a brand-new first scan at [scanContextId]: the only scans an untick may take back. */
    val recorded: Map<String, RecordedScan> = emptyMap(),
    /** Ticked people who were already scanned at [scanContextId]: unticking never touches their scans. */
    val preExisting: Set<String> = emptySet(),
    /** Ticks whose scan failed or was refused, so they aren't recorded at [scanContextId]. */
    val notRecorded: Set<String> = emptySet(),
    val finishedAt: String? = null,
) {
    val recordsScans: Boolean get() = scanContextId != null
    val isFinished: Boolean get() = finishedAt != null

    /** Everyone on the list: the expected people, then anyone added. */
    val allIds: List<String> get() = expectedIds + added.filterNot { it in expectedIdSet }

    @Transient private val expectedIdSet: Set<String> = expectedIds.toSet()

    fun isExpected(id: String): Boolean = id in expectedIdSet

    /**
     * Ticks or unticks [id]. Unticking someone who was added takes them off the list again. What was
     * recorded for them ([queued], [recorded], [preExisting]) is left for the recording work to settle.
     */
    fun toggle(id: String, at: String): RollCall =
        if (id in accounted) {
            copy(
                accounted = accounted - id,
                added = if (isExpected(id)) added else added - id,
                notRecorded = notRecorded - id,
            )
        } else if (isExpected(id) || id in added) {
            copy(accounted = accounted + (id to at), stillRecorded = stillRecorded - id, notRecorded = notRecorded - id)
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
            notRecorded = notRecorded - id,
        )
    }

    /** Forgets what was recorded for [id] (an untick has settled it). */
    fun settled(id: String): RollCall =
        copy(queued = queued - id, recorded = recorded - id, preExisting = preExisting - id, notRecorded = notRecorded - id)
}

/** Something about recording a tick worth telling the user (shown as a snackbar). */
data class RollCallNotice(val eventId: String, val message: String)

/**
 * The active roll call per event (at most one), persisted through [JsonCache] so it survives the app
 * being killed. Without a Keystore key the cache writes nothing, so a roll call then lives only as
 * long as the process (the app's usual "online only" mode).
 *
 * When the roll call records at a scan point, ticks go through [ScanRepository.submit] (offline queue,
 * idempotent client_scan_id, original time). An untick takes back only a scan the roll call made:
 * a queued one is dropped before it syncs, and a recorded one is undone only if Attend still shows it
 * as the person's only scan there (Attend's undo removes every scan at the scan point). Everything,
 * including the state change itself, runs on the app [scope], one person at a time and in the order
 * tapped, so leaving the screen never drops a tick or an untick.
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

    // The changes below run on the app scope, not the caller's, so leaving the screen can't cancel them.
    // They start undispatched: calls made one after another on the main thread queue on the mutex in
    // that order.

    fun start(rollCall: RollCall): Job = launch { mutex.withLock { if (load(rollCall.eventId) == null) store(rollCall) } }

    /** Ticks or unticks someone and, at a scan point, records or takes back their scan in the background. */
    fun toggle(eventId: String, participantEventId: String): Job =
        launch { mutate(eventId, participantEventId) { it.toggle(participantEventId, Time.nowIso()) } }

    /** Marks someone who wasn't expected as present. */
    fun add(eventId: String, participantEventId: String, name: String?): Job =
        launch { mutate(eventId, participantEventId) { it.add(participantEventId, name, Time.nowIso()) } }

    fun finish(eventId: String): Job = launch { mutate(eventId, null) { it.copy(finishedAt = it.finishedAt ?: Time.nowIso()) } }

    fun resume(eventId: String): Job = launch { mutate(eventId, null) { it.copy(finishedAt = null) } }

    /** Ends the roll call and forgets it. Scans it recorded stay recorded; work still running for it finishes. */
    fun end(eventId: String): Job = launch {
        mutex.withLock {
            _sessions.update { it + (eventId to null) }
            cache.remove(key(eventId))
        }
    }

    /**
     * Sign-out: stops the recording work and forgets everything. Removes the files under the lock, so a
     * write already in flight lands first and any later one finds no roll call and writes nothing. Run
     * it before the cache directory is wiped.
     */
    suspend fun clear() {
        mutex.withLock {
            // Under the lock: recording work is only ever queued while holding it, so none starts after this.
            synchronized(jobs) {
                jobs.values.forEach { it.cancel() }
                jobs.clear()
            }
            val ids = _sessions.value.keys
            _sessions.value = emptyMap()
            ids.forEach { runCatching { cache.remove(key(it)) } }
        }
    }

    private fun launch(block: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }

    /** Applies [transform] and, if [person]'s tick changed, queues their recording work (under the lock, so in order). */
    private suspend fun mutate(eventId: String, person: String?, transform: (RollCall) -> RollCall) = mutex.withLock {
        val current = load(eventId) ?: return@withLock
        val next = transform(current)
        if (next == current) return@withLock
        store(next)
        if (person != null) record(current, next, person)
    }

    private suspend fun store(rollCall: RollCall) {
        _sessions.update { it + (rollCall.eventId to rollCall) }
        cache.write(key(rollCall.eventId), RollCall.serializer(), rollCall)
    }

    /** The roll call [startedAt] identifies, if it's still the current one. */
    private fun current(eventId: String, startedAt: String): RollCall? = _sessions.value[eventId]?.takeIf { it.startedAt == startedAt }

    /** Changes the roll call [startedAt] identifies, if it's still current: work finishing after it ended (or sign-out) writes nothing. */
    private suspend fun update(eventId: String, startedAt: String, transform: (RollCall) -> RollCall) = mutex.withLock {
        val current = current(eventId, startedAt) ?: return@withLock
        val next = transform(current)
        if (next != current) store(next)
    }

    private fun record(before: RollCall, after: RollCall, id: String) {
        if (after.scanContextId == null) return
        val ticked = id in after.accounted && id !in before.accounted
        val unticked = id in before.accounted && id !in after.accounted
        if (!ticked && !unticked) return
        synchronized(jobs) {
            val previous = jobs[id]
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                if (ticked) recordTick(after, id) else recordUntick(after, id)
            }
            jobs[id] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[id] === job) jobs.remove(id) } }
            job.start()
        }
    }

    private fun nameOf(rc: RollCall, id: String): String =
        rc.names[id] ?: participants.roster(rc.eventId)?.byEventId?.get(id)?.let { it.fullName?.takeIf { n -> n.isNotBlank() } ?: it.name } ?: "Them"

    /** Whether the cached roster shows [id] with a scan at [contextId]. */
    private suspend fun rosterHasScan(eventId: String, id: String, contextId: String): Boolean =
        participants.load(eventId)?.byEventId?.get(id)?.scansByContext?.any { it.scanContextId == contextId && it.scanCount > 0 } == true

    /** Records a tick as a scan, and always notes how that went so a later untick knows what it may take back. */
    private suspend fun recordTick(rc: RollCall, id: String) {
        val contextId = rc.scanContextId ?: return
        val where = rc.scanContextName ?: "the scan point"
        // Read now, after this person's earlier work has finished: anyone already scanned there keeps that scan.
        val hadScan = rosterHasScan(rc.eventId, id, contextId)
        val outcome = try {
            // Staff are looking at the person, as with a check-in from their page: don't second-guess them.
            scans.submit(rc.eventId, ScanInput(participantId = id, source = "manual"), contextId, rc.scanContextName,
                checksIn = rc.scanContextChecksIn, enforceAdmission = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanOutcome.Failed("", e.friendlyMessage, null, notFound = false)
        }
        val failure: String? = when (outcome) {
            is ScanOutcome.Scanned -> {
                val r = outcome.result
                // Only a brand-new first scan there is the roll call's to take back later.
                val ours = r.success && !hadScan && !r.isAlreadyScanned && !r.deduplicated
                update(rc.eventId, rc.startedAt) {
                    if (ours) it.copy(recorded = it.recorded + (id to RecordedScan(outcome.clientScanId, r.scan?.scannedAt)))
                    else it.copy(preExisting = it.preExisting + id)
                }
                null
            }
            is ScanOutcome.AlreadyScanned -> {
                update(rc.eventId, rc.startedAt) { it.copy(preExisting = it.preExisting + id) }
                null
            }
            is ScanOutcome.Queued -> {
                update(rc.eventId, rc.startedAt) {
                    it.copy(queued = it.queued + (id to outcome.clientScanId), preExisting = if (hadScan) it.preExisting + id else it.preExisting)
                }
                null
            }
            // Already scanned there (offline pre-check): that scan exists, and isn't ours.
            is ScanOutcome.Rejected -> if (outcome.reason == RejectReason.AlreadyCheckedIn) {
                update(rc.eventId, rc.startedAt) { it.copy(preExisting = it.preExisting + id) }
                null
            } else {
                outcome.reason.short
            }
            is ScanOutcome.Failed -> outcome.message
        }
        if (failure != null) {
            update(rc.eventId, rc.startedAt) {
                it.copy(notRecorded = it.notRecorded + id, preExisting = if (hadScan) it.preExisting + id else it.preExisting)
            }
            notice(rc.eventId, "${nameOf(rc, id)} is ticked, but not recorded at $where: $failure")
        }
    }

    /**
     * Takes back the scan the person's tick made, and nothing else. Reads what that tick recorded now
     * (its job has finished by the time this runs), not when the untick was tapped.
     */
    private suspend fun recordUntick(snapshot: RollCall, id: String) {
        val contextId = snapshot.scanContextId ?: return
        val eventId = snapshot.eventId
        val rc = current(eventId, snapshot.startedAt) ?: snapshot
        val where = rc.scanContextName ?: "the scan point"
        val name = nameOf(rc, id)

        suspend fun keep(message: String?) {
            update(eventId, rc.startedAt) { it.settled(id).copy(stillRecorded = if (id in it.accounted) it.stillRecorded else it.stillRecorded + id) }
            if (message != null) notice(eventId, message)
        }
        suspend fun clean() = update(eventId, rc.startedAt) { it.settled(id).copy(stillRecorded = it.stillRecorded - id) }

        val queuedScan = rc.queued[id]
        if (queuedScan != null) {
            // Dropped before it synced means nothing of ours reached Attend: never followed by an undo.
            if (scans.discardPending(queuedScan, undoIfLanded = true)) {
                if (id in rc.preExisting) keep(null) else clean()
            } else {
                keep("Unticked $name. The tick had already synced, so the scan at $where stays.")
            }
            return
        }

        val ours = rc.recorded[id]
        if (ours == null) {
            // Scanned there before the tick (or the tick wasn't recorded): their scans are left alone.
            val stays = id in rc.preExisting || (id !in rc.notRecorded && rosterHasScan(eventId, id, contextId))
            if (stays) keep("Unticked $name. They were already scanned at $where, so that scan stays.") else clean()
            return
        }

        // Attend's undo removes every scan at the scan point: only go ahead while ours is still the only one.
        val reason: String? = try {
            val there = participants.detail(eventId, id).scansByContext.firstOrNull { it.scanContextId == contextId }
            when {
                there == null || there.scanCount == 0 -> null // already gone
                there.scanCount > 1 -> "Attend shows other scans for them there"
                ours.scannedAt != null && there.firstScannedAt != null &&
                    Time.parse(ours.scannedAt) != Time.parse(there.firstScannedAt) -> "the scan there isn't the roll call's"
                else -> {
                    scans.undo(eventId, id, contextId)
                    participants.applyUndo(eventId, id, contextId, emptyList())
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isTransient) "you're offline" else e.friendlyMessage
        }
        if (reason == null) clean() else keep("Unticked $name, but the scan at $where stays: $reason.")
    }

    private fun notice(eventId: String, message: String) {
        _notices.tryEmit(RollCallNotice(eventId, message))
    }

    private fun key(eventId: String) = "rollcall_$eventId"
}
