package au.ingo.betterattend.ui.scan

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.data.auth.AuthState
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.data.store.AppSettings
import au.ingo.betterattend.scan.NfcStatus
import au.ingo.betterattend.scan.NfcSupport
import au.ingo.betterattend.scan.ScanFeedback
import au.ingo.betterattend.scan.rememberNfcReader
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.AccountButton
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.EventSwitcherTitle
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.theme.status
import kotlinx.coroutines.launch

private enum class ScanSheet { Find, Pending, Recent }

/** Callbacks for [ScanContent]; defaults make previews and screenshot tests concise. */
class ScanActions(
    val onPickEvent: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSelectContext: (String) -> Unit = {},
    val onRetryContexts: () -> Unit = {},
    val onDismissCard: () -> Unit = {},
    val onUndo: (ScanCard) -> Unit = {},
    val onRetry: (ScanCard) -> Unit = {},
    val onDetails: ((ScanCard) -> Unit)? = null,
    val onRequestCamera: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onToggleTorch: () -> Unit = {},
    val onOpenNfcSettings: () -> Unit = {},
    val onFindPerson: () -> Unit = {},
    val onRecent: () -> Unit = {},
    val onPending: () -> Unit = {},
    val onKiosk: () -> Unit = {},
    val onToggleSounds: () -> Unit = {},
    val onToggleHaptics: () -> Unit = {},
)

