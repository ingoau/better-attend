package au.ingo.betterattend.data.repo

import au.ingo.betterattend.data.api.ApiException
import au.ingo.betterattend.data.api.AttendApi
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.api.isTransient
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanResult
import au.ingo.betterattend.data.store.JsonCache
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    data class Scanned(override val clientScanId: String, val result: ScanResult, override val participant: Participant?) : ScanOutcome
    data class AlreadyScanned(override val clientScanId: String, val result: ScanResult, override val participant: Participant?) : ScanOutcome
    /** Couldn't reach the server; saved and will sync automatically. */
    data class Queued(override val clientScanId: String, val pending: PendingScan, override val participant: Participant?, val reason: String) : ScanOutcome
    data class Failed(override val clientScanId: String, val message: String, override val participant: Participant?, val notFound: Boolean) : ScanOutcome
}

/** Log line for the scanner's "recent scans" sheet (kept in memory for this app session). */
data class ScanLogEntry(val outcome: ScanOutcome, val contextName: String?, val at: String)

class ScanRepository(
    private val api: AttendApi,
    private val cache: JsonCache,
    private val participants: ParticipantRepository,
    private val scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<List<PendingScan>>(emptyList())
    val pending: StateFlow<List<PendingScan>> = _pending.asStateFlow()

    private val _log = MutableStateFlow<List<ScanLogEntry>>(emptyList())
    val log: StateFlow<List<ScanLogEntry>> = _log.asStateFlow()

    private val queueMutex = Mutex()

    /** Set by the app to schedule a WorkManager flush when something is queued. */
    var onQueued: () -> Unit = {}

    init {
        scope.launch { _pending.value = cache.read(KEY_QUEUE, ListSerializer(PendingScan.serializer())).orEmpty() }
    }

    suspend fun submit(eventId: String, input: ScanInput, scanContextId: String?, scanContextName: String?): ScanOutcome {
        val clientScanId = UUID.randomUUID().toString()
        val scannedAt = Time.nowIso()
        val known = participants.load(eventId)?.let { r -> (input.badgeToken ?: input.participantId)?.let(r::find) }
        val outcome = try {
            val result = api.createScan(
                eventId = eventId,
                participantId = input.participantId,
                badgeToken = input.badgeToken,
                scanContextId = scanContextId,
                source = input.source.takeIf { it == "manual" },
                clientScanId = clientScanId,
                scannedAt = scannedAt,
            )
            val p = result.participant?.let { ParticipantRepository.mergeKeepingDetail(known, it) } ?: known
            result.participant?.let { participants.upsert(eventId, p!!) }
            if (result.isAlreadyScanned) ScanOutcome.AlreadyScanned(clientScanId, result, p)
            else ScanOutcome.Scanned(clientScanId, result, p)
        } catch (e: Exception) {
            if (e.isTransient) {
                val pending = PendingScan(clientScanId, eventId, scanContextId, scanContextName, input, scannedAt, 1, e.friendlyMessage, known?.name)
                enqueue(pending)
                ScanOutcome.Queued(clientScanId, pending, known, e.friendlyMessage)
            } else {
                val notFound = (e as? ApiException)?.isNotFound == true
                ScanOutcome.Failed(
                    clientScanId,
                    if (notFound) "Not registered for this event" else e.friendlyMessage,
                    known,
                    notFound,
                )
            }
        }
        _log.update { (listOf(ScanLogEntry(outcome, scanContextName, scannedAt)) + it).take(100) }
        return outcome
    }

    private suspend fun enqueue(p: PendingScan) = queueMutex.withLock {
        _pending.update { it + p }
        cache.write(KEY_QUEUE, ListSerializer(PendingScan.serializer()), _pending.value)
        onQueued()
    }

    /**
     * Sends queued scans oldest first. `client_scan_id` makes retries idempotent. Returns the number
     * still pending; stops early on a transient failure so we don't burn the rate limit.
     */
    suspend fun flush(): Int = queueMutex.withLock {
        val remaining = _pending.value.toMutableList()
        val iterator = remaining.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            try {
                val result = api.createScan(
                    eventId = p.eventId,
                    participantId = p.input.participantId,
                    badgeToken = p.input.badgeToken,
                    scanContextId = p.scanContextId,
                    source = p.input.source.takeIf { it == "manual" },
                    clientScanId = p.clientScanId,
                    scannedAt = p.scannedAt,
                )
                result.participant?.let { participants.upsert(p.eventId, it) }
                iterator.remove()
            } catch (e: Exception) {
                if (e.isTransient) break
                // Permanent failure (e.g. not registered): drop it but keep a record in the log.
                iterator.remove()
                _log.update {
                    listOf(ScanLogEntry(ScanOutcome.Failed(p.clientScanId, "Offline scan rejected: ${e.friendlyMessage}", null, false), p.scanContextName, Time.nowIso())) + it
                }
            }
        }
        _pending.value = remaining
        cache.write(KEY_QUEUE, ListSerializer(PendingScan.serializer()), remaining)
        remaining.size
    }

    suspend fun discardPending(clientScanId: String) = queueMutex.withLock {
        _pending.update { list -> list.filterNot { it.clientScanId == clientScanId } }
        cache.write(KEY_QUEUE, ListSerializer(PendingScan.serializer()), _pending.value)
    }

    suspend fun undo(eventId: String, participantEventId: String, scanContextId: String?) =
        api.undoScans(eventId, participantEventId, scanContextId)

    suspend fun clear() {
        _pending.value = emptyList(); _log.value = emptyList()
        cache.remove(KEY_QUEUE)
    }

    companion object {
        private const val KEY_QUEUE = "scan_queue"

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
