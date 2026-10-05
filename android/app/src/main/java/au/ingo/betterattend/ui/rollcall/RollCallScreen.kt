package au.ingo.betterattend.ui.rollcall

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.RollCall
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.data.store.SecureBox
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.catching
import au.ingo.betterattend.ui.nav.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the roll call screens render. Screenshot tests build this from sample data. */
data class RollCallUiState(
    /** False until the cached event list has been read. */
    val eventsLoaded: Boolean = false,
    val event: Event? = null,
    /** null until the cached roster is read. */
    val roster: Roster? = null,
    /** The event's scan points; null while loading. */
    val contexts: List<ScanContext>? = null,
    /** False until this event's saved roll call (if any) has been read. */
    val loaded: Boolean = false,
    val rollCall: RollCall? = null,
    val syncing: Boolean = false,
    val syncError: String? = null,
    /** No Keystore key: the roll call lives only while the app is open. */
    val storageUnavailable: Boolean = false,
) {
    val canView: Boolean get() = EventPermissions.canViewParticipants(event)

    /** A roster that's had a full sync, so it can say who's expected. */
    val completeRoster: Roster? get() = roster?.takeIf { it.syncedAt != null }
}

private data class Local(val loaded: Boolean = false, val syncing: Boolean = false, val syncError: String? = null)

class RollCallViewModel(private val c: AppContainer, private val eventId: String) : ViewModel() {
    private val local = MutableStateFlow(Local())

    /** The roll call being ended: shown until the screen has gone. */
    private val ending = MutableStateFlow<RollCall?>(null)

    private val sessions = combine(c.rollCalls.sessions, ending) { sessions, ending -> ending ?: sessions[eventId] }

    private val repos = combine(c.events.events, c.participants.rosters, c.events.contexts, sessions) { events, rosters, contexts, rollCall ->
        RollCallUiState(
            eventsLoaded = events != null,
            event = events?.firstOrNull { it.id == eventId },
            roster = rosters[eventId],
            contexts = contexts[eventId],
            rollCall = rollCall,
        )
    }

    val state: StateFlow<RollCallUiState> = combine(repos, local, SecureBox.available) { s, l, available ->
        s.copy(loaded = l.loaded, syncing = l.syncing, syncError = l.syncError, storageUnavailable = !available)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RollCallUiState())

    /** Snackbar messages about recording ticks at the scan point. */
    val notices = c.rollCalls.notices.filter { it.eventId == eventId }

    init {
        viewModelScope.launch {
            c.participants.load(eventId)
            c.events.loadContexts(eventId)
            c.rollCalls.load(eventId)
            local.update { it.copy(loaded = true) }
            // Only while setting up: a running roll call works from its frozen list and needs no network.
            if (c.rollCalls.sessions.value[eventId] == null) refresh()
        }
    }

    /** Freshens the roster and scan points before starting, so "who's expected" is current. */
    fun refresh() {
        val event = state.value.event ?: c.events.events.value?.firstOrNull { it.id == eventId }
        if (!EventPermissions.canViewParticipants(event)) return
        viewModelScope.launch {
            local.update { it.copy(syncing = true) }
            launch { c.events.refreshContexts(eventId) }
            val result = catching { c.participants.sync(eventId) }
            local.update { it.copy(syncing = false, syncError = result.exceptionOrNull()?.friendlyMessage) }
        }
    }

    fun start(mode: RollCallExpected, recording: TickRecording) {
        val s = state.value
        val roster = s.completeRoster ?: return
        if (!s.canView || s.rollCall != null) return
        // Only a scan point the user picked from this event's list; never a fallback.
        if (recording is TickRecording.AtScanPoint && s.contexts.orEmpty().none { it.id == recording.contextId }) return
        c.rollCalls.start(RollCallLogic.start(eventId, roster.participants, mode, recording, s.contexts.orEmpty()))
    }

    // These hand straight to the repository, which runs them on the app scope: leaving the screen
    // right after a tap never drops the tick, its scan, or an untick's undo.

    fun toggle(participantEventId: String) { c.rollCalls.toggle(eventId, participantEventId) }

    fun add(participantEventId: String, name: String) { c.rollCalls.add(eventId, participantEventId, name) }

    fun finish() { c.rollCalls.finish(eventId) }

    fun resume() { c.rollCalls.resume(eventId) }

    /** Leaves first, still showing the summary, so the setup screen never flashes up on the way out. */
    fun end(leave: () -> Unit) {
        if (ending.value != null) return
        ending.value = state.value.rollCall ?: return
        leave()
        c.rollCalls.end(eventId)
    }
}

@Composable
fun RollCallScreen(eventId: String, nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm: RollCallViewModel = viewModel(key = "rollcall_$eventId", factory = viewModelFactory { initializer { RollCallViewModel(container, eventId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it.message) } }

    var expected by rememberSaveable { mutableStateOf(RollCallExpected.CheckedIn) }
    // Ticks stay on this phone unless the user picks a scan point themselves: never preselect one.
    var recordAt by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(RollCallFilter.Missing) }
    var query by rememberSaveable { mutableStateOf("") }

    RollCallContent(
        state = state,
        snackbar = snackbar,
        setup = RollCallSetupChoice(expected, recordAt?.let { TickRecording.AtScanPoint(it) } ?: TickRecording.PhoneOnly),
        filter = filter,
        query = query,
        actions = RollCallActions(
            onBack = nav::back,
            onRefresh = vm::refresh,
            onExpected = { expected = it },
            onRecording = { recordAt = (it as? TickRecording.AtScanPoint)?.contextId },
            onStart = { vm.start(expected, recordAt?.let { TickRecording.AtScanPoint(it) } ?: TickRecording.PhoneOnly); filter = RollCallFilter.Missing; query = "" },
            onFilter = { filter = it },
            onQuery = { query = it },
            onToggle = vm::toggle,
            onAdd = vm::add,
            onFinish = vm::finish,
            onResume = vm::resume,
            onEnd = { vm.end { nav.back() } },
            onShare = { text -> context.shareText(text, "Roll call") },
            onOpenParticipant = { nav.openParticipant(eventId, it) },
        ),
    )
}

/** Opens the share sheet with plain text. */
internal fun Context.shareText(text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, title)
    }
    runCatching { startActivity(Intent.createChooser(send, title)) }
}
