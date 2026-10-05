package au.ingo.betterattend.ui.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SupervisorAccount
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.data.model.EventPermissions
import au.ingo.betterattend.data.model.StaffMember
import au.ingo.betterattend.data.model.StaffRole
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.SegmentedItem
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.HapticPullToRefreshBox
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.people.ConfirmDialog
import au.ingo.betterattend.ui.people.ErrorBanner
import au.ingo.betterattend.ui.theme.status

data class StaffActions(
    val back: () -> Unit = {},
    val refresh: () -> Unit = {},
    val openAdd: () -> Unit = {},
    val updateAdd: (AddStaffState) -> Unit = {},
    val sendAdd: () -> Unit = {},
    val closeAdd: () -> Unit = {},
    val select: (StaffMember) -> Unit = {},
    val pickRole: (String) -> Unit = {},
    val saveRole: () -> Unit = {},
    val remove: () -> Unit = {},
    val closeMember: () -> Unit = {},
)

/** Settings → Event staff: who can work on this event, and in what role. Event admins only. */
@Composable
fun StaffScreen(eventId: String, nav: AppNavigator) {
    val c = LocalAppContainer.current
    val vm: StaffViewModel = viewModel(key = "staff_$eventId", factory = StaffViewModel.Factory(c, eventId))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.cues.collect { ok -> if (ok) haptics.confirm() else haptics.reject() } }
    var manualRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) manualRefresh = false }

    StaffContent(
        state = state,
        snackbar = snackbar,
        refreshing = manualRefresh && state.loading,
        actions = StaffActions(
            back = nav::back,
            refresh = { manualRefresh = true; vm.refresh() },
            openAdd = { haptics.click(); vm.openAdd() },
            updateAdd = vm::updateAdd,
            sendAdd = vm::sendAdd,
            closeAdd = vm::closeAdd,
            select = { haptics.click(); vm.select(it) },
            pickRole = { haptics.tick(); vm.pickRole(it) },
            saveRole = vm::saveRole,
            remove = vm::remove,
            closeMember = vm::closeMember,
        ),
    )
}

@Composable
fun StaffContent(
    state: StaffUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    refreshing: Boolean = false,
    actions: StaffActions = StaffActions(),
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val showList = state.eventsLoaded && state.canManage && state.staff != null

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Event staff")
                        state.event?.let {
                            Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = actions.back, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (showList && state.roles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = actions.openAdd,
                    icon = { Icon(Icons.Outlined.PersonAdd, null) },
                    text = { Text("Add staff") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.eventsLoaded -> LoadingState()
                !state.canManage -> EmptyState(
                    Icons.Outlined.Lock, "You don't have access to this",
                    body = "Only event admins can see and manage who's on the staff" + (state.event?.let { " for ${it.name}." } ?: "."),
                    actionLabel = "Back", onAction = actions.back,
                )
                state.staff == null && state.error != null -> EmptyState(
                    Icons.Outlined.CloudOff, "Couldn't load staff", body = state.error, actionLabel = "Try again", onAction = actions.refresh,
                )
                state.staff == null -> LoadingState(message = "Loading staff…")
                else -> HapticPullToRefreshBox(isRefreshing = refreshing, onRefresh = actions.refresh) {
                    StaffList(state, actions)
                }
            }
        }
    }

    state.add?.let { add ->
        val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
        ModalBottomSheet(onDismissRequest = actions.closeAdd, sheetState = sheet) {
            AddStaffSheetContent(
                add, state.roles, actions.updateAdd, actions.sendAdd, actions.closeAdd,
                held = StaffLogic.heldRoles(add.email, state.staff.orEmpty()),
            )
        }
    }

    val member = state.selectedMember
    val sel = state.selected
    if (member != null && sel != null) {
        val self = StaffLogic.isSelf(member, state.me)
        val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
        ModalBottomSheet(onDismissRequest = actions.closeMember, sheetState = sheet) {
            MemberSheetContent(
                member, sel, state.roles, self,
                onPick = actions.pickRole,
                onSave = {
                    if (StaffLogic.losesStaffAccess(member, sel.role, state.me, state.staff.orEmpty())) confirm = "demote" else actions.saveRole()
                },
                onRemove = { confirm = "remove" },
                onCancel = actions.closeMember,
                held = StaffLogic.heldRoles(member.user.email, state.staff.orEmpty(), exceptId = member.id),
            )
        }
        val lose = StaffLogic.losesStaffAccess(member, if (confirm == "demote") sel.role else null, state.me, state.staff.orEmpty())
        when (confirm) {
            "demote" -> ConfirmDialog(
                "Change your own role?",
                "You'll be ${StaffLogic.roleLabel(sel.role, state.roles)} on ${state.event?.name ?: "this event"}. You'll lose access to managing staff, " +
                    "and only another event admin can give it back.",
                "Change my role", destructive = true,
                onConfirm = { confirm = null; actions.saveRole() }, onDismiss = { confirm = null },
            )
            "remove" -> ConfirmDialog(
                if (self) "Remove yourself?" else "Remove ${member.user.displayName}?",
                buildString {
                    append(if (self) "You'll no longer be on the staff for " else "They'll lose access to ")
                    append(state.event?.name ?: "this event")
                    append(".")
                    if (lose) append(" You'll lose access to managing staff, and only another event admin can add you back.")
                },
                "Remove", destructive = true,
                onConfirm = { confirm = null; actions.remove() }, onDismiss = { confirm = null },
            )
        }
    }
}

