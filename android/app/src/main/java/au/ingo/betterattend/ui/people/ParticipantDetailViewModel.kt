package au.ingo.betterattend.ui.people

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.ApiException
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.Note
import au.ingo.betterattend.data.model.NoteAuthor
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.EventRepository
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanOutcome
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface NfcWriteState {
    data object Idle : NfcWriteState
    data object Preparing : NfcWriteState
    data class Waiting(val token: String, val hasSlackLink: Boolean) : NfcWriteState
    data object Writing : NfcWriteState
    data object Success : NfcWriteState
    data object Unsupported : NfcWriteState
    data object Disabled : NfcWriteState
    data class Failed(val message: String, val retryable: Boolean = true) : NfcWriteState
}

/** A note plus its optimistic-send state. */
data class NoteItem(val note: Note, val pending: Boolean = false, val failed: Boolean = false)

enum class DetailBusy { CheckingIn, Undoing, UpdatingStatus, ResettingBadge }

/** Haptic cues for results of actions on the detail screen (played by the screen, which knows the Haptics setting). */
enum class DetailCue { Confirm, Reject, Click }

data class DetailUiState(
    val event: Event? = null,
    val participant: Participant? = null,
    /** True once the full `show` payload has arrived (sections beyond the roster copy). */
    val detailLoaded: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val notes: List<NoteItem>? = null,
    val notesError: String? = null,
    /** Notes endpoint refused (403/404): hide the section rather than nag. */
    val notesHidden: Boolean = false,
    val contexts: List<ScanContext> = emptyList(),
    val busy: DetailBusy? = null,
    val nfc: NfcWriteState = NfcWriteState.Idle,
) {
    val canViewPii get() = event?.canViewParticipantPii == true
    val canViewSensitive get() = event?.canViewSensitiveData == true
    /** PATCH participants is limited to direct edit roles (safeguarding leads and series-only members get 403). */
    val canChangeStatus get() = EventPermissions.canEditParticipant(event)
    val checkInContexts: List<ScanContext> get() = contexts.filter { it.checksIn }.ifEmpty { contexts }
    val defaultCheckInContext: ScanContext? get() = EventRepository.defaultContext(checkInContexts)
}

const val MAX_NOTE_LENGTH = 1000
val NOTE_TYPES = listOf("ops", "safeguarding", "logistical")
val NOTE_SENSITIVITIES = listOf("normal", "restricted")

/** Client-side validation (the API 500s on bad enums). Returns an error message or null. */
fun validateNote(content: String, type: String, sensitivity: String): String? = when {
    content.isBlank() -> "Write something first."
    content.trim().length > MAX_NOTE_LENGTH -> "Notes can be up to $MAX_NOTE_LENGTH characters."
    type !in NOTE_TYPES -> "Pick a note type."
    sensitivity !in NOTE_SENSITIVITIES -> "Pick a sensitivity."
    else -> null
}

