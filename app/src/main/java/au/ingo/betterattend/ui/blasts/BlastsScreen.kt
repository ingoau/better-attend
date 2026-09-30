package au.ingo.betterattend.ui.blasts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.SlackBlast
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.nav.AppNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlastsUiState(
    val event: Event? = null,
    /** null until the first load finishes. */
    val blasts: List<SlackBlast>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** People the next blast would reach, from the cached roster; null when we can't tell. */
    val recipientEstimate: Int? = null,
    val sending: Boolean = false,
    val sendError: String? = null,
)

class BlastsViewModel(private val c: AppContainer, private val eventId: String) : ViewModel() {
    private val local = MutableStateFlow(BlastsUiState())
    private val _sent = MutableSharedFlow<SlackBlast>(extraBufferCapacity = 1)
    /** Emits once a blast has been accepted by the server (closes the composer, shows a snackbar). */
    val sent: SharedFlow<SlackBlast> = _sent
    private var pollJob: Job? = null

    val state: StateFlow<BlastsUiState> = combine(local, c.events.events, c.participants.rosters) { l, events, rosters ->
        val event = events?.firstOrNull { it.id == eventId }
        l.copy(
            event = event,
            recipientEstimate = rosters[eventId]?.takeIf { event?.canViewParticipants == true }
                ?.let { BlastLogic.estimateRecipients(it.participants) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlastsUiState())

    init {
        viewModelScope.launch { c.participants.load(eventId) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            local.update { it.copy(refreshing = true) }
            runCatching { c.api.slackBlasts(eventId) }
                .onSuccess { list -> local.update { it.copy(blasts = list, refreshing = false, error = null) }; ensurePolling() }
                .onFailure { e -> local.update { it.copy(refreshing = false, error = e.friendlyMessage) } }
        }
    }

    fun send(plain: String) {
        if (plain.isBlank() || local.value.sending) return
        viewModelScope.launch {
            local.update { it.copy(sending = true, sendError = null) }
            runCatching { c.api.sendSlackBlast(eventId, BlastLogic.toHtml(plain)) }
                .onSuccess { blast ->
                    local.update { it.copy(sending = false, blasts = BlastLogic.upsert(it.blasts.orEmpty(), blast)) }
                    _sent.tryEmit(blast)
                    ensurePolling()
                }
                .onFailure { e -> local.update { it.copy(sending = false, sendError = e.friendlyMessage) } }
        }
    }

    fun clearSendError() = local.update { it.copy(sendError = null) }

    /** Polls every in-flight blast every 2 s until it completes or fails (bounded, to protect the rate limit). */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var rounds = 0
            while (rounds++ < MAX_POLL_ROUNDS) {
                val active = local.value.blasts.orEmpty().filter(BlastLogic::isActive)
                if (active.isEmpty()) break
                delay(2_000)
                for (b in active) {
                    runCatching { c.api.slackBlast(eventId, b.id) }.onSuccess { updated ->
                        local.update { it.copy(blasts = BlastLogic.upsert(it.blasts.orEmpty(), updated)) }
                    }
                }
            }
        }
    }

    companion object {
        /** 2 s × 300 = 10 minutes; after that the list's pull-to-refresh picks up the final state. */
        private const val MAX_POLL_ROUNDS = 300
    }
}

@Composable
fun BlastsScreen(eventId: String, nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm: BlastsViewModel = viewModel(key = "blasts_$eventId", factory = viewModelFactory { initializer { BlastsViewModel(container, eventId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val controller = rememberBlastsController()
    LaunchedEffect(vm) {
        vm.sent.collect { blast -> controller.onSent(blast) }
    }
    BlastsContent(
        state = state,
        controller = controller,
        onBack = nav::back,
        onRefresh = vm::refresh,
        onSend = vm::send,
        onDismissSendError = vm::clearSendError,
    )
}
