package au.ingo.betterattend.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
)

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

    init {
        viewModelScope.launch {
            c.auth.state.collect { s -> _state.update { it.copy(user = (s as? AuthState.SignedIn)?.user) } }
        }
        viewModelScope.launch {
            c.events.events.collect { list -> _state.update { it.copy(eventsLoaded = list != null) } }
        }
        viewModelScope.launch {
            c.events.selectedEvent.distinctUntilChangedBy { it?.id to it }.collect { e -> onEvent(e) }
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
                c.events.cachedContexts(event.id)?.let { ctx -> _state.update { it.copy(contexts = ctx) } }
                c.events.refreshContexts(event.id).onSuccess { ctx -> _state.update { it.copy(contexts = ctx) } }
            }
            if (!event.canViewParticipants) {
                _state.update { it.copy(roster = emptyList()) }
                return@launch
            }
            // Cached roster first (instant), then keep following the repository.
            val cached = c.participants.load(event.id)
            _state.update { it.copy(roster = cached?.participants ?: it.roster, lastSyncAt = cached?.lastSyncAt) }
            c.participants.rosters.collectLatest { map ->
                val r = map[event.id] ?: return@collectLatest
                _state.update { it.copy(roster = r.participants, lastSyncAt = r.lastSyncAt ?: it.lastSyncAt) }
            }
        }
        if (_state.value.query.length >= 2) scheduleRemoteSearch()
    }

    /** Delta sync (full every few hours, handled by the repository). Safe to call often. */
    fun sync(forceFull: Boolean = false) {
        val event = _state.value.event ?: return
        if (!event.canViewParticipants || _state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            try {
                val r = c.participants.sync(event.id, forceFull)
                _state.update { it.copy(syncing = false, syncError = null, roster = r.participants, lastSyncAt = r.lastSyncAt) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(syncing = false, syncError = e.friendlyMessage, roster = it.roster ?: emptyList()) }
            }
        }
    }

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
                _state.update { if (it.query.trim() == q) it.copy(remoteResults = results, remoteSearching = false, remoteError = null) else it }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(remoteSearching = false, remoteError = e.friendlyMessage) }
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
