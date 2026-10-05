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
import au.ingo.betterattend.data.repo.ScanRejection
import au.ingo.betterattend.data.store.SecureBox
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.PollWhileVisible
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.components.catching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** Offline check-ins the server turned down on sync (any event), until dismissed. */
    val rejections: List<ScanRejection> = emptyList(),
    /** The encryption key is unavailable, so nothing is saved on this device. */
    val storageUnavailable: Boolean = false,
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

    private val alerts = combine(c.scans.rejections, SecureBox.available) { r, available -> r to !available }

    val state: StateFlow<DashboardState> = combine(repoState, c.events.events, c.auth.state, local, alerts) { s, events, auth, l, (rejections, noStorage) ->
        val same = l.eventId != null && l.eventId == s.event?.id
        s.copy(
            rejections = rejections,
            storageUnavailable = noStorage,
            user = (auth as? AuthState.SignedIn)?.user,
            events = events,
            scans = if (same) l.scans else null,
            refreshing = same && l.refreshing,
            userRefreshing = same && l.refreshing && l.userRefreshing,
            error = if (same) l.error else null,
            lastUpdated = if (same) l.lastUpdated else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    /** The sync currently in flight, if any, and a counter so a stale job's cleanup can't clobber a newer one. */
    private var syncJob: Job? = null
    private var syncEventId: String? = null
    private var syncSeq = 0

    /**
     * Shows cached data immediately, then syncs. The roster sync is a cheap `updated_since` delta after the
     * first time. [force] skips the throttle (pull-to-refresh); polling calls within 20 s of the last one are skipped
     * so bouncing between tabs doesn't burn the shared per-IP rate limit.
     *
     * The work runs in [viewModelScope], not the caller's scope: the poll that triggers it is cancelled as soon as
     * Home leaves the screen, and a sync cut off half way used to leave `refreshing` stuck on ("Syncing…" forever,
     * with every later refresh skipped).
     */
    fun refresh(event: Event, force: Boolean) {
        val id = event.id
        if (local.value.eventId != id) local.value = Local(eventId = id)
        val last = local.value.lastAttempt
        if (!force && last != null && Duration.between(last, Instant.now()) < Duration.ofSeconds(20)) return
        if (syncJob?.isActive == true && syncEventId == id) {
            // A sync is already running: let a pull show its spinner until that one finishes.
            if (force) local.update { it.copy(userRefreshing = true) }
            return
        }

        val seq = ++syncSeq
        syncEventId = id
        local.update { it.copy(refreshing = true, userRefreshing = force, lastAttempt = Instant.now()) }
        syncJob = viewModelScope.launch {
            try {
                sync(event, force)
            } finally {
                // Always clear the flags, even if cancelled, unless a newer sync has taken over.
                if (seq == syncSeq) local.update { if (it.eventId == id) it.copy(refreshing = false, userRefreshing = false) else it }
            }
        }
    }

    private suspend fun sync(event: Event, force: Boolean) {
        val id = event.id
        c.participants.load(id)
        c.events.loadContexts(id)
        if (event.travelEnabled) c.travel.load(id)

        val errors = mutableListOf<Throwable>()
        var scans: List<Scan>? = null
        coroutineScope {
            if (force) launch { c.events.refresh() }
            if (event.canViewParticipants) {
                launch { catching { c.participants.sync(id) }.onFailure { errors += it } }
            } else {
                launch { catching { c.api.scans(id).scans }.onSuccess { scans = it }.onFailure { errors += it } }
            }
            if (force || !local.value.contextsFetched || c.events.cachedContexts(id) == null) {
                launch { c.events.refreshContexts(id).onSuccess { local.update { l -> l.copy(contextsFetched = true) } } }
            }
            if (event.travelEnabled) launch { c.travel.refresh(id).onFailure { if (it !is CancellationException) errors += it } }
        }
        // The repositories' Result-returning calls swallow cancellation; don't record a cancelled sync as a success.
        currentCoroutineContext().ensureActive()
        local.update {
            if (it.eventId != id) it
            else it.copy(
                error = errors.firstOrNull()?.friendlyMessage,
                lastUpdated = if (errors.isEmpty()) Instant.now() else it.lastUpdated,
                scans = scans ?: it.scans,
            )
        }
    }

    fun refreshAsync(event: Event, force: Boolean = true) = refresh(event, force)

    fun refreshEvents() { viewModelScope.launch { c.events.refresh() } }

    fun dismissRejections() { viewModelScope.launch { c.scans.dismissRejections() } }
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
        onOpenRejection = { r -> r.participantEventId?.let { nav.openParticipant(r.eventId, it) } },
        onDismissRejections = vm::dismissRejections,
        onRollCall = { event?.let { nav.openRollCall(it.id) } },
        onFirstAid = { event?.let { nav.openFirstAid(it.id) } },
    )
}
