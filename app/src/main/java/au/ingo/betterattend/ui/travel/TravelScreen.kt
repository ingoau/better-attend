package au.ingo.betterattend.ui.travel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.PollWhileVisible
import au.ingo.betterattend.ui.nav.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Everything the travel list needs, independent of search/filter UI state. */
data class TravelUiState(
    val event: Event? = null,
    val calendar: TravelCalendar? = null,
    val refreshing: Boolean = false,
    /** Last refresh failure (cached data, if any, is still shown). */
    val error: String? = null,
    val lastUpdated: Instant? = null,
) {
    val loading: Boolean get() = calendar == null && error == null
}

private data class LoadState(
    val eventId: String? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Instant? = null,
)

class TravelViewModel(private val c: AppContainer) : ViewModel() {
    private val load = MutableStateFlow(LoadState())

    val state: StateFlow<TravelUiState> = combine(c.events.selectedEvent, c.travel.calendars, load) { event, cals, l ->
        val sameEvent = l.eventId == event?.id
        TravelUiState(
            event = event,
            calendar = event?.let { cals[it.id] },
            refreshing = sameEvent && l.refreshing,
            error = if (sameEvent) l.error else null,
            lastUpdated = if (sameEvent) l.lastUpdated else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TravelUiState())

    /** Shows the cached calendar straight away, then refreshes from the server. */
    suspend fun refresh(eventId: String) {
        if (load.value.eventId != eventId) load.value = LoadState(eventId)
        c.travel.load(eventId)
        load.update { it.copy(refreshing = true) }
        val result = c.travel.refresh(eventId)
        load.update {
            if (it.eventId != eventId) it
            else result.fold(
                onSuccess = { _ -> it.copy(refreshing = false, error = null, lastUpdated = Instant.now()) },
                onFailure = { e -> it.copy(refreshing = false, error = e.friendlyMessage) },
            )
        }
    }

    fun refreshAsync(eventId: String) { viewModelScope.launch { refresh(eventId) } }
}

@Composable
fun TravelScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm: TravelViewModel = viewModel(factory = viewModelFactory { initializer { TravelViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TravelFilter.All) }
    var mode by rememberSaveable { mutableStateOf<TravelMode?>(null) }

    val event = state.event
    // The server caches this for 5 min but busts it on pickup/check-in scans, so a minute is a good balance.
    if (event != null && event.travelEnabled) {
        PollWhileVisible(event.id, 60_000) { vm.refresh(event.id) }
    }

    TravelContent(
        state = state,
        query = query,
        onQueryChange = { query = it },
        filter = filter,
        onFilterChange = { filter = it },
        mode = mode,
        onModeChange = { mode = it },
        onRefresh = { event?.let { vm.refreshAsync(it.id) } },
        onOpen = { entry ->
            val pe = entry.participantEventId
            if (event != null && pe != null) nav.openParticipant(event.id, pe)
        },
        onPickEvent = nav::openEventPicker,
    )
}
