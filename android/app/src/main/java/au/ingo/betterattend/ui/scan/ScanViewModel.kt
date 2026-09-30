package au.ingo.betterattend.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.EventRepository
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanRepository
import au.ingo.betterattend.scan.FeedbackKind
import au.ingo.betterattend.scan.NfcParseResult
import au.ingo.betterattend.scan.RosterSearch
import au.ingo.betterattend.scan.SameCodeGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives the scanner (and kiosk): event + scan context selection, the same-code gate, submitting
 * scans with an instant "Checking…" card, undo, the offline queue and the find-person search.
 *
 * @param fixedEventId kiosk mode: always this event instead of the app-wide selected one.
 * @param lockedContextId kiosk mode: always this context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModel(
    private val container: AppContainer,
    private val fixedEventId: String? = null,
    private val lockedContextId: String? = null,
) : ViewModel() {
    private val gate = SameCodeGate()

    val event: StateFlow<Event?> = (
        if (fixedEventId != null) container.events.events.map { list -> list?.firstOrNull { it.id == fixedEventId } }
        else container.events.selectedEvent
    ).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True until the events list has been read from cache at least once. */
    val eventsLoading: StateFlow<Boolean> = container.events.events.map { it == null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val contextErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    val contexts: StateFlow<List<ScanContext>?> = combine(event, container.events.contexts) { e, map -> e?.let { map[it.id] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val contextsError: StateFlow<String?> = combine(event, contextErrors) { e, errs -> e?.let { errs[it.id] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedContextId: StateFlow<String?> = combine(event, contexts, container.settings.settings) { e, list, s ->
        when {
            e == null || list.isNullOrEmpty() -> null
            lockedContextId != null -> list.firstOrNull { it.id == lockedContextId }?.id
            else -> list.firstOrNull { it.id == s.selectedContexts[e.id] }?.id ?: EventRepository.defaultContext(list)?.id
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _card = MutableStateFlow<ScanCard?>(null)
    val card: StateFlow<ScanCard?> = _card.asStateFlow()

    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Sound + haptic cues, one per final outcome. */
    private val _feedback = MutableSharedFlow<FeedbackKind>(extraBufferCapacity = 8)
    val feedback: SharedFlow<FeedbackKind> = _feedback

    /**
     * Haptic cues for staff actions that aren't scans (undo, a manual "Sync now"): true = it
     * worked, false = it didn't. Scan outcomes use [feedback] instead, so nothing buzzes twice.
     */
    private val _actionResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val actionResults: SharedFlow<Boolean> = _actionResults

    /** One-off messages for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            event.map { it?.id }.distinctUntilChanged().collect { id ->
                gate.reset()
                _card.value = null
                _search.value = SearchUiState()
                if (id != null) onEventSelected(event.value!!)
            }
        }
    }

    private fun onEventSelected(e: Event) {
        viewModelScope.launch {
            container.events.loadContexts(e.id)
            refreshContexts(e.id)
        }
        viewModelScope.launch {
            container.participants.load(e.id)
            // Background roster refresh gives instant names/headshots while "Checking…" and offline search.
            if (e.canViewParticipants) runCatching { container.participants.sync(e.id) }
        }
    }

    fun refreshContexts(eventId: String? = event.value?.id) {
        eventId ?: return
        viewModelScope.launch {
            container.events.refreshContexts(eventId)
                .onSuccess { contextErrors.update { it - eventId } }
                .onFailure { err -> contextErrors.update { it + (eventId to err.friendlyMessage) } }
        }
    }

    fun selectContext(id: String) {
        val e = event.value ?: return
        if (lockedContextId != null || id == selectedContextId.value) return
        gate.reset()
        viewModelScope.launch { container.settings.setSelectedContext(e.id, id) }
    }

    private val ready: Boolean get() = event.value != null && (contexts.value != null || contextsError.value != null)

    // ---------- inputs ----------

    /** Every decoded QR value from a camera frame. */
    fun onCameraCodes(values: List<String>) {
        if (!ready) return
        for (raw in values) {
            val key = "qr:$raw"
            if (!gate.offer(key)) continue
            val input = ScanRepository.parseCode(raw, "qr")
            if (input == null) {
                show(ScanCard(newKey(), ResultKind.Rejected, "Not an Attend code", "This QR code isn't an Attend ticket or badge.", gateKey = key))
                _feedback.tryEmit(FeedbackKind.Reject)
            } else submit(input, key)
        }
    }

    fun onNfc(result: NfcParseResult) {
        if (event.value == null) return
        when (result) {
            is NfcParseResult.Input -> {
                val key = "nfc:" + (result.input.badgeToken ?: result.input.participantId)
                if (!ready || !gate.offer(key)) return
                submit(result.input, key)
            }
            is NfcParseResult.Unrecognised -> {
                if (!gate.offer("nfc-error:${result.message}")) return
                show(ScanCard(newKey(), ResultKind.Rejected, "Not an Attend badge", result.message))
                _feedback.tryEmit(FeedbackKind.Reject)
            }
        }
    }

    /** Manual check-in from the find-person sheet; bypasses the gate (a deliberate tap). */
    fun checkInManually(participantEventId: String) = submit(ScanInput(participantId = participantEventId, source = "manual"), null)

    fun submitDirect(input: ScanInput) = submit(input.copy(source = "manual"), null)

    fun retry(card: ScanCard) {
        card.input?.let { submit(it, card.gateKey) }
        if (card.contextId == null) refreshContexts()
    }

    private fun submit(input: ScanInput, gateKey: String?) {
        val e = event.value ?: return
        if (!ready) {
            show(ScanCard(newKey(), ResultKind.Rejected, "Still loading", "Checkpoints are still loading. Try again in a moment.", retryable = true, input = input))
            return
        }
        val ctx = contexts.value?.firstOrNull { it.id == selectedContextId.value }
        val key = newKey()
        val cached = container.participants.roster(e.id)?.find(input.badgeToken ?: input.participantId.orEmpty())
        show(ScanCard(key, ResultKind.Checking, "Checking…", participant = cached, contextName = ctx?.name, contextId = ctx?.id, gateKey = gateKey, input = input))
        _inFlight.update { it + 1 }
        viewModelScope.launch {
            try {
                val outcome = container.scans.submit(e.id, input, ctx?.id, ctx?.name)
                val result = outcome.toCard(key, ctx, e.timezone, gateKey, input)
                result.kind.feedback()?.let { _feedback.tryEmit(it) }
                _card.update { current -> if (current?.key == key) result else current }
                if (result.kind == ResultKind.Rejected && result.message?.contains("context", ignoreCase = true) == true) refreshContexts(e.id)
            } finally {
                _inFlight.update { it - 1 }
            }
        }
    }

    private fun show(card: ScanCard) { _card.value = card }

    fun dismiss() {
        _card.value?.gateKey?.let(gate::release)
        _card.value = null
    }

    /** Clears the card without releasing the gate (kiosk auto-hide: the ticket may still be in frame). */
    fun hide(key: String) = _card.update { if (it?.key == key) null else it }

    fun undo(card: ScanCard) {
        val e = event.value ?: return
        val peid = card.participant?.participantEventId ?: return
        val ctxId = card.contextId ?: return
        _card.update { if (it?.key == card.key) it.copy(busy = true) else it }
        viewModelScope.launch {
            runCatching {
                container.scans.undo(e.id, peid, ctxId)
                container.participants.applyUndo(e.id, peid, ctxId, contexts.value.orEmpty())
            }.onSuccess {
                _actionResults.tryEmit(true)
                card.gateKey?.let(gate::release)
                _card.update {
                    if (it?.key == card.key) it.copy(kind = ResultKind.Undone, title = "Scan undone",
                        message = "${card.participant?.name ?: "They"} is no longer marked as scanned here", canUndo = false, busy = false, retryable = false)
                    else it
                }
            }.onFailure { err ->
                _actionResults.tryEmit(false)
                _card.update { if (it?.key == card.key) it.copy(busy = false) else it }
                _messages.tryEmit("Couldn't undo: ${err.friendlyMessage}")
            }
        }
    }

    // ---------- offline queue ----------

    /** @param manual the user tapped "Sync now" (gets a success/failure cue); false for quiet retries. */
    fun syncNow(manual: Boolean = true) {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                val left = container.scans.flush()
                if (manual) _actionResults.tryEmit(left == 0)
                if (left > 0) _messages.tryEmit("Still offline. $left ${if (left == 1) "scan is" else "scans are"} waiting to sync.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (manual) _actionResults.tryEmit(false)
                _messages.tryEmit(e.friendlyMessage)
            } finally {
                _syncing.value = false
            }
        }
    }

    /** Called whenever the scanner resumes: quietly retry anything queued. */
    fun onResume() {
        if (container.scans.pending.value.isNotEmpty()) syncNow(manual = false)
    }

    fun discard(clientScanId: String) {
        viewModelScope.launch { container.scans.discardPending(clientScanId) }
    }

    // ---------- find person ----------

    fun setQuery(q: String) {
        val e = event.value
        val canSearch = e?.canViewParticipants != false
        val roster = e?.let { container.participants.roster(it.id) }?.participants.orEmpty()
        val local = if (canSearch) RosterSearch.filter(roster, q) else emptyList()
        _search.value = SearchUiState(
            query = q,
            results = local,
            directInput = RosterSearch.directInput(q),
            canSearch = canSearch,
            rosterEmpty = roster.isEmpty(),
            remoteLoading = canSearch && q.trim().length >= 2,
        )
        searchJob?.cancel()
        if (e == null || !canSearch || q.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(300)
            val remote = runCatching { container.participants.search(e.id, q.trim()) }
            _search.update { s ->
                if (s.query != q) s
                else remote.fold(
                    onSuccess = { found ->
                        val known = s.results.map { it.participantEventId }.toSet()
                        s.copy(results = s.results + found.filter { it.participantEventId !in known }, remoteLoading = false, remoteError = null)
                    },
                    onFailure = { err -> s.copy(remoteLoading = false, remoteError = err.friendlyMessage) },
                )
            }
        }
    }

    private fun newKey() = UUID.randomUUID().toString()

    class Factory(
        private val container: AppContainer,
        private val fixedEventId: String? = null,
        private val lockedContextId: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScanViewModel(container, fixedEventId, lockedContextId) as T
    }
}
