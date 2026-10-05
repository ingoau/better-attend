package au.ingo.betterattend.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.ApiException
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.StaffMember
import au.ingo.betterattend.data.model.StaffRole
import au.ingo.betterattend.data.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "Add staff" sheet. */
data class AddStaffState(
    val email: String = "",
    /** No default: the user picks a role on purpose. */
    val role: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
)

/** The sheet for one (changeable) member: the role picked so far, and how saving / removing went. */
data class MemberSheetState(
    val memberId: String,
    val role: String,
    val busy: Boolean = false,
    val error: String? = null,
)

data class StaffUiState(
    /** False until the events cache has been read, so we don't flash "no access". */
    val eventsLoaded: Boolean = false,
    val event: Event? = null,
    val me: User? = null,
    /** null = not loaded yet. */
    val staff: List<StaffMember>? = null,
    val roles: List<StaffRole> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Attend said 403 even though the app thought this role could manage staff (roles changed server-side). */
    val forbidden: Boolean = false,
    val add: AddStaffState? = null,
    val selected: MemberSheetState? = null,
) {
    val canManage: Boolean get() = !forbidden && EventPermissions.canManageStaff(event)
    val selectedMember: StaffMember? get() = selected?.let { s -> staff?.firstOrNull { it.id == s.memberId } }
}

class StaffViewModel(private val c: AppContainer, private val eventId: String) : ViewModel() {
    private val _state = MutableStateFlow(StaffUiState())
    val state: StateFlow<StaffUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _cues = Channel<Boolean>(Channel.BUFFERED)
    /** true = confirm, false = reject: haptics for results, played by the screen. */
    val cues = _cues.receiveAsFlow()

    init {
        viewModelScope.launch {
            c.auth.state.collect { s -> _state.update { it.copy(me = (s as? AuthState.SignedIn)?.user) } }
        }
        viewModelScope.launch {
            c.events.events.collect { list ->
                val event = list?.firstOrNull { it.id == eventId }
                _state.update { it.copy(eventsLoaded = list != null, event = event) }
                // Load once we know the role allows it; never ask for what the server would refuse.
                val s = _state.value
                if (s.canManage && s.staff == null && !s.loading && s.error == null) refresh()
            }
        }
    }

    fun refresh() {
        if (!_state.value.canManage) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val res = c.api.staff(eventId)
                _state.update { it.copy(staff = res.staff, roles = res.roles, loading = false, error = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val forbidden = (e as? ApiException)?.isForbidden == true
                _state.update { it.copy(loading = false, forbidden = forbidden, error = if (forbidden) null else e.friendlyMessage) }
            }
        }
    }

    // ---------------- add ----------------

    fun openAdd() { _state.update { if (it.canManage && it.add == null) it.copy(add = AddStaffState()) else it } }
    fun updateAdd(form: AddStaffState) { _state.update { s -> if (s.add == null || s.add.sending) s else s.copy(add = form) } }
    fun closeAdd() { _state.update { if (it.add?.sending == true) it else it.copy(add = null) } }

    fun sendAdd() {
        val s = _state.value
        val form = s.add ?: return
        if (form.sending || !s.canManage) return
        StaffLogic.validateAdd(form.email, form.role, s.roles)?.let { err -> _state.update { it.copy(add = form.copy(error = err)) }; return }
        _state.update { it.copy(add = form.copy(sending = true, error = null)) }
        viewModelScope.launch {
            try {
                val res = c.api.addStaff(eventId, form.email.trim().lowercase(), form.role!!)
                _state.update { st ->
                    st.copy(add = null, staff = st.staff.orEmpty().filterNot { it.id == res.staffMember.id } + res.staffMember)
                }
                _cues.send(true)
                _messages.send(StaffLogic.addedMessage(res.staffMember, res.accountCreated, _state.value.roles))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(add = it.add?.copy(sending = false, error = e.friendlyMessage)) }
                _cues.send(false)
            }
        }
    }

    // ---------------- change / remove ----------------

    /** Opens a member's sheet. Series-inherited members can't be changed here, so they never open. */
    fun select(member: StaffMember) {
        _state.update {
            if (!it.canManage || !EventPermissions.canChangeStaffMember(member) || it.selected != null) it
            else it.copy(selected = MemberSheetState(member.id, member.role))
        }
    }

    fun pickRole(role: String) { _state.update { s -> s.selected?.takeIf { !it.busy }?.let { s.copy(selected = it.copy(role = role, error = null)) } ?: s } }
    fun closeMember() { _state.update { if (it.selected?.busy == true) it else it.copy(selected = null) } }

    /** Saves the picked role. The screen has already confirmed if this demotes the signed-in user. */
    fun saveRole() {
        val s = _state.value
        val sel = s.selected ?: return
        val member = s.selectedMember ?: return
        if (sel.busy || sel.role == member.role) return
        val self = StaffLogic.isSelf(member, s.me)
        _state.update { it.copy(selected = sel.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                val updated = c.api.updateStaffRole(eventId, member.id, sel.role)
                _state.update { st -> st.copy(selected = null, staff = st.staff?.map { if (it.id == updated.id) updated else it }) }
                _cues.send(true)
                _messages.send("${if (self) "You're" else member.user.displayName + " is"} now ${StaffLogic.roleLabel(updated, _state.value.roles)}")
                // Our own role changed: refresh events so the whole app (this screen included) follows.
                if (self) c.events.refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(selected = it.selected?.copy(busy = false, error = e.friendlyMessage)) }
                _cues.send(false)
            }
        }
    }

    fun remove() {
        val s = _state.value
        val sel = s.selected ?: return
        val member = s.selectedMember ?: return
        if (sel.busy) return
        val self = StaffLogic.isSelf(member, s.me)
        _state.update { it.copy(selected = sel.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                c.api.removeStaff(eventId, member.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if ((e as? ApiException)?.isNotFound != true) {
                    // 409 for series-inherited access explains where to manage it instead.
                    _state.update { it.copy(selected = it.selected?.copy(busy = false, error = e.friendlyMessage)) }
                    _cues.send(false)
                    return@launch
                }
            }
            _state.update { st -> st.copy(selected = null, staff = st.staff?.filterNot { it.id == member.id }) }
            _cues.send(true)
            _messages.send(if (self) "You've left the event staff" else "Removed ${member.user.displayName} from the event staff")
            if (self) c.events.refresh()
        }
    }

    class Factory(private val c: AppContainer, private val eventId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StaffViewModel(c, eventId) as T
    }
}
