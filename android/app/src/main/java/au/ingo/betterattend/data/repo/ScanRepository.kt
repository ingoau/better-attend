package au.ingo.betterattend.data.repo

import au.ingo.betterattend.data.api.ApiException
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.api.isTransient
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanResult
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.scan.Precheck
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.scan.ScanAdmission
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** What was scanned. Exactly one of [participantId] / [badgeToken] is normally set. */
@Serializable
data class ScanInput(
    /** Raw QR text, participant UUID, or participant_event UUID. */
    val participantId: String? = null,
    val badgeToken: String? = null,
    /** "qr" | "nfc" | "manual" */
    val source: String = "qr",
)

@Serializable
data class PendingScan(
    val clientScanId: String,
    val eventId: String,
    val scanContextId: String?,
    val scanContextName: String?,
    val input: ScanInput,
    val scannedAt: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** Best-effort name for display while offline. */
    val knownName: String? = null,
)

sealed interface ScanOutcome {
    val clientScanId: String
    val participant: Participant?

    /** Confirmed by the server. */
    data class Scanned(override val clientScanId: String, val result: ScanResult, override val participant: Participant?) : ScanOutcome
    data class AlreadyScanned(override val clientScanId: String, val result: ScanResult, override val participant: Participant?) : ScanOutcome
    /**
     * Couldn't reach the server (or it took too long); passed the offline pre-check, saved, and will
     * sync automatically. [rosterAt] is when the roster it was checked against was synced.
     */
    data class Queued(
        override val clientScanId: String,
        val pending: PendingScan,
        override val participant: Participant?,
        val reason: String,
        val rosterAt: String? = null,
    ) : ScanOutcome
    /** The server refused the scan ([status] is its HTTP status, 0 when it never got that far). */
    data class Failed(
        override val clientScanId: String,
        val message: String,
        override val participant: Participant?,
        val notFound: Boolean,
        val status: Int = 0,
    ) : ScanOutcome
    /**
     * Not admitted. [offline]: the cached roster said no and nothing was queued. Otherwise the server's
     * own record of the person said no; [reverted] when the scan Attend recorded anyway was taken back.
     */
    data class Rejected(
        override val clientScanId: String,
        val reason: RejectReason,
        override val participant: Participant?,
        val detail: String? = null,
        val offline: Boolean = false,
        val reverted: Boolean = false,
        val rosterAt: String? = null,
    ) : ScanOutcome

    /** The server turned this scan down after the app had shown it as going through. */
    val isServerRejection: Boolean
        get() = (this is Failed && status != 0 && status != 401) || (this is Rejected && !offline)
}

/**
 * A scan that didn't let someone in, kept until staff dismiss it: the scanner's interrupt banner, and
 * offline check-ins the server turned down later (persisted, shown on Home).
 */
@Serializable
data class ScanRejection(
    val clientScanId: String,
    val eventId: String,
    val participantEventId: String? = null,
    val name: String,
    /** "consent not signed", "not registered for this event", … */
    val reason: String,
    val scannedAt: String,
    val contextName: String? = null,
) {
    /** "Mia Chen: consent not signed" */
    val headline: String get() = "$name: $reason"
}

/** Log line for the scanner's "recent scans" sheet (kept in memory for this app session). */
data class ScanLogEntry(val outcome: ScanOutcome, val contextName: String?, val at: String)