@Composable
fun ScanScreen(nav: AppNavigator) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val vm: ScanViewModel = viewModel(factory = ScanViewModel.Factory(container))

    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val auth by container.auth.state.collectAsStateWithLifecycle()
    val event by vm.event.collectAsStateWithLifecycle()
    val eventsLoading by vm.eventsLoading.collectAsStateWithLifecycle()
    val contexts by vm.contexts.collectAsStateWithLifecycle()
    val contextsError by vm.contextsError.collectAsStateWithLifecycle()
    val selectedContextId by vm.selectedContextId.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val inFlight by vm.inFlight.collectAsStateWithLifecycle()
    val pending by container.scans.pending.collectAsStateWithLifecycle()
    val log by container.scans.log.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    var sheet by rememberSaveable { mutableStateOf<ScanSheet?>(null) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val permission = rememberCameraPermission()

    // Feedback: distinct sound + vibration per outcome, respecting settings.
    val feedback = remember { ScanFeedback(context) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    val currentSettings by rememberUpdatedState(settings)
    LaunchedEffect(vm) { vm.feedback.collect { feedback.play(it, currentSettings.sounds, currentSettings.haptics) } }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Keep the screen awake at the desk.
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // Retry the offline queue whenever the scanner comes back into view.
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }

    val nfc = rememberNfcReader(enabled = event != null && sheet == null, onResult = vm::onNfc)

    val state = ScanUiState(
        eventsLoading = eventsLoading,
        event = event,
        contexts = contexts,
        contextsError = contextsError,
        selectedContextId = selectedContextId,
        card = card,
        pendingCount = pending.size,
        inFlight = inFlight,
        camera = permission.access,
        torchAvailable = torchAvailable,
        torchOn = torchOn,
        nfc = nfc,
        sounds = settings.sounds,
        haptics = settings.haptics,
    )
    val canOpenDetails = event?.canViewParticipants == true

    ScanContent(
        state = state,
        user = (auth as? AuthState.SignedIn)?.user,
        snackbar = snackbar,
        actions = ScanActions(
            onPickEvent = nav::openEventPicker,
            onOpenSettings = nav::openSettings,
            onSelectContext = vm::selectContext,
            onRetryContexts = { vm.refreshContexts() },
            onDismissCard = vm::dismiss,
            onUndo = vm::undo,
            onRetry = vm::retry,
            onDetails = if (canOpenDetails) { c -> c.participant?.let { p -> event?.let { nav.openParticipant(it.id, p.participantEventId) } } } else null,
            onRequestCamera = permission.request,
            onOpenAppSettings = permission.openSettings,
            onToggleTorch = { torchOn = !torchOn },
            onOpenNfcSettings = { NfcSupport.openSettings(context) },
            onFindPerson = { vm.setQuery(""); sheet = ScanSheet.Find },
            onRecent = { sheet = ScanSheet.Recent },
            onPending = { sheet = ScanSheet.Pending },
            onKiosk = { event?.let { nav.openKiosk(it.id, selectedContextId) } },
            onToggleSounds = { scope.launch { container.settings.setSounds(!settings.sounds) } },
            onToggleHaptics = { scope.launch { container.settings.setHaptics(!settings.haptics) } },
        ),
        camera = {
            CameraScanner(
                onCodes = { if (sheet == null) vm.onCameraCodes(it) },
                torchOn = torchOn,
                onTorchAvailable = { torchAvailable = it; if (!it) torchOn = false },
                onError = { scope.launch { snackbar.showSnackbar(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        },
    )

    val tz = event?.timezone
    when (sheet) {
        ScanSheet.Find -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            val ctx = state.selectedContext
            FindPersonContent(
                state = search,
                contextName = ctx?.name,
                checksIn = ctx?.checksIn ?: true,
                timezone = tz,
                selectedContextId = selectedContextId,
                onQuery = vm::setQuery,
                onCheckIn = { p: Participant -> sheet = null; vm.checkInManually(p.participantEventId) },
                onSubmitDirect = { sheet = null; vm.submitDirect(it) },
            )
        }
        ScanSheet.Pending -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            PendingQueueContent(pending, syncing, tz, onSyncNow = vm::syncNow, onDiscard = { vm.discard(it.clientScanId) })
        }
        ScanSheet.Recent -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            RecentScansContent(log, tz, onOpen = { p ->
                sheet = null
                if (canOpenDetails) event?.let { nav.openParticipant(it.id, p.participantEventId) }
            })
        }
        null -> Unit
    }
}

/** Stateless scanner UI. [camera] is the live preview (a dark placeholder in screenshot tests). */
@Composable
fun ScanContent(
    state: ScanUiState,
    user: User?,
    actions: ScanActions,
    camera: @Composable () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { ScanTopBar(state, user, actions) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.event == null && state.eventsLoading -> LoadingState(message = "Loading your events…")
                state.event == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        Icons.Outlined.EventAvailable,
                        "Pick an event to start scanning",
                        body = "Choose the event you're working at. Scans, check-ins and the offline queue are all per event.",
                        actionLabel = "Choose event",
                        onAction = actions.onPickEvent,
                    )
                }
                else -> {
                    ContextSelector(
                        contexts = state.contexts,
                        selectedId = state.selectedContextId,
                        timezone = state.event.timezone,
                        error = state.contextsError,
                        onSelect = actions.onSelectContext,
                        onRetry = actions.onRetryContexts,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                    Viewport(state, actions, camera, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ScanTopBar(state: ScanUiState, user: User?, actions: ScanActions) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            EventSwitcherTitle(
                eventName = state.event?.name,
                subtitle = if (state.event == null) null else state.selectedContext?.name?.let { "Scanning · $it" } ?: "Scanner",
                onClick = actions.onPickEvent,
            )
        },
        actions = {
            if (state.event != null) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Kiosk mode") },
                        leadingIcon = { Icon(Icons.Outlined.TabletAndroid, null) },
                        onClick = { menu = false; actions.onKiosk() },
                    )
                    DropdownMenuItem(
                        text = { Text("Recent scans") },
                        leadingIcon = { Icon(Icons.Outlined.History, null) },
                        onClick = { menu = false; actions.onRecent() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Scan sounds") },
                        leadingIcon = { Icon(Icons.Outlined.VolumeUp, null) },
                        trailingIcon = { Checkbox(checked = state.sounds, onCheckedChange = null) },
                        onClick = actions.onToggleSounds,
                    )
                    DropdownMenuItem(
                        text = { Text("Vibration") },
                        leadingIcon = { Icon(Icons.Outlined.Vibration, null) },
                        trailingIcon = { Checkbox(checked = state.haptics, onCheckedChange = null) },
                        onClick = actions.onToggleHaptics,
                    )
                }
            }
            AccountButton(user, actions.onOpenSettings)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun Viewport(state: ScanUiState, actions: ScanActions, camera: @Composable () -> Unit, modifier: Modifier) {
    val card = state.card
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(CameraBackdrop),
    ) {
        if (state.camera == CameraAccess.Granted) {
            camera()
            val accent = when (card?.kind) {
                null, ResultKind.Checking, ResultKind.Undone -> Color.White
                else -> card.kind.colors().strong
            }
            ScanFrame(accent = accent, verticalBias = if (card != null) 0.3f else 0.42f)
        } else {
            CameraPermissionPanel(state.camera, actions.onRequestCamera, actions.onOpenAppSettings,
                modifier = Modifier.padding(bottom = if (card == null) 88.dp else 0.dp))
        }

        // Status chips.
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = ChipSpacing,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(state)
            when (state.nfc) {
                NfcStatus.Ready -> OverlayChip("NFC", Icons.Outlined.Nfc, a11y = "NFC badge reading is on")
                NfcStatus.Disabled -> OverlayChip("Turn on NFC", Icons.Outlined.Nfc, onClick = actions.onOpenNfcSettings,
                    a11y = "NFC is off. Open settings to turn it on and scan badges.")
                NfcStatus.Unavailable -> Unit
            }
            Spacer(Modifier.weight(1f))
            if (state.pendingCount > 0) {
                OverlayChip(
                    "${state.pendingCount} waiting to sync",
                    Icons.Outlined.CloudQueue,
                    onClick = actions.onPending,
                    container = MaterialTheme.status.infoContainer,
                    content = MaterialTheme.status.onInfoContainer,
                )
            }
        }

        // Result card + controls.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(
                targetState = card,
                contentKey = { it?.key },
                transitionSpec = {
                    (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { it / 3 } + fadeOut())
                },
                label = "result",
                modifier = Modifier.widthIn(max = 560.dp),
            ) { c ->
                if (c != null) {
                    ScanResultCard(
                        card = c,
                        onDismiss = actions.onDismissCard,
                        onUndo = { actions.onUndo(c) },
                        onRetry = { actions.onRetry(c) },
                        onDetails = actions.onDetails?.let { d -> { d(c) } },
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                } else Box(Modifier.fillMaxWidth())
            }
            Controls(state, actions)
        }
    }
}

