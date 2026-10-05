package au.ingo.betterattend.ui.people

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.HapticPullToRefreshBox
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
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
    val editDetails: () -> Unit = {},
    val remove: () -> Unit = {},
    val openWeb: () -> Unit = {},
    val copyId: () -> Unit = {},
    val addNote: (String, String, String) -> Boolean = { _, _, _ -> true },
    val retryNote: (String) -> Unit = {},
    val discardNote: (String) -> Unit = {},
    val contact: ContactActions = ContactActions(),
)

/** Holds this visit's browse order so it survives rotation (and is taken from [ParticipantBrowseOrder] exactly once). */
private class BrowseOrderHolder(val ids: List<String>) : ViewModel()

/**
 * Participant detail. Opened from the People list, it's a pager over that list (same filter and
 * sort): swipe sideways for the previous / next person. Opened from anywhere else, it's just this
 * person. Each page has its own ViewModel and its own lifecycle, capped at STARTED unless it's the
 * settled page, so NFC reader mode and other RESUMED-only work only runs for the person on screen.
 */
@Composable
fun ParticipantDetailScreen(eventId: String, participantEventId: String, nav: AppNavigator) {
    val browse: BrowseOrderHolder = viewModel(key = "browse_$participantEventId") {
        BrowseOrderHolder(ParticipantBrowseOrder.take(eventId, participantEventId))
    }
    val order = browse.ids
    if (order.size <= 1) {
        ParticipantDetailPage(eventId, participantEventId, nav, position = null)
        return
    }

    val haptics = rememberHaptics()
    val pager = rememberPagerState(initialPage = order.indexOf(participantEventId).coerceAtLeast(0)) { order.size }
    // A tick as a swipe commits to the next person, like flipping a card.
    LaunchedEffect(pager) {
        snapshotFlow { pager.targetPage }.distinctUntilChanged().drop(1).collect {
            if (pager.isScrollInProgress) haptics.tick()
        }
    }
    HorizontalPager(
        state = pager,
        key = { order[it] },
        pageSpacing = 8.dp,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { page ->
        DetailPageLifecycle(active = page == pager.settledPage) {
            ParticipantDetailPage(eventId, order[page], nav, position = "${page + 1} of ${order.size}")
        }
    }
}

/** A page's lifecycle: RESUMED only while [active] (and the screen itself is), otherwise at most STARTED. */
private class DetailPageLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

// Mirrors PageLifecycle in ui/nav/TabPager.kt.
@Composable
private fun DetailPageLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember { DetailPageLifecycleOwner() }
    DisposableEffect(parent, active) {
        fun sync() {
            val cap = if (active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
            val parentState = parent.lifecycle.currentState
            val target = if (parentState < cap) parentState else cap
            // Can't move an INITIALIZED lifecycle straight to DESTROYED.
            if (target == Lifecycle.State.DESTROYED && owner.registry.currentState == Lifecycle.State.INITIALIZED) return
            owner.registry.currentState = target
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        parent.lifecycle.addObserver(observer)
        sync()
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (owner.registry.currentState.isAtLeast(Lifecycle.State.CREATED)) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

@Composable
private fun ParticipantDetailPage(eventId: String, participantEventId: String, nav: AppNavigator, position: String?) {
    val c = LocalAppContainer.current
    val vm: ParticipantDetailViewModel = viewModel(
        key = "participant_$participantEventId",
        factory = ParticipantDetailViewModel.Factory(c, eventId, participantEventId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Removed: this screen has nothing left to show, so leave and confirm on the way out.
    LaunchedEffect(vm) {
        vm.removed.collect { name ->
            android.widget.Toast.makeText(context, "Removed $name", android.widget.Toast.LENGTH_SHORT).show()
            nav.back()
        }
    }
    LaunchedEffect(vm) {
        vm.cues.collect { cue ->
            when (cue) {
                DetailCue.Confirm -> haptics.confirm()
                DetailCue.Reject -> haptics.reject()
                DetailCue.Click -> haptics.click()
            }
        }
    }

    fun copy(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        // Android 13+ shows its own clipboard confirmation; only older versions need ours.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("$label copied") }
        }
    }

    // Reader mode stays on for the whole time the badge sheet is open (turning it off mid-write drops the tag).
    // Android only allows it while the activity is resumed, so it follows RESUMED: re-enabled on resume
    // (e.g. after rotation with the sheet open), disabled on pause and when the sheet closes.
    val activity = remember(context) { context.findActivity() }
    val nfcSheetOpen = state.nfc !is NfcWriteState.Idle && state.nfc != NfcWriteState.Unsupported && state.nfc != NfcWriteState.Disabled
    LifecycleResumeEffect(nfcSheetOpen, activity) {
        val started = nfcSheetOpen && activity != null && NfcBadgeWriter.start(activity) { tag -> vm.onTag(tag) }
        onPauseOrDispose { if (started) activity?.let(NfcBadgeWriter::stop) }
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
        call = { haptics.click(); launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it))), "No phone app found") },
        sms = { haptics.click(); launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(it))), "No messaging app found") },
        whatsApp = { haptics.click(); launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + it.filter(Char::isDigit))), "WhatsApp isn't installed") },
        email = { haptics.click(); launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(it))), "No email app found") },
        slack = {
            haptics.click()
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SlackLinks.app(it)))) } catch (_: ActivityNotFoundException) {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(SlackLinks.web(it))), "Slack isn't installed")
            }
        },
        openUrl = { haptics.click(); launch(Intent(Intent.ACTION_VIEW, Uri.parse(it)), "No browser found") },
        // The long-press haptic comes from the long-pressed row itself.
        copy = { label, value -> copy(label, value) },
    )

    ParticipantDetailContent(
        state = state,
        snackbar = snackbar,
        position = position,
        callbacks = DetailCallbacks(
            back = nav::back,
            refresh = vm::refresh,
            checkIn = vm::checkIn,
            undo = vm::undo,
            writeBadge = { vm.startNfcWrite(NfcBadgeWriter.availability(context)) },
            resetBadge = vm::resetBadge,
            setWithdrawn = vm::setWithdrawn,
            editDetails = vm::startEdit,
            remove = vm::remove,
            openWeb = {
                state.event?.let { e -> contact.openUrl("https://attend.hackclub.com/admin/events/${e.slug}/participants/$participantEventId") }
            },
            copyId = { haptics.click(); copy("Participant ID", participantEventId) },
            addNote = vm::addNote,
            retryNote = vm::retryNote,
            discardNote = vm::discardNote,
            contact = contact,
        ),
    )

    state.edit?.let { edit ->
        EditDetailsDialog(
            session = edit,
            name = state.participant?.name ?: "",
            canEditPii = state.canEditPii,
            onChange = vm::updateEdit,
            onSave = vm::saveEdit,
            onDismiss = vm::cancelEdit,
        )
    }

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
    /** "3 of 42" when swiping through a list; shown in the bar until the name scrolls under it. */
    position: String? = null,
) {
    val p = state.participant
    val tz = state.event?.timezone
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val motion = MaterialTheme.motionScheme
    // Removing finishes even if the screen goes, but stay put until it has so the outcome is seen.
    val removing = state.busy == DetailBusy.Removing
    BackHandler(enabled = removing) {}

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    // The name once the header has scrolled away; otherwise the position in the list, if any.
                    val showName = scrolled && p != null
                    AnimatedContent(
                        targetState = if (showName) p?.name else position,
                        transitionSpec = {
                            (fadeIn(motion.fastEffectsSpec()) + slideInVertically(motion.fastSpatialSpec()) { it / 3 }) togetherWith
                                fadeOut(motion.fastEffectsSpec())
                        },
                        label = "detailTitle",
                    ) { t ->
                        when {
                            t == null -> Spacer(Modifier.fillMaxWidth())
                            t == position -> Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            else -> Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = callbacks.back, enabled = !removing) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { if (p != null) OverflowMenu(state, p, callbacks) { dialog = it } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        // Loading → person → error crossfade instead of cutting over.
        val phase = when {
            p == null && state.loading -> 0
            p == null -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
            label = "detailPhase",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                when {
                    shown == 0 -> LoadingState(message = "Loading…")
                    shown == 1 || p == null -> EmptyState(
                        Icons.Outlined.PersonOff, "Couldn't open this person",
                        body = state.error ?: "They may have been removed from this event.",
                        actionLabel = "Try again", onAction = callbacks.refresh,
                    )
                    else -> HapticPullToRefreshBox(isRefreshing = state.loading && state.detailLoaded, onRefresh = callbacks.refresh) {
                        DetailBody(state, p, tz, now, list, callbacks) { dialog = it }
                    }
                }
            }
        }
    }

    if (removing) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {},
            title = { Text("Removing ${p?.name ?: "them"}…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Deleting their registration from ${state.event?.name ?: "this event"}.")
                }
            },
        )
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
        "remove" -> ConfirmDialog(
            "Remove ${p.name} from ${state.event?.name ?: "this event"}?",
            "This deletes their registration, travel, consents and scans for this event. This can't be undone.\n\n" +
                "If they're just not coming, withdraw them instead: withdrawing is reversible.",
            "Remove", destructive = true, onConfirm = { dialog = null; callbacks.remove() }, onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun OverflowMenu(state: DetailUiState, p: Participant, callbacks: DetailCallbacks, onDialog: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "More actions for this person") }
        // Edit and the web link are in the action row; this keeps the copy and the destructive actions.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
            if (state.canChangeStatus || state.canRemove) HorizontalDivider()
            if (state.canChangeStatus) {
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
            if (state.canRemove) {
                DropdownMenuItem(
                    text = { Text("Remove from event", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.PersonRemove, null, tint = MaterialTheme.colorScheme.error) },
                    enabled = state.busy == null,
                    onClick = { open = false; onDialog("remove") },
                )
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
        // Sections fill in as the full profile arrives: each one fades in / grows and the rest glide
        // down to make room, rather than the page popping into a new layout.
        section("header") { Header(p, tz) }
        if (state.error != null && !state.detailLoaded) {
            section("err") { OfflineBanner("Showing saved details. ${state.error}", onRetry = callbacks.refresh) }
        }
        alerts.forEachIndexed { i, alert -> section("alert_$i") { SafetyAlertCard(alert) } }
        section("actions") { ActionsPanel(state, p, callbacks, onDialog) }
        if (!state.detailLoaded && state.loading) {
            section("loading") {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(36.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Loading full profile…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        section("contact") { ContactSection(p, state.canViewPii, callbacks.contact) }
        section("scans") { ScansSection(p, tz) }
        p.travelInbound?.let { t -> section("travel_in") { TravelSection(t, tz, state.canViewPii) } }
        p.travelOutbound?.let { t -> section("travel_out") { TravelSection(t, tz, state.canViewPii) } }
        section("personal") { PersonalSection(p) }
        section("accommodation") { AccommodationSection(p) }
        if (state.canViewSensitive) {
            section("medical") { MedicalSection(p) }
            section("access") { AccessibilitySection(p.accessibility) }
        }
        section("safeguarding") { SafeguardingSection(p, state.canViewSensitive) }
        section("guardians") { GuardiansSection(p, callbacks.contact) }
        section("consents") { ConsentsSection(p.consents, tz, callbacks.contact) }
        section("groups") { GroupsSection(p) }
        if (!state.notesHidden) {
            section("notes") {
                NotesSection(state.notes, state.notesError, now, callbacks.addNote, callbacks.retryNote, callbacks.discardNote)
            }
        }
    }
}

/** A detail section that animates in, out, to its new place, and as its own content grows or shrinks. */
private fun LazyListScope.section(key: String, content: @Composable () -> Unit) = item(key = key) {
    Box(Modifier.fillMaxWidth().animateItem().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) { content() }
}

@Composable
private fun Header(p: Participant, tz: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The expressive cookie is the photo's mask, not a frame around a circle.
        Avatar(p.fullName ?: p.name, p.headshotUrl, size = 136.dp, shape = MaterialShapes.Cookie9Sided.toShape())
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
        val motion = MaterialTheme.motionScheme
        // "Check in" and "Undo check-in" crossfade as the status flips.
        AnimatedContent(
            targetState = p.isCheckedIn,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.96f)) togetherWith
                    fadeOut(motion.fastEffectsSpec()) using SizeTransform(clip = false)
            },
            label = "checkInButtons",
        ) { checkedIn -> CheckInButtons(state, p, checkedIn, callbacks, onDialog) }
        val phone = p.phone?.takeIf { state.canViewPii && it.isNotBlank() }
        // Whatever email the server sends is already on screen, so mailing it needs no extra permission.
        val email = p.email?.takeIf { it.isNotBlank() }
        val slack = p.slackUserId?.takeIf { it.isNotBlank() }
        val reach = buildList {
            if (phone != null) {
                add(QuickAction(Icons.Outlined.Call, "Call") { callbacks.contact.call(phone) })
                add(QuickAction(Icons.Outlined.Sms, "Message") { callbacks.contact.sms(phone) })
                add(QuickAction(Icons.AutoMirrored.Outlined.Chat, "WhatsApp") { callbacks.contact.whatsApp(phone) })
            }
            if (email != null) add(QuickAction(Icons.Outlined.Email, "Email") { callbacks.contact.email(email) })
            if (slack != null) add(QuickAction(Icons.Outlined.Tag, "Slack") { callbacks.contact.slack(slack) })
        }
        val manage = buildList {
            add(QuickAction(Icons.Outlined.Nfc, "Badge", busy = state.busy == DetailBusy.ResettingBadge, onClick = callbacks.writeBadge))
            // Only once the full profile is here: the roster copy has no legal names or birthday.
            if (state.canEditDetails && state.detailLoaded) add(QuickAction(Icons.Outlined.Edit, "Edit", onClick = callbacks.editDetails))
            if (state.event != null) add(QuickAction(Icons.AutoMirrored.Outlined.OpenInNew, "Web", onClick = callbacks.openWeb))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (reach.isNotEmpty()) QuickActionGroup(reach, stacked = true)
            QuickActionGroup(manage, stacked = false)
        }
    }
}

