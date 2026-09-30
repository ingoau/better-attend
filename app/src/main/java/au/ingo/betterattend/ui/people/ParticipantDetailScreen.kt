package au.ingo.betterattend.ui.people

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.util.Time
import java.time.Instant

/** Everything the detail screen can ask for, bundled so the stateless content stays readable. */
data class DetailCallbacks(
    val back: () -> Unit = {},
    val refresh: () -> Unit = {},
    val checkIn: (ScanContext?) -> Unit = {},
    val undo: (String?) -> Unit = {},
    val writeBadge: () -> Unit = {},
    val resetBadge: () -> Unit = {},
    val setWithdrawn: (Boolean) -> Unit = {},
    val openWeb: () -> Unit = {},
    val copyId: () -> Unit = {},
    val addNote: (String, String, String) -> Boolean = { _, _, _ -> true },
    val retryNote: (String) -> Unit = {},
    val discardNote: (String) -> Unit = {},
    val contact: ContactActions = ContactActions(),
)

@Composable
fun ParticipantDetailScreen(eventId: String, participantEventId: String, nav: AppNavigator) {
    val c = LocalAppContainer.current
    val vm: ParticipantDetailViewModel = viewModel(
        key = "participant_$participantEventId",
        factory = ParticipantDetailViewModel.Factory(c, eventId, participantEventId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Reader mode stays on for the whole time the badge sheet is open (turning it off mid-write drops the tag).
    val activity = remember(context) { context.findActivity() }
    val nfcSheetOpen = state.nfc !is NfcWriteState.Idle && state.nfc != NfcWriteState.Unsupported && state.nfc != NfcWriteState.Disabled
    DisposableEffect(nfcSheetOpen, activity) {
        if (nfcSheetOpen && activity != null) NfcBadgeWriter.start(activity) { tag -> vm.onTag(tag) }
        onDispose { if (activity != null) NfcBadgeWriter.stop(activity) }
    }
    // Came back from NFC settings: carry on if it's now on.
    LifecycleResumeEffect(state.nfc) {
        if (state.nfc == NfcWriteState.Disabled && NfcBadgeWriter.availability(context) == NfcAvailability.Ready) {
            vm.startNfcWrite(NfcAvailability.Ready)
        }
        onPauseOrDispose { }
    }

    fun launch(intent: Intent, fallback: String) {
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, fallback, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val contact = ContactActions(
        call = { launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it))), "No phone app found") },
        sms = { launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(it))), "No messaging app found") },
        whatsApp = { launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + it.filter(Char::isDigit))), "WhatsApp isn't installed") },
        email = { launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(it))), "No email app found") },
        openUrl = { launch(Intent(Intent.ACTION_VIEW, Uri.parse(it)), "No browser found") },
    )

    ParticipantDetailContent(
        state = state,
        snackbar = snackbar,
        callbacks = DetailCallbacks(
            back = nav::back,
            refresh = vm::refresh,
            checkIn = vm::checkIn,
            undo = vm::undo,
            writeBadge = { vm.startNfcWrite(NfcBadgeWriter.availability(context)) },
            resetBadge = vm::resetBadge,
            setWithdrawn = vm::setWithdrawn,
            openWeb = {
                state.event?.let { e -> contact.openUrl("https://attend.hackclub.com/admin/events/${e.slug}/participants/$participantEventId") }
            },
            copyId = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Participant ID", participantEventId))
            },
            addNote = vm::addNote,
            retryNote = vm::retryNote,
            discardNote = vm::discardNote,
            contact = contact,
        ),
    )

    if (state.nfc !is NfcWriteState.Idle) {
        NfcWriteSheet(
            state = state.nfc,
            name = state.participant?.name ?: "this person",
            onRetry = { vm.retryNfc(NfcBadgeWriter.availability(context)) },
            onOpenSettings = { launch(Intent(Settings.ACTION_NFC_SETTINGS), "Couldn't open NFC settings") },
            onDismiss = vm::dismissNfc,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ParticipantDetailContent(
    state: DetailUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    now: Instant = Instant.now(),
    callbacks: DetailCallbacks = DetailCallbacks(),
) {
    val p = state.participant
    val tz = state.event?.timezone
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(scrolled && p != null, enter = fadeIn(), exit = fadeOut()) {
                        Text(p?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = callbacks.back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { if (p != null) OverflowMenu(state, p, callbacks) { dialog = it } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                p == null && state.loading -> LoadingState(message = "Loading…")
                p == null -> EmptyState(
                    Icons.Outlined.PersonOff, "Couldn't open this person",
                    body = state.error ?: "They may have been removed from this event.",
                    actionLabel = "Try again", onAction = callbacks.refresh,
                )
                else -> PullToRefreshBox(isRefreshing = state.loading && state.detailLoaded, onRefresh = callbacks.refresh) {
                    DetailBody(state, p, tz, now, list, callbacks) { dialog = it }
                }
            }
        }
    }

    if (p != null) when (dialog) {
        "undo" -> UndoCheckInDialog(p.name, p.scansByContext, tz, onConfirm = { dialog = null; callbacks.undo(it) }, onDismiss = { dialog = null })
        "withdraw" -> ConfirmDialog(
            "Withdraw ${p.name}?", "They'll be hidden from the list and lose access to their ticket. You can reinstate them later.",
            "Withdraw", destructive = true, onConfirm = { dialog = null; callbacks.setWithdrawn(true) }, onDismiss = { dialog = null },
        )
        "reinstate" -> ConfirmDialog(
            "Reinstate ${p.name}?", "Their registration goes back to in progress so they can finish signing up.",
            "Reinstate", destructive = false, onConfirm = { dialog = null; callbacks.setWithdrawn(false) }, onDismiss = { dialog = null },
        )
        "reset_badge" -> ConfirmDialog(
            "Reset ${p.name}'s badge?", "Their current NFC badge will stop working at scanners. Write a new badge afterwards.",
            "Reset badge", destructive = true, onConfirm = { dialog = null; callbacks.resetBadge() }, onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun OverflowMenu(state: DetailUiState, p: Participant, callbacks: DetailCallbacks, onDialog: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Open in Attend web") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                onClick = { open = false; callbacks.openWeb() },
            )
            DropdownMenuItem(
                text = { Text("Copy participant ID") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { open = false; callbacks.copyId() },
            )
            if (p.nfcBadgeAssigned) {
                DropdownMenuItem(
                    text = { Text("Reset NFC badge") },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                    onClick = { open = false; onDialog("reset_badge") },
                )
            }
            if (state.canChangeStatus) {
                HorizontalDivider()
                if (p.status == "withdrawn") {
                    DropdownMenuItem(
                        text = { Text("Reinstate") },
                        leadingIcon = { Icon(Icons.Outlined.HowToReg, null) },
                        onClick = { open = false; onDialog("reinstate") },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Withdraw", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { open = false; onDialog("withdraw") },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBody(
    state: DetailUiState,
    p: Participant,
    tz: String?,
    now: Instant,
    list: androidx.compose.foundation.lazy.LazyListState,
    callbacks: DetailCallbacks,
    onDialog: (String) -> Unit,
) {
    val alerts = remember(p, state.canViewSensitive) { safetyAlerts(p, state.canViewSensitive) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { Header(p, tz) }
        if (state.error != null && !state.detailLoaded) {
            item(key = "err") { OfflineBanner("Showing saved details. ${state.error}", onRetry = callbacks.refresh) }
        }
        items(alerts.size, key = { "alert_$it" }) { i -> SafetyAlertCard(alerts[i]) }
        item(key = "actions") { ActionsPanel(state, p, callbacks, onDialog) }
        if (!state.detailLoaded && state.loading) {
            item(key = "loading") {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(36.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Loading full profile…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "contact") { ContactSection(p, state.canViewPii, callbacks.contact) }
        item(key = "scans") { ScansSection(p, tz) }
        p.travelInbound?.let { t -> item(key = "travel_in") { TravelSection(t, tz, state.canViewPii) } }
        p.travelOutbound?.let { t -> item(key = "travel_out") { TravelSection(t, tz, state.canViewPii) } }
        item(key = "personal") { PersonalSection(p) }
        item(key = "accommodation") { AccommodationSection(p) }
        if (state.canViewSensitive) {
            item(key = "medical") { MedicalSection(p) }
            item(key = "access") { AccessibilitySection(p.accessibility) }
        }
        item(key = "safeguarding") { SafeguardingSection(p, state.canViewSensitive) }
        item(key = "guardians") { GuardiansSection(p, callbacks.contact) }
        item(key = "consents") { ConsentsSection(p.consents, tz, callbacks.contact) }
        item(key = "groups") { GroupsSection(p) }
        if (!state.notesHidden) {
            item(key = "notes") {
                NotesSection(state.notes, state.notesError, now, callbacks.addNote, callbacks.retryNote, callbacks.discardNote)
            }
        }
    }
}

@Composable
private fun Header(p: Participant, tz: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(136.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer))
            Avatar(p.fullName ?: p.name, p.headshotUrl, size = 108.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(p.name, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        val sub = listOfNotNull(
            p.fullName?.takeIf { it != p.name && it.isNotBlank() },
            p.pronouns?.takeIf { it.isNotBlank() },
            p.personal?.age?.let { "Age $it" },
        ).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(p)
            if (p.status != "complete" && p.isCheckedIn) Pill(statusLabel(p.status), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.nfcBadgeAssigned) Pill("Badge", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, icon = Icons.Outlined.Nfc)
        }
        Spacer(Modifier.height(6.dp))
        val scan = PeopleFilter.checkInScan(p)
        Text(
            if (p.isCheckedIn) "Checked in ${Time.dayTime(p.checkedInAt, tz)}${scan?.scanContextName?.let { " at $it" } ?: ""}" else "Not checked in",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ActionsPanel(state: DetailUiState, p: Participant, callbacks: DetailCallbacks, onDialog: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CheckInButtons(state, p, callbacks, onDialog)
        val phone = p.phone?.takeIf { state.canViewPii && it.isNotBlank() }
        val email = p.email?.takeIf { state.canViewPii && it.isNotBlank() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (phone != null) {
                ActionTile(Icons.Outlined.Call, "Call") { callbacks.contact.call(phone) }
                ActionTile(Icons.Outlined.Sms, "Message") { callbacks.contact.sms(phone) }
                ActionTile(Icons.AutoMirrored.Outlined.Chat, "WhatsApp") { callbacks.contact.whatsApp(phone) }
            }
            if (email != null) ActionTile(Icons.Outlined.Email, "Email") { callbacks.contact.email(email) }
            ActionTile(Icons.Outlined.Nfc, "Badge", busy = state.busy == DetailBusy.ResettingBadge, onClick = callbacks.writeBadge)
        }
    }
}

@Composable
private fun CheckInButtons(state: DetailUiState, p: Participant, callbacks: DetailCallbacks, onDialog: (String) -> Unit) {
    val busy = state.busy
    val options = state.checkInContexts
    val default = state.defaultCheckInContext
    if (p.isCheckedIn) {
        FilledTonalButton(
            onClick = { onDialog("undo") },
            enabled = busy == null,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy == DetailBusy.Undoing) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.AutoMirrored.Outlined.Undo, null)
            Spacer(Modifier.width(8.dp))
            Text("Undo check-in", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = if (default != null && options.size > 1) "Check in · ${default.name}" else "Check in"
        if (options.size > 1) {
            var menu by remember { mutableStateOf(false) }
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.LeadingButton(
                        onClick = { callbacks.checkIn(default) },
                        enabled = busy == null,
                        modifier = Modifier.height(56.dp).widthIn(min = 200.dp),
                    ) { CheckInLabel(busy == DetailBusy.CheckingIn, label) }
                },
                trailingButton = {
                    Box {
                        SplitButtonDefaults.TrailingButton(
                            checked = menu,
                            onCheckedChange = { menu = it },
                            enabled = busy == null,
                            modifier = Modifier.height(56.dp).semantics { },
                        ) {
                            Icon(Icons.Outlined.ExpandMore, "Choose where to check in", Modifier.rotate(if (menu) 180f else 0f))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            options.forEach { ctx ->
                                DropdownMenuItem(
                                    text = { Text(ctx.name) },
                                    leadingIcon = if (ctx.id == default?.id) { { Icon(Icons.Outlined.CheckCircle, "Default") } } else null,
                                    onClick = { menu = false; callbacks.checkIn(ctx) },
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            Button(
                onClick = { callbacks.checkIn(default) },
                enabled = busy == null,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { CheckInLabel(busy == DetailBusy.CheckingIn, label) }
        }
        if (p.scansByContext.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { onDialog("undo") }, enabled = busy == null, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Undo, "Undo scans")
            }
        }
    }
}

@Composable
private fun CheckInLabel(busy: Boolean, label: String) {
    if (busy) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
    else Icon(Icons.Outlined.CheckCircle, null)
    Spacer(Modifier.width(8.dp))
    Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, busy: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        FilledTonalIconButton(onClick = onClick, enabled = !busy, modifier = Modifier.size(56.dp)) {
            if (busy) LoadingIndicator(Modifier.size(24.dp)) else Icon(icon, label)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