@Composable
private fun Controls(state: ScanUiState, actions: ScanActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.standardFloatingToolbarColors()) {
            if (state.camera == CameraAccess.Granted) {
                IconToggleButton(checked = state.torchOn, onCheckedChange = { actions.onToggleTorch() }, enabled = state.torchAvailable) {
                    Icon(if (state.torchOn) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                        if (state.torchOn) "Turn torch off" else "Turn torch on")
                }
            }
            IconButton(onClick = actions.onRecent) { Icon(Icons.Outlined.History, "Recent scans") }
        }
        ExtendedFloatingActionButton(
            onClick = actions.onFindPerson,
            icon = { Icon(Icons.Outlined.PersonSearch, null) },
            text = { Text("Find person") },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StatusChip(state: ScanUiState) {
    val s = MaterialTheme.status
    val (label, dot) = when {
        state.inFlight > 0 -> "Checking…" to s.info
        !state.ready -> "Loading…" to MaterialTheme.colorScheme.outline
        state.camera != CameraAccess.Granted -> "Camera off" to MaterialTheme.colorScheme.outline
        else -> "Ready to scan" to s.success
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun OverlayChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null,
    a11y: String? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    val inner: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    val mod = if (a11y != null) Modifier.semantics(mergeDescendants = true) { contentDescription = a11y } else Modifier
    if (onClick != null) Surface(onClick = onClick, color = container, contentColor = content, shape = CircleShape, modifier = mod) { inner() }
    else Surface(color = container, contentColor = content, shape = CircleShape, modifier = mod) { inner() }
}