class ParticipantDetailViewModel(
    private val c: AppContainer,
    private val eventId: String,
    private val participantEventId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-off snackbar messages. */
    val messages = _messages.receiveAsFlow()

    private val _cues = Channel<DetailCue>(Channel.BUFFERED)
    /** One-off haptic cues: confirm / reject when an action's result arrives, click when a badge is detected. */
    val cues = _cues.receiveAsFlow()
    private fun cue(ok: Boolean) { _cues.trySend(if (ok) DetailCue.Confirm else DetailCue.Reject) }

    init {
        viewModelScope.launch {
            c.events.events.collect { list -> _state.update { it.copy(event = list?.firstOrNull { e -> e.id == eventId } ?: it.event) } }
        }
        viewModelScope.launch {
            // Roster copy first (instant), then keep following it: scans/undo/detail all upsert there.
            c.participants.load(eventId)
            c.participants.rosters.collect { map ->
                map[eventId]?.byEventId?.get(participantEventId)?.let { p -> _state.update { it.copy(participant = p) } }
            }
        }
        viewModelScope.launch {
            c.events.loadContexts(eventId)
            c.events.cachedContexts(eventId)?.let { ctx -> _state.update { it.copy(contexts = ctx) } }
            c.events.refreshContexts(eventId).onSuccess { ctx -> _state.update { it.copy(contexts = ctx) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val full = c.participants.detail(eventId, participantEventId)
                _state.update { it.copy(participant = it.participant ?: full, detailLoaded = true, loading = false, error = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.friendlyMessage) }
            }
        }
        loadNotes()
    }

    private fun loadNotes() {
        viewModelScope.launch {
            try {
                val notes = c.api.notes(eventId, participantEventId)
                _state.update { s ->
                    val pending = s.notes.orEmpty().filter { it.pending || it.failed }
                    s.copy(notes = pending + notes.map { NoteItem(it) }, notesError = null, notesHidden = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val hidden = (e as? ApiException)?.let { it.isForbidden || it.isNotFound } == true
                _state.update { it.copy(notesHidden = hidden, notesError = if (hidden) null else e.friendlyMessage) }
            }
        }
    }

    fun checkIn(context: ScanContext?) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = DetailBusy.CheckingIn) }
            val ctx = context ?: _state.value.defaultCheckInContext
            // A deliberate check-in from their page: staff can see their status, so this overrides the admission rules.
            val outcome = c.scans.submit(eventId, ScanInput(participantId = participantEventId, source = "manual"), ctx?.id, ctx?.name,
                ctx?.checksIn ?: true, enforceAdmission = false)
            _state.update { it.copy(busy = null) }
            // Already scanned there = nothing changed, so it gets the "didn't happen" cue.
            cue(outcome is ScanOutcome.Scanned || outcome is ScanOutcome.Queued)
            val tz = _state.value.event?.timezone
            _messages.send(
                when (outcome) {
                    is ScanOutcome.Scanned -> "Checked in${ctx?.let { " at ${it.name}" } ?: ""}"
                    is ScanOutcome.AlreadyScanned ->
                        "Already scanned${ctx?.let { " at ${it.name}" } ?: ""}${Time.time(outcome.result.firstScannedAt, tz)?.let { " at $it" } ?: ""}"
                    is ScanOutcome.Queued -> "You're offline. Check-in saved and will sync automatically."
                    is ScanOutcome.Failed -> outcome.message
                    is ScanOutcome.Rejected -> "Not checked in: ${outcome.reason.short}" + if (outcome.offline) " (checked offline)" else ""
                }
            )
        }
    }

    /** [scanContextId] null = undo every context. */
    fun undo(scanContextId: String?) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = DetailBusy.Undoing) }
            try {
                val res = c.scans.undo(eventId, participantEventId, scanContextId)
                c.participants.applyUndo(eventId, participantEventId, scanContextId, _state.value.contexts)
                val where = scanContextId?.let { id -> _state.value.contexts.firstOrNull { it.id == id }?.name }
                cue(true)
                _messages.send(
                    buildString {
                        append(if (res.deletedScans == 1) "Removed 1 scan" else "Removed ${res.deletedScans} scans")
                        if (where != null) append(" at $where")
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                cue(false)
                _messages.send("Couldn't undo: ${e.friendlyMessage}")
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun setWithdrawn(withdrawn: Boolean) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = DetailBusy.UpdatingStatus) }
            try {
                val updated = c.api.updateParticipantStatus(eventId, participantEventId, if (withdrawn) "withdrawn" else "in_progress")
                c.participants.upsert(eventId, updated)
                cue(true)
                _messages.send(if (withdrawn) "Marked as withdrawn" else "Reinstated")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                cue(false)
                _messages.send(e.friendlyMessage)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    fun addNote(content: String, type: String, sensitivity: String): Boolean {
        validateNote(content, type, sensitivity)?.let { msg -> cue(false); viewModelScope.launch { _messages.send(msg) }; return false }
        val user = (c.auth.state.value as? AuthState.SignedIn)?.user
        val local = Note(
            id = "local-" + UUID.randomUUID(), content = content.trim(), noteType = type, sensitivity = sensitivity,
            createdAt = Time.nowIso(), author = NoteAuthor(id = user?.id, name = user?.displayName, email = user?.email),
        )
        _state.update { it.copy(notes = listOf(NoteItem(local, pending = true)) + it.notes.orEmpty()) }
        send(local)
        return true
    }

    fun retryNote(localId: String) {
        val item = _state.value.notes?.firstOrNull { it.note.id == localId } ?: return
        _state.update { s -> s.copy(notes = s.notes?.map { if (it.note.id == localId) it.copy(pending = true, failed = false) else it }) }
        send(item.note)
    }

    fun discardNote(localId: String) {
        _state.update { s -> s.copy(notes = s.notes?.filterNot { it.note.id == localId }) }
    }

    private fun send(local: Note) {
        viewModelScope.launch {
            try {
                val saved = c.api.createNote(eventId, participantEventId, local.content, local.noteType, local.sensitivity)
                _state.update { s -> s.copy(notes = s.notes?.map { if (it.note.id == local.id) NoteItem(saved) else it }) }
                cue(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { s -> s.copy(notes = s.notes?.map { if (it.note.id == local.id) it.copy(pending = false, failed = true) else it }) }
                cue(false)
                _messages.send("Note not saved: ${e.friendlyMessage}")
            }
        }
    }

    // ---------------- NFC badge ----------------

    fun startNfcWrite(availability: NfcAvailability) {
        when (availability) {
            NfcAvailability.Unsupported -> { _state.update { it.copy(nfc = NfcWriteState.Unsupported) }; return }
            NfcAvailability.Disabled -> { _state.update { it.copy(nfc = NfcWriteState.Disabled) }; return }
            NfcAvailability.Ready -> Unit
        }
        viewModelScope.launch {
            _state.update { it.copy(nfc = NfcWriteState.Preparing) }
            try {
                val badge = c.api.nfcEnsure(eventId, participantEventId)
                val slack = _state.value.participant?.slackUserId
                _state.update { it.copy(nfc = NfcWriteState.Waiting(badge.badgeToken, !slack.isNullOrBlank())) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = if ((e as? ApiException)?.status == 422) e.message ?: "NFC badges are not enabled for this event" else e.friendlyMessage
                _state.update { it.copy(nfc = NfcWriteState.Failed(msg, retryable = (e as? ApiException)?.status != 422)) }
                cue(false)
            }
        }
    }

    /** Called from the NFC binder thread when a tag appears while waiting. */
    fun onTag(tag: Tag) {
        val waiting = _state.value.nfc as? NfcWriteState.Waiting ?: return
        _state.update { it.copy(nfc = NfcWriteState.Writing) }
        _cues.trySend(DetailCue.Click) // "Got the badge, hold still."
        viewModelScope.launch {
            try {
                val message = BadgeNdef.buildMessage(waiting.token, _state.value.participant?.slackUserId)
                withContext(Dispatchers.IO) { NfcBadgeWriter.writeAndVerify(tag, message, waiting.token) }
                val confirmed = c.api.nfcConfirm(eventId, participantEventId, waiting.token)
                _state.value.participant?.let { p ->
                    c.participants.upsert(eventId, p.copy(nfcBadgeToken = confirmed.badgeToken, nfcBadgeAssigned = true))
                }
                _state.update { it.copy(nfc = NfcWriteState.Success) }
                cue(true)
            } catch (e: BadgeWriteException) {
                _state.update { it.copy(nfc = NfcWriteState.Failed(e.message ?: "Couldn't write the badge.", e.retryable)) }
                cue(false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(nfc = NfcWriteState.Failed("Badge written, but Attend didn't confirm it: ${e.friendlyMessage}")) }
                cue(false)
            }
        }
    }

    /** Back to waiting for a tag after a retryable failure (keeps the ensured token). */
    fun retryNfc(availability: NfcAvailability) = startNfcWrite(availability)

    fun dismissNfc() { _state.update { it.copy(nfc = NfcWriteState.Idle) } }

    fun resetBadge() {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = DetailBusy.ResettingBadge) }
            try {
                val res = c.api.nfcReset(eventId, participantEventId)
                _state.value.participant?.let { p -> c.participants.upsert(eventId, p.copy(nfcBadgeToken = res.badgeToken, nfcBadgeAssigned = false)) }
                cue(true)
                _messages.send("Badge reset. The old badge no longer works.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                cue(false)
                _messages.send(if ((e as? ApiException)?.status == 422) e.message ?: "NFC badges are not enabled for this event" else e.friendlyMessage)
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    class Factory(private val c: AppContainer, private val eventId: String, private val participantEventId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ParticipantDetailViewModel(c, eventId, participantEventId) as T
    }
}
