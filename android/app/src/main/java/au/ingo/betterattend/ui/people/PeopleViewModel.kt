package au.ingo.betterattend.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.repo.Roster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the People list renders. Filtering itself happens in [PeopleFilter] (pure, tested). */
data class PeopleUiState(
    val user: User? = null,
    /** False until the events cache has been read, so we don't flash "no event". */
    val eventsLoaded: Boolean = false,
    val event: Event? = null,
    /** null = still reading the cache. */
    val roster: List<Participant>? = null,
    /**
     * False while [roster] is only partial (the repository has no `syncedAt` for it yet, e.g. a few people
     * learned from scans before the first full sync). Counts and "X of Y here" wait until this is true.
     */
    val rosterComplete: Boolean = true,
    val contexts: List<ScanContext> = emptyList(),
    val query: String = "",
    val quick: QuickFilter = QuickFilter.All,
    val options: FilterOptions = FilterOptions(),
    val sort: SortOrder = SortOrder.Name,
    val syncing: Boolean = false,
    val lastSyncAt: String? = null,
    val syncError: String? = null,
    /** Server search results, used only when the local roster has no match. */
    val remoteResults: List<Participant>? = null,
    val remoteSearching: Boolean = false,
    val remoteError: String? = null,
    /** The "Invite someone" sheet, while open. */
    val invite: InviteState? = null,
) {
    /** Inviting is admin-only upstream (event admins, series members, global admins). */
    val canInvite: Boolean get() = event?.canViewParticipants == true && EventPermissions.canInviteParticipants(event)
}

/** Chip/filter choices survive tab switches and screen recreation for the app session. */
private object PeopleSession {
    data class Choice(val quick: QuickFilter, val options: FilterOptions, val sort: SortOrder)
    val byEvent = HashMap<String, Choice>()
}

class PeopleViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PeopleUiState())
    val state: StateFlow<PeopleUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var eventJob: Job? = null
    private var syncSeq = 0

    init {
        viewModelScope.launch {
            c.auth.state.collect { s -> _state.update { it.copy(user = (s as? AuthState.SignedIn)?.user) } }
        }
        viewModelScope.launch {
            c.events.events.collect { list -> _state.update { it.copy(eventsLoaded = list != null) } }
        }
        viewModelScope.launch {
            c.events.selectedEvent.collect { e -> onEvent(e) }
        }
    }

    private fun onEvent(event: Event?) {
        val changed = event?.id != _state.value.event?.id
        _state.update { s ->
            if (!changed) s.copy(event = event) else {
                val choice = event?.let { PeopleSession.byEvent[it.id] }
                PeopleUiState(
                    user = s.user, eventsLoaded = s.eventsLoaded, event = event,
                    roster = null, query = s.query,
                    quick = choice?.quick ?: QuickFilter.All,
                    options = choice?.options ?: FilterOptions(),
                    sort = choice?.sort ?: SortOrder.Name,
                )
            }
        }
        if (!changed || event == null) return
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            launch {
                c.events.loadContexts(event.id)
                c.events.cachedContexts(event.id)?.let { ctx -> _state.update { if (it.event?.id == event.id) it.copy(contexts = ctx) else it } }
                c.events.refreshContexts(event.id).onSuccess { ctx -> _state.update { if (it.event?.id == event.id) it.copy(contexts = ctx) else it } }
            }
            if (!event.canViewParticipants) {
                _state.update { if (it.event?.id != event.id) it else it.copy(roster = emptyList()) }
                return@launch
            }
            // Cached roster first (instant), then keep following the repository.
            val cached = c.participants.load(event.id)
            _state.update {
                if (it.event?.id != event.id) it
                else it.copy(
                    roster = cached?.participants ?: it.roster,
                    rosterComplete = cached?.syncedAt != null,
                    lastSyncAt = cached?.lastSyncAt,
                )
            }
            c.participants.rosters.collectLatest { map ->
                val r = map[event.id] ?: return@collectLatest
                _state.update { it.applyRoster(event.id, r) }
            }
        }
        if (_state.value.query.length >= 2) scheduleRemoteSearch()
    }

    /**
     * Delta sync (full every few hours, handled by the repository). Safe to call often.
     *
     * Results are only applied while [event] is still the selected one: a sync that was in flight when the
     * user switched events would otherwise briefly show the previous event's people (and tapping one 404s).
     */
    fun sync(forceFull: Boolean = false) {
        val event = _state.value.event ?: return
        if (!event.canViewParticipants || _state.value.syncing) return
        val seq = ++syncSeq
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            try {
                val r = c.participants.sync(event.id, forceFull)
                _state.update { if (it.event?.id != event.id) it else it.applyRoster(event.id, r).copy(syncError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    if (it.event?.id != event.id) it
                    else it.copy(syncError = e.friendlyMessage, roster = it.roster ?: emptyList())
                }
            } finally {
                // A new event starts with a fresh state (syncing = false), and a newer sync owns the flag once it starts.
                if (seq == syncSeq) _state.update { if (it.event?.id == event.id) it.copy(syncing = false) else it }
            }
        }
    }

    private fun PeopleUiState.applyRoster(eventId: String, r: Roster): PeopleUiState =
        if (event?.id != eventId || r.eventId != eventId) this
        else copy(roster = r.participants, rosterComplete = r.syncedAt != null, lastSyncAt = r.lastSyncAt ?: lastSyncAt)

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, remoteResults = null, remoteError = null) }
        scheduleRemoteSearch()
    }

    private fun scheduleRemoteSearch() {
        searchJob?.cancel()
        val s = _state.value
        val q = s.query.trim()
        val event = s.event ?: return
        if (q.length < 2) { _state.update { it.copy(remoteSearching = false) }; return }
        val localHit = s.roster.orEmpty().any { PeopleFilter.matchesQuery(it, q) }
        if (localHit) { _state.update { it.copy(remoteSearching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(350)
            _state.update { it.copy(remoteSearching = true) }
            try {
                val results = c.participants.search(event.id, q)
                _state.update {
                    if (it.query.trim() == q && it.event?.id == event.id) it.copy(remoteResults = results, remoteSearching = false, remoteError = null) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.event?.id == event.id) it.copy(remoteSearching = false, remoteError = e.friendlyMessage) else it }
            }
        }
    }

    // ---------------- invite ----------------

    fun openInvite() { _state.update { if (it.canInvite && it.invite == null) it.copy(invite = InviteState()) else it } }

    fun updateInvite(form: InviteState) { _state.update { s -> if (s.invite == null || s.invite.sending) s else s.copy(invite = form) } }

    fun closeInvite() { _state.update { if (it.invite?.sending == true) it else it.copy(invite = null) } }

    /** Sends the invitation, then delta-syncs so the new (invited) person shows up in the list. */
    fun sendInvite() {
        val s = _state.value
        val event = s.event ?: return
        val form = s.invite ?: return
        if (!s.canInvite || form.sending) return
        InviteLogic.validate(form.email)?.let { err -> _state.update { it.copy(invite = form.copy(error = err)) }; return }
        _state.update { it.copy(invite = form.copy(sending = true, error = null)) }
        viewModelScope.launch {
            try {
                val result = c.api.inviteParticipant(
                    event.id, form.email.trim().lowercase(), form.firstName.trim().ifBlank { null }, form.lastName.trim().ifBlank { null },
                )
                _state.update { it.copy(invite = it.invite?.copy(sending = false, result = result)) }
                // The repository serializes syncs, so this runs after any sync already in flight.
                try {
                    c.participants.sync(event.id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 409 (already invited / registered) and 422 (bad or banned email) carry a readable message.
                _state.update { it.copy(invite = it.invite?.copy(sending = false, error = e.friendlyMessage)) }
            }
        }
    }

    fun setQuick(f: QuickFilter) { _state.update { it.copy(quick = f) }; remember() }
    fun setOptions(o: FilterOptions) { _state.update { it.copy(options = o) }; remember() }
    fun setSort(s: SortOrder) { _state.update { it.copy(sort = s) }; remember() }

    private fun remember() {
        val s = _state.value
        s.event?.let { PeopleSession.byEvent[it.id] = PeopleSession.Choice(s.quick, s.options, s.sort) }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PeopleViewModel(c) as T
    }
}
