package au.ingo.betterattend.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.PollWhileVisible
import au.ingo.betterattend.ui.nav.AppNavigator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Everything Home renders. Screenshot tests build this directly from sample data. */
data class DashboardState(
    val user: User? = null,
    /** null while the cached event list is still being read. */
    val events: List<Event>? = null,
    val event: Event? = null,
    /** null until the cached roster is read / the first sync finishes. */
    val roster: Roster? = null,
    val contexts: List<ScanContext> = emptyList(),
    val travel: TravelCalendar? = null,
    /** Latest scans; only fetched for roles that can't see participants. */
    val scans: List<Scan>? = null,
    /** Any sync in flight (drives the quiet "Syncing…" line). */
    val refreshing: Boolean = false,
    /** A sync the user asked for (drives the pull-to-refresh spinner, so background polls stay quiet). */
    val userRefreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Instant? = null,
    val pendingScans: Int = 0,
) {
    val canViewParticipants: Boolean get() = event?.canViewParticipants == true
}

private data class Local(
    val eventId: String? = null,
    val refreshing: Boolean = false,
    val userRefreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Instant? = null,
    val lastAttempt: Instant? = null,
    val scans: List<Scan>? = null,
    val contextsFetched: Boolean = false,
)

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(Local())

    private val repoState = combine(
        c.events.selectedEvent, c.participants.rosters, c.events.contexts, c.travel.calendars, c.scans.pending,
    ) { event, rosters, contexts, travel, pending ->
        DashboardState(
            event = event,
            roster = event?.let { rosters[it.id] },
            contexts = event?.let { contexts[it.id] }.orEmpty(),
            travel = event?.takeIf { it.travelEnabled }?.let { travel[it.id] },
            pendingScans = pending.count { it.eventId == event?.id },
        )
    }

    val state: StateFlow<DashboardState> = combine(repoState, c.events.events, c.auth.state, local) { s, events, auth, l ->
        val same = l.eventId != null && l.eventId == s.event?.id
        s.copy(
            user = (auth as? AuthState.SignedIn)?.user,
            events = events,
            scans = if (same) l.scans else null,
            refreshing = same && l.refreshing,
            userRefreshing = same && l.refreshing && l.userRefreshing,
            error = if (same) l.error else null,
            lastUpdated = if (same) l.lastUpdated else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    /**
     * Shows cached data immediately, then syncs. The roster sync is a cheap `updated_since` delta after the
     * first time. [force] skips the throttle (pull-to-refresh); polling calls within 20 s of the last one are skipped
     * so bouncing between tabs doesn't burn the shared per-IP rate limit.
     */
    suspend fun refresh(event: Event, force: Boolean) {
        val id = event.id
        if (local.value.eventId != id) local.value = Local(eventId = id)
        val last = local.value.lastAttempt
        if (!force && last != null && Duration.between(last, Instant.now()) < Duration.ofSeconds(20)) return
        if (local.value.refreshing) {
            // A sync is already running: let a pull show its spinner until that one finishes.
            if (force) local.update { it.copy(userRefreshing = true) }
            return
        }

        c.participants.load(id)
        c.events.loadContexts(id)
        if (event.travelEnabled) c.travel.load(id)

        local.update { it.copy(refreshing = true, userRefreshing = force, lastAttempt = Instant.now()) }
        val errors = mutableListOf<Throwable>()
        var scans: List<Scan>? = null
        coroutineScope {
            if (force) launch { c.events.refresh() }
            if (event.canViewParticipants) {
                launch { runCatching { c.participants.sync(id) }.onFailure { errors += it } }
            } else {
                launch { runCatching { c.api.scans(id).scans }.onSuccess { scans = it }.onFailure { errors += it } }
            }
            if (force || !local.value.contextsFetched || c.events.cachedContexts(id) == null) {
                launch { c.events.refreshContexts(id).onSuccess { local.update { l -> l.copy(contextsFetched = true) } } }
            }
            if (event.travelEnabled) launch { c.travel.refresh(id).onFailure { errors += it } }
        }
        local.update {
            if (it.eventId != id) it
            else it.copy(
                refreshing = false,
                userRefreshing = false,
                error = errors.firstOrNull()?.friendlyMessage,
                lastUpdated = if (errors.isEmpty()) Instant.now() else it.lastUpdated,
                scans = scans ?: it.scans,
            )
        }
    }

    fun refreshAsync(event: Event, force: Boolean = true) { viewModelScope.launch { refresh(event, force) } }

    fun refreshEvents() { viewModelScope.launch { c.events.refresh() } }
}

@Composable
fun DashboardScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val event = state.event

    // Live-ish numbers: delta-sync every 45 s while Home is on screen; paused when it isn't.
    if (event != null) {
        PollWhileVisible(event.id to event.canViewParticipants, 45_000) { vm.refresh(event, force = false) }
    }

    // Keeps "Updated 2 min ago", countdowns and "in the last hour" honest between syncs.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }

    DashboardContent(
        state = state,
        now = now,
        onRefresh = { if (event != null) vm.refreshAsync(event) else vm.refreshEvents() },
        onPickEvent = nav::openEventPicker,
        onAccount = nav::openSettings,
        onSwitchTab = nav::switchTab,
        onOpenParticipant = { pe -> event?.let { nav.openParticipant(it.id, pe) } },
        onAnnounce = { event?.let { nav.openBlasts(it.id) } },
        onKiosk = { event?.let { nav.openKiosk(it.id, null) } },
    )
}
