package au.ingo.betterattend.ui.firstaid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.catching
import au.ingo.betterattend.ui.nav.AppNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class FirstAidUiState(
    val eventsLoaded: Boolean = false,
    val event: Event? = null,
    /** null until the cached roster is read. */
    val roster: Roster? = null,
    val rosterRead: Boolean = false,
    val syncing: Boolean = false,
    val syncError: String? = null,
) {
    val canView: Boolean get() = EventPermissions.canViewParticipants(event)
    val sensitive: Boolean get() = EventPermissions.canViewSensitiveData(event)
}

private data class Local(val rosterRead: Boolean = false, val syncing: Boolean = false, val syncError: String? = null)

class FirstAidViewModel(private val c: AppContainer, private val eventId: String) : ViewModel() {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<FirstAidUiState> = combine(c.events.events, c.participants.rosters, local) { events, rosters, l ->
        FirstAidUiState(
            eventsLoaded = events != null,
            event = events?.firstOrNull { it.id == eventId },
            roster = rosters[eventId],
            rosterRead = l.rosterRead,
            syncing = l.syncing,
            syncError = l.syncError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstAidUiState())

    init {
        viewModelScope.launch {
            c.participants.load(eventId)
            local.update { it.copy(rosterRead = true) }
            refresh()
        }
    }

    /** A cheap delta sync; the sheet works from the cached roster either way. */
    fun refresh() {
        val event = c.events.events.value?.firstOrNull { it.id == eventId }
        if (!EventPermissions.canViewParticipants(event)) return
        viewModelScope.launch {
            local.update { it.copy(syncing = true) }
            val result = catching { c.participants.sync(eventId) }
            local.update { it.copy(syncing = false, syncError = result.exceptionOrNull()?.friendlyMessage) }
        }
    }
}

@Composable
fun FirstAidScreen(eventId: String, nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm: FirstAidViewModel = viewModel(key = "firstaid_$eventId", factory = viewModelFactory { initializer { FirstAidViewModel(container, eventId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf<FirstAidFilter?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    FirstAidContent(
        state = state,
        filter = filter,
        query = query,
        onFilter = { filter = it },
        onQuery = { query = it },
        onBack = nav::back,
        onRefresh = vm::refresh,
        onOpen = { nav.openParticipant(eventId, it) },
        onCall = { phone ->
            try {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No phone app found", Toast.LENGTH_SHORT).show()
            }
        },
        onPrint = { people, filterLabel ->
            val event = state.event ?: return@FirstAidContent
            val html = FirstAidLogic.html(event.name, people, state.sensitive, event.timezone, filterLabel, Instant.now())
            if (!FirstAidPrint.print(context, "${event.name} — First-aid sheet", html)) {
                Toast.makeText(context, "Printing isn't available on this device", Toast.LENGTH_SHORT).show()
            }
        },
    )
}