private class QuickAction(val icon: ImageVector, val label: String, val busy: Boolean = false, val onClick: () -> Unit)

/**
 * An expressive button group, like Home's quick actions: pill buttons share the row, the pressed one
 * squares off and pushes its neighbours aside, and any that don't fit move into an overflow menu.
 * [stacked]: tall tonal buttons with the label under the icon (ways to reach them); otherwise a
 * quieter row with the label beside the icon (things to do with the record).
 */
@Composable
private fun QuickActionGroup(actions: List<QuickAction>, stacked: Boolean) {
    ButtonGroup(
        overflowIndicator = { menu -> ButtonGroupDefaults.OverflowIndicator(menu) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        actions.forEach { a ->
            customItem(
                buttonGroupContent = {
                    val source = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = a.onClick,
                        enabled = !a.busy,
                        shapes = ButtonDefaults.shapes(),
                        colors = if (stacked) ButtonDefaults.filledTonalButtonColors()
                        else ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        interactionSource = source,
                        contentPadding = if (stacked) PaddingValues(horizontal = 2.dp, vertical = 12.dp) else PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.weight(1f).animateWidth(source).heightIn(min = if (stacked) 76.dp else 56.dp),
                    ) {
                        val icon: @Composable () -> Unit = {
                            if (a.busy) LoadingIndicator(Modifier.size(24.dp)) else Icon(a.icon, null, Modifier.size(24.dp))
                        }
                        // Five tall pills share a phone's width: a size down keeps "WhatsApp" whole.
                        val style = if (stacked && actions.size > 4) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                        val label: @Composable () -> Unit = {
                            Text(a.label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (stacked) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                icon()
                                Spacer(Modifier.height(4.dp))
                                label()
                            }
                        } else {
                            icon()
                            Spacer(Modifier.width(8.dp))
                            label()
                        }
                    }
                },
                menuContent = { menu ->
                    DropdownMenuItem(
                        text = { Text(a.label) },
                        leadingIcon = { Icon(a.icon, null) },
                        enabled = !a.busy,
                        onClick = { menu.dismiss(); a.onClick() },
                    )
                },
            )
        }
    }
}

@Composable
private fun CheckInButtons(state: DetailUiState, p: Participant, checkedIn: Boolean, callbacks: DetailCallbacks, onDialog: (String) -> Unit) {
    val busy = state.busy
    val options = state.checkInContexts
    val default = state.defaultCheckInContext
    if (checkedIn) {
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