class ScanRepository(
    private val api: AttendApi,
    private val cache: JsonCache,
    private val participants: ParticipantRepository,
    private val scope: CoroutineScope,
    /** How long a live scan may take before it's treated as offline and queued. */
    private val scanTimeoutMillis: Long = SCAN_TIMEOUT_MILLIS,
) {
    private val _pending = MutableStateFlow<List<PendingScan>>(emptyList())
    val pending: StateFlow<List<PendingScan>> = _pending.asStateFlow()

    private val _rejections = MutableStateFlow<List<ScanRejection>>(emptyList())
    /** Offline check-ins the server turned down when they were sent, until dismissed. */
    val rejections: StateFlow<List<ScanRejection>> = _rejections.asStateFlow()

    private val _log = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val log: StateFlow<List<ScanLogEntry>> = _log.asStateFlow()

    private val queueMutex = Mutex()

    /**
     * Resolves a scan context for scans queued before contexts could load (the server rejects a
     * context-less scan when an event has several). Set by AppContainer.
     */
    var fallbackContext: suspend (eventId: String) -> String? = { null }

    /** Set by the app to schedule a WorkManager flush when something is queued. */
    var onQueued: () -> Unit = {}

    /** Set by the app to raise a notification when queued scans are turned down on sync. */
    var onRejected: (List<ScanRejection>) -> Unit = {}

    /**
     * Cached rosters of the user's other events, keyed by event name, for the offline "wrong event"
     * check. Set by AppContainer.
     */
    var otherRosters: suspend (eventId: String) -> Map<String, Roster> = { emptyMap() }

    /** Completes once the persisted queue has been read, so nothing races the initial load. */
    private val queueLoaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val saved = cache.read(KEY_QUEUE, ListSerializer(PendingScan.serializer())).orEmpty()
                _pending.update { current -> saved + current.filter { c -> saved.none { it.clientScanId == c.clientScanId } } }
                val rejected = cache.read(KEY_REJECTIONS, ListSerializer(ScanRejection.serializer())).orEmpty()
                _rejections.update { current -> rejected + current.filter { c -> rejected.none { it.clientScanId == c.clientScanId } } }
            } finally {
                queueLoaded.complete(Unit)
            }
        }
    }

    /**
     * Sends a scan, giving up after [scanTimeoutMillis]. The server's answer is checked against
     * [ScanAdmission]; if it can't be reached in time, the scan is pre-checked against the cached
     * roster and queued (keeping its original time) only if nothing there says no.
     *
     * @param checksIn the checkpoint checks people in (a missing waiver only blocks there).
     */
    suspend fun submit(
        eventId: String,
        input: ScanInput,
        scanContextId: String?,
        scanContextName: String?,
        checksIn: Boolean = true,
    ): ScanOutcome {
        val clientScanId = UUID.randomUUID().toString()
        val scannedAt = Time.nowIso()
        val roster = participants.load(eventId)
        val known = (input.badgeToken ?: input.participantId)?.let { roster?.find(it) }
        val outcome = try {
            val result = withTimeout(scanTimeoutMillis) {
                api.createScan(
                    eventId = eventId,
                    participantId = input.participantId,
                    badgeToken = input.badgeToken,
                    scanContextId = scanContextId,
                    source = input.source.takeIf { it == "manual" },
                    clientScanId = clientScanId,
                    scannedAt = scannedAt,
                )
            }
            val p = result.participant?.let { ParticipantRepository.mergeKeepingDetail(known, it) } ?: known
            result.participant?.let { participants.upsert(eventId, p!!) }
            verdict(eventId, clientScanId, result, p, scanContextId, checksIn)
        } catch (e: TimeoutCancellationException) {
            offline(eventId, clientScanId, input, scanContextId, scanContextName, checksIn, scannedAt, roster, known, "Attend took too long to respond.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isTransient) {
                offline(eventId, clientScanId, input, scanContextId, scanContextName, checksIn, scannedAt, roster, known, e.friendlyMessage)
            } else {
                val notFound = (e as? ApiException)?.isNotFound == true
                ScanOutcome.Failed(
                    clientScanId,
                    if (notFound) "Not registered for this event" else e.friendlyMessage,
                    known,
                    notFound,
                    (e as? ApiException)?.status ?: 0,
                )
            }
        }
        _log.update { (listOf(ScanLogEntry(outcome, scanContextName, scannedAt)) + it).take(100) }
        return outcome
    }

    /**
     * Turns a scan the server accepted into an outcome. Attend records scans for withdrawn people and
     * missing waivers too, so its fresh record of the person gets the final say; a brand-new scan of
     * someone who isn't admitted is taken back so they don't count as here.
     */
    private suspend fun verdict(
        eventId: String,
        clientScanId: String,
        result: ScanResult,
        p: Participant?,
        scanContextId: String?,
        checksIn: Boolean,
    ): ScanOutcome {
        val problem = p?.let { ScanAdmission.problem(it, result.scanContext?.checksIn ?: checksIn) }
        return when {
            problem != null -> ScanOutcome.Rejected(clientScanId, problem, p, reverted = revert(eventId, result, p, scanContextId))
            result.isAlreadyScanned -> ScanOutcome.AlreadyScanned(clientScanId, result, p)
            else -> ScanOutcome.Scanned(clientScanId, result, p)
        }
    }

    /** Undoes the scan Attend just recorded, if it was the person's first at that checkpoint. True if it's gone. */
    private suspend fun revert(eventId: String, result: ScanResult, p: Participant, scanContextId: String?): Boolean {
        if (result.isAlreadyScanned) return false
        val contextId = result.scanContext?.id ?: scanContextId ?: return false
        return try {
            api.undoScans(eventId, p.participantEventId, contextId)
            participants.applyUndo(eventId, p.participantEventId, contextId, emptyList())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** The offline path: pre-check against the cached roster, then queue only what passes. */
    private suspend fun offline(
        eventId: String,
        clientScanId: String,
        input: ScanInput,
        scanContextId: String?,
        scanContextName: String?,
        checksIn: Boolean,
        scannedAt: String,
        roster: Roster?,
        known: Participant?,
        reason: String,
    ): ScanOutcome {
        queueLoaded.await()
        val code = input.badgeToken ?: input.participantId
        val others = if (roster?.syncedAt != null && input.badgeToken == null && code != null && roster.find(code) == null) {
            otherRosters(eventId)
        } else emptyMap()
        val rosterAt = ScanAdmission.rosterTime(roster)
        return when (val pre = ScanAdmission.precheck(input, roster, scanContextId, checksIn, _pending.value, others)) {
            is Precheck.Block -> ScanOutcome.Rejected(clientScanId, pre.reason, pre.participant, pre.detail, offline = true, rosterAt = rosterAt)
            is Precheck.Pass -> {
                val pending = PendingScan(clientScanId, eventId, scanContextId, scanContextName, input, scannedAt, 1, reason, known?.name)
                enqueue(pending)
                ScanOutcome.Queued(clientScanId, pending, known, reason, rosterAt)
            }
        }
    }

    private suspend fun enqueue(p: PendingScan) = queueLoaded.await().let { queueMutex.withLock {
        _pending.update { it + p }
        cache.write(KEY_QUEUE, ListSerializer(PendingScan.serializer()), _pending.value)
        onQueued()
    } }

    /**
     * Sends queued scans oldest first, each with the time it was really scanned (`scanned_at`).
     * `client_scan_id` makes retries idempotent. Returns the number still pending; stops early on a
     * transient failure so we don't burn the rate limit. Scans the server turns down are kept in
     * [rejections] and reported through [onRejected], not just logged.
     */
    suspend fun flush(): Int = queueLoaded.await().let { queueMutex.withLock {
        val remaining = _pending.value.toMutableList()
        val rejected = mutableListOf<ScanRejection>()
        val iterator = remaining.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val known = (p.input.badgeToken ?: p.input.participantId)?.let { participants.roster(p.eventId)?.find(it) }
            try {
                val contextId = p.scanContextId ?: fallbackContext(p.eventId)
                val result = api.createScan(
                    eventId = p.eventId,
                    participantId = p.input.participantId,
                    badgeToken = p.input.badgeToken,
                    scanContextId = contextId,
                    source = p.input.source.takeIf { it == "manual" },
                    clientScanId = p.clientScanId,
                    scannedAt = p.scannedAt,
                )
                result.participant?.let { participants.upsert(p.eventId, it) }
                val who = result.participant ?: known
                // Accepted, but the server's record says they shouldn't have been let in (e.g. withdrawn since the roster synced).
                who?.let { ScanAdmission.problem(it, result.scanContext?.checksIn ?: true) }?.let { problem ->
                    revert(p.eventId, result, who, contextId)
                    rejected += p.rejection(who, problem.short)
                }
                iterator.remove()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline/5xx/429, or an auth problem the user can fix by signing in again: keep it.
                if (e.isTransient || (e as? ApiException)?.let { it.isUnauthorized || it.isForbidden } == true) break
                // Permanent failure (e.g. not registered): drop it, and tell staff who it was and why.
                iterator.remove()
                val reason = if ((e as? ApiException)?.isNotFound == true) RejectReason.NotRegistered.short else e.friendlyMessage
                rejected += p.rejection(known, reason)
                _log.update {
                    listOf(ScanLogEntry(ScanOutcome.Failed(p.clientScanId, "Offline scan rejected: $reason", known, false), p.scanContextName, Time.nowIso())) + it
                }
            }
        }
        _pending.value = remaining
        cache.write(KEY_QUEUE, ListSerializer(PendingScan.serializer()), remaining)
        if (rejected.isNotEmpty()) {
            _rejections.update { current -> rejected.reversed() + current.filter { c -> rejected.none { it.clientScanId == c.clientScanId } } }
            cache.write(KEY_REJECTIONS, ListSerializer(ScanRejection.serializer()), _rejections.value)
            onRejected(rejected)
        }
        remaining.size
    } }

    private fun PendingScan.rejection(who: Participant?, reason: String) = ScanRejection(
        clientScanId = clientScanId,
        eventId = eventId,
        participantEventId = who?.participantEventId,
        name = who?.name ?: knownName ?: input.badgeToken?.let { "NFC badge" } ?: "Unknown attendee",
        reason = reason,
        scannedAt = scannedAt,
        contextName = scanContextName,
    )

    /** Clears rejected offline check-ins from Home: the given ones, or all of them. */
    suspend fun dismissRejections(clientScanIds: Collection<String>? = null) = queueMutex.withLock {
        _rejections.update { list -> if (clientScanIds == null) emptyList() else list.filterNot { it.clientScanId in clientScanIds } }
        cache.write(KEY_REJECTIONS, ListSerializer(ScanRejection.serializer()), _rejections.value)
    }

    suspend fun discardPending(clientScanId: String) = queueMutex.withLock {
        _pending.update { list -> list.filterNot { it.clientScanId == clientScanId } }
        cache.write(KEY_QUEUE, ListSerializer(PendingScan.serializer()), _pending.value)
    }

    suspend fun undo(eventId: String, participantEventId: String, scanContextId: String?) =
        api.undoScans(eventId, participantEventId, scanContextId)

    suspend fun clear() {
        _pending.value = emptyList(); _log.value = emptyList(); _rejections.value = emptyList()
        cache.remove(KEY_QUEUE)
        cache.remove(KEY_REJECTIONS)
    }

    companion object {
        private const val KEY_QUEUE = "scan_queue"
        private const val KEY_REJECTIONS = "scan_rejections"

        /** A live scan that takes longer than this is treated as offline. */
        const val SCAN_TIMEOUT_MILLIS = 5_000L

        private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * Turns whatever a camera/NFC tag produced into a scan input, or null if it isn't ours.
         * Accepts attend://checkin/<id>, attend:P:<id>, bare UUIDs, and Attend URLs containing a UUID.
         */
        fun parseCode(raw: String, source: String = "qr"): ScanInput? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val lower = text.lowercase()
            return when {
                lower.startsWith("attend://checkin/") -> ScanInput(participantId = text, source = source)
                lower.startsWith("attend:p:") -> text.substring(9).takeIf { it.isNotBlank() }?.let { ScanInput(participantId = it, source = source) }
                UUID_RE.matches(text) -> ScanInput(participantId = text, source = source)
                (lower.startsWith("https://attend.hackclub.com") || lower.startsWith("attend://")) ->
                    UUID_RE.find(text)?.value?.let { ScanInput(participantId = it, source = source) }
                else -> null
            }
        }
    }
}