@Composable
private fun StaffList(state: StaffUiState, actions: StaffActions) {
    val groups = remember(state.staff, state.roles) { StaffLogic.grouped(state.staff.orEmpty(), state.roles) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        if (state.error != null) item("error") { OfflineBanner("Showing the last list. ${state.error}", Modifier.padding(top = 8.dp), onRetry = actions.refresh) }
        if (groups.isEmpty()) {
            item("empty") { EmptyState(Icons.Outlined.SupervisorAccount, "No staff yet", body = "Add the people helping run this event.") }
        }
        groups.forEach { (label, members) ->
            item("h_$label") {
                Text(
                    "$label · ${members.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
                )
            }
            itemsIndexed(members, key = { _, m -> m.id }) { i, m ->
                StaffRow(
                    m, i, members.size, self = StaffLogic.isSelf(m, state.me),
                    onClick = if (EventPermissions.canChangeStaffMember(m)) { { actions.select(m) } } else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffRow(member: StaffMember, index: Int, count: Int, self: Boolean, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        val s = MaterialTheme.status
        val user = member.user
        ListItem(
            headlineContent = { Text(user.displayName + if (self) " (you)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text(user.email, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (user.globalAdmin || member.inheritedFromSeries) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (user.globalAdmin) Pill("Global admin", s.dangerContainer, s.onDangerContainer, icon = Icons.Outlined.AdminPanelSettings)
                            if (member.inheritedFromSeries) {
                                Pill("From series", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Lock)
                            }
                        }
                    }
                }
            },
            leadingContent = { Avatar(user.displayName, null, size = 40.dp) },
            trailingContent = if (onClick != null) { { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Change role or remove") } } else null,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    SegmentedItem(index, count, onClick = onClick, content = content)
}

/**
 * Radio list of the server's role catalogue, each with its summary. [held]: roles the person already
 * has in another row here; upstream allows each role once per person, so those can't be picked.
 */
@Composable
fun RolePicker(roles: List<StaffRole>, selected: String?, enabled: Boolean, onSelect: (String) -> Unit, held: Set<String> = emptySet()) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        roles.forEach { r ->
            val on = r.role == selected
            val taken = r.role in held
            val rowEnabled = enabled && !taken
            Surface(
                color = if (on && !taken) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().selectable(selected = on, enabled = rowEnabled, role = Role.RadioButton) { onSelect(r.role) },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = on, onClick = null, enabled = rowEnabled)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val dim = if (taken) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified
                        Text(r.label, style = MaterialTheme.typography.bodyLarge, color = dim)
                        if (taken) {
                            Text("Already ${r.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            r.summary?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddStaffSheetContent(
    state: AddStaffState,
    roles: List<StaffRole>,
    onChange: (AddStaffState) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Roles the typed email already holds here (they're on the list). */
    held: Set<String> = emptySet(),
) {
    val enabled = !state.sending
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("Add staff", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "They'll get an email saying they've been added. Someone new to Attend signs in with Hack Club using this address.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        state.error?.let { ErrorBanner(it); Spacer(Modifier.height(12.dp)) }
        OutlinedTextField(
            value = state.email,
            onValueChange = { onChange(state.copy(email = it, error = null)) },
            label = { Text("Email") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        SheetLabel("Role")
        RolePicker(roles, state.role, enabled, { onChange(state.copy(role = it, error = null)) }, held)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend, enabled = enabled && state.email.isNotBlank() && state.role != null && state.role !in held,
                shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (state.sending) {
                    LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Adding…")
                } else Text("Add")
            }
        }
    }
}

@Composable
fun MemberSheetContent(
    member: StaffMember,
    state: MemberSheetState,
    roles: List<StaffRole>,
    self: Boolean,
    onPick: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Roles this person already holds in their other rows here. */
    held: Set<String> = emptySet(),
) {
    val enabled = !state.busy
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(member.user.displayName, null, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(member.user.displayName + if (self) " (you)" else "", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(member.user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        state.error?.let { ErrorBanner(it); Spacer(Modifier.height(4.dp)) }
        SheetLabel("Role")
        RolePicker(roles, state.role, enabled, onPick, held)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onRemove, enabled = enabled, shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.PersonRemove, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave, enabled = enabled && state.role != member.role && state.role !in held, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                if (state.busy) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary) else Text("Save")
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}
