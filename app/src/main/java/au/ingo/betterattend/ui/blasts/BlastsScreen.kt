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
import au.ingo.betterattend.ui.components.PollWhileVisible
import au.ingo.betterattend.ui.components.catching
import au.ingo.betterattend.ui.nav.AppNavigator
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

    val state: StateFlow<BlastsUiState> = combine(local, c.events.events, c.participants.rosters) { l, events, rosters ->
        val event = events?.firstOrNull { it.id == eventId }
        l.copy(
            event = event,
            // A roster without syncedAt is only partial, so it can't tell us how many people a blast reaches.
            recipientEstimate = rosters[eventId]?.takeIf { event?.canViewParticipants == true && it.syncedAt != null }
                ?.let { BlastLogic.estimateRecipients(it.participants) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlastsUiState())

    /**
     * Polling rounds left before we stop checking on in-flight blasts. Bounded because the venue's staff share
     * one per-IP rate limit (300 requests / 5 min); sending a blast or pulling to refresh tops it up again.
     */
    private val pollBudget = MutableStateFlow(MAX_POLL_ROUNDS)

    /** True while some blast is still sending and we haven't used up the polling budget. */
    val shouldPoll: StateFlow<Boolean> = combine(local, pollBudget) { l, budget ->
        budget > 0 && l.blasts.orEmpty().any(BlastLogic::isActive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { c.participants.load(eventId) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            local.update { it.copy(refreshing = true) }
            catching { c.api.slackBlasts(eventId) }
                .onSuccess { list -> local.update { it.copy(blasts = list, refreshing = false, error = null) }; resetPollBudget() }
                .onFailure { e -> local.update { it.copy(refreshing = false, error = e.friendlyMessage) } }
        }
    }

    fun send(plain: String) {
        if (plain.isBlank() || local.value.sending) return
        viewModelScope.launch {
            local.update { it.copy(sending = true, sendError = null) }
            catching { c.api.sendSlackBlast(eventId, BlastLogic.toHtml(plain)) }
                .onSuccess { blast ->
                    local.update { it.copy(sending = false, blasts = BlastLogic.upsert(it.blasts.orEmpty(), blast)) }
                    _sent.tryEmit(blast)
                    resetPollBudget()
                }
                .onFailure { e -> local.update { it.copy(sending = false, sendError = e.friendlyMessage) } }
        }
    }

    fun clearSendError() = local.update { it.copy(sendError = null) }

    /**
     * One round of progress checks for in-flight blasts. The screen calls this every [POLL_INTERVAL_MS] only
     * while it's visible (see [BlastsScreen]), so a backgrounded app doesn't keep hitting the API.
     */
    suspend fun pollActive() {
        val active = local.value.blasts.orEmpty().filter(BlastLogic::isActive)
        if (active.isEmpty() || pollBudget.value <= 0) return
        pollBudget.update { it - 1 }
        if (active.size == 1) {
            catching { c.api.slackBlast(eventId, active.single().id) }.onSuccess { updated ->
                local.update { it.copy(blasts = BlastLogic.upsert(it.blasts.orEmpty(), updated)) }
            }
        } else {
            // Several in flight: one list request is cheaper than one per blast.
            catching { c.api.slackBlasts(eventId) }.onSuccess { list -> local.update { it.copy(blasts = list) } }
        }
    }

    private fun resetPollBudget() { pollBudget.value = MAX_POLL_ROUNDS }

    companion object {
        const val POLL_INTERVAL_MS = 10_000L
        /** 10 s × 60 = 10 minutes of on-screen polling; after that pull-to-refresh picks up the final state. */
        private const val MAX_POLL_ROUNDS = 60
    }
}

@Composable
fun BlastsScreen(eventId: String, nav: AppNavigator) {
    val container = LocalAppContainer.current
    val vm: BlastsViewModel = viewModel(key = "blasts_$eventId", factory = viewModelFactory { initializer { BlastsViewModel(container, eventId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val shouldPoll by vm.shouldPoll.collectAsStateWithLifecycle()
    // Live progress for blasts still sending: every 10 s, only while this screen is visible, until they finish.
    if (shouldPoll) {
        PollWhileVisible(vm, BlastsViewModel.POLL_INTERVAL_MS, runImmediately = false) { vm.pollActive() }
    }
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
