package au.ingo.betterattend.ui.tickets

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.Ticket
import au.ingo.betterattend.data.model.TicketMessage
import au.ingo.betterattend.data.model.TicketTravel
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.MaterialShapesCookie
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Callbacks for [TicketDetailContent]. */
class TicketDetailActions(
    val onBack: () -> Unit = {},
    val onShowQr: () -> Unit = {},
    val onWallet: () -> Unit = {},
    val onDirections: () -> Unit = {},
    val onCompleteRegistration: () -> Unit = {},
    val onReportIncident: () -> Unit = {},
    val onCallHotline: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** Where the pass's QR code sits on screen (root coordinates), so full-screen mode can grow out of it. */
    val onQrBounds: (Rect) -> Unit = {},
)

/** Per-ticket screen state; kept per id so swiping between passes doesn't mix up spinners and errors. */
private data class PageState(val loading: Boolean = true, val error: String? = null, val walletLoading: Boolean = false)

/**
 * A participant's pass. With several confirmed tickets they can swipe sideways between them (same order as the
 * tickets list). Each pass refreshes once when first shown; screen brightness follows the visible pass.
 */
@Composable
fun TicketDetailScreen(ticketId: String, nav: AppNavigator) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val tickets by container.tickets.tickets.collectAsStateWithLifecycle()
    val pageIds = remember(tickets, ticketId) { TicketLogic.pagerIds(tickets, ticketId) }
    var visibleId by rememberSaveable(ticketId) { mutableStateOf(ticketId) }
    if (visibleId !in pageIds) visibleId = ticketId
    val visible = tickets?.firstOrNull { it.id == visibleId }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val now by produceState(Instant.now()) { while (true) { delay(1_000); value = Instant.now() } }

    val pages = remember { mutableStateMapOf<String, PageState>() }
    fun page(id: String) = pages[id] ?: PageState()
    fun update(id: String, f: (PageState) -> PageState) { pages[id] = f(page(id)) }
    val listStates = remember { HashMap<String, LazyListState>() }
    fun listState(id: String) = listStates.getOrPut(id) { LazyListState() }
    val qrBounds = remember { HashMap<String, Rect>() }

    fun refresh(id: String) = scope.launch {
        update(id) { it.copy(loading = true) }
        container.tickets.refreshTicket(id)
            .onSuccess { update(id) { it.copy(loading = false, error = null) } }
            .onFailure { e -> update(id) { it.copy(loading = false, error = e.friendlyMessage) } }
    }
    // Each pass refreshes the first time it's shown, not every time it's swiped past.
    val refreshed = remember { HashSet<String>() }
    LaunchedEffect(visibleId) { if (refreshed.add(visibleId)) refresh(visibleId) }

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    fun failed(msg: String) { haptics.reject(); say(msg) }

    fun actionsFor(id: String): TicketDetailActions {
        val ticket = tickets?.firstOrNull { it.id == id }
        return TicketDetailActions(
            onBack = nav::back,
            onShowQr = { haptics.click(); fullScreen = true },
            onWallet = {
                if (!page(id).walletLoading) scope.launch {
                    haptics.click()
                    update(id) { it.copy(walletLoading = true) }
                    container.tickets.googleWalletUrl(id)
                        .onSuccess { url -> if (!context.openUri(url) && !context.openWeb(url)) failed("Couldn't open Google Wallet on this device.") }
                        .onFailure { e ->
                            val api = e as? au.ingo.betterattend.data.api.ApiException
                            failed(if (api?.status == 422) "Google Wallet couldn't create your pass right now. Try again later — your QR code here works either way." else e.friendlyMessage)
                        }
                    update(id) { it.copy(walletLoading = false) }
                }
            },
            onDirections = {
                haptics.click()
                val e = ticket?.event
                val ok = e != null && (TicketLogic.geoUri(e)?.let(context::openUri) == true || TicketLogic.webDirectionsUrl(e)?.let(context::openWeb) == true)
                if (!ok) failed("No maps app found for directions.")
            },
            onCompleteRegistration = {
                haptics.click()
                val url = ticket?.onboardingUrl
                if (url == null || !context.openWeb(url)) failed("Couldn't open your registration. Check you have a web browser installed.")
            },
            onReportIncident = { haptics.click(); if (!context.openWeb(TicketLogic.INCIDENT_URL)) failed("Couldn't open the incident form.") },
            onCallHotline = { haptics.click(); if (!context.openUri(TicketLogic.HOTLINE_TEL)) failed("Call ${TicketLogic.HOTLINE_DISPLAY} from any phone.") },
            onRetry = { haptics.click(); refresh(id) },
            onQrBounds = { qrBounds[id] = it },
        )
    }

    // A fresh pager state whenever the set of passes changes (e.g. the list finishes loading), opened on the
    // pass being looked at so nothing jumps.
    val pager = remember(pageIds) { PagerState(currentPage = pageIds.indexOf(visibleId).coerceAtLeast(0)) { pageIds.size } }
    val currentIds by rememberUpdatedState(pageIds)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().drop(1).collect { p ->
            currentIds.getOrNull(p)?.let { visibleId = it }
            haptics.tick()
        }
    }

    val overlayOpen = fullScreen && visible?.confirmed == true
    val qrOnScreen by remember { derivedStateOf { listState(visibleId).firstVisibleItemIndex == 0 } }
    BrightScreenEffect(enabled = visible?.confirmed == true && (overlayOpen || qrOnScreen))

    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.positionInRoot() }) {
        val visibleActions = actionsFor(visibleId)
        TicketDetailScaffold(
            ticket = visible,
            actions = visibleActions,
            snackbar = snackbar,
            subtitle = if (pageIds.size > 1) "${pager.currentPage + 1} of ${pageIds.size}" else null,
            // The pass underneath is hidden from accessibility while the full-screen QR is up.
            modifier = if (overlayOpen) Modifier.clearAndSetSemantics { } else Modifier,
        ) { padding ->
            HorizontalPager(
                state = pager,
                key = { pageIds.getOrElse(it) { "gone_$it" } },
                userScrollEnabled = !overlayOpen,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { index ->
                val id = pageIds.getOrElse(index) { ticketId }
                val state = page(id)
                TicketDetailBody(
                    ticket = tickets?.firstOrNull { it.id == id },
                    loading = state.loading,
                    error = state.error,
                    now = now,
                    walletLoading = state.walletLoading,
                    actions = if (id == visibleId) visibleActions else actionsFor(id),
                    listState = listState(id),
                )
            }
        }
        QrOverlay(
            ticket = visible?.takeIf { it.confirmed },
            open = overlayOpen,
            onClose = { fullScreen = false },
            // Grow out of the pass's QR when it's on screen; otherwise just from the middle.
            origin = { if (qrOnScreen) qrBounds[visibleId]?.translate(-screenOrigin) else null },
        )
    }
}

/** A single pass with its top bar; screenshot tests and previews use this. */
@Composable
fun TicketDetailContent(
    ticket: Ticket?,
    loading: Boolean,
    error: String?,
    now: Instant,
    walletLoading: Boolean = false,
    actions: TicketDetailActions = TicketDetailActions(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    listState: LazyListState = rememberLazyListState(),
) {
    TicketDetailScaffold(ticket, actions, snackbar) { padding ->
        TicketDetailBody(ticket, loading, error, now, walletLoading, actions, listState, Modifier.padding(padding))
    }
}

@Composable
private fun TicketDetailScaffold(
    ticket: Ticket?,
    actions: TicketDetailActions,
    snackbar: SnackbarHostState,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val title: @Composable () -> Unit = { Text(if (ticket?.confirmed == false) "Registration" else "Your pass", maxLines = 1) }
            val nav: @Composable () -> Unit = {
                IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }
            val barActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                if (ticket?.confirmed == true) {
                    IconButton(onClick = actions.onShowQr) { Icon(Icons.Outlined.Fullscreen, "Show QR code full screen") }
                }
            }
            val colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            if (subtitle != null) {
                TopAppBar(
                    title = title,
                    subtitle = {
                        Text(subtitle, modifier = Modifier.semantics { contentDescription = "Pass $subtitle. Swipe sideways for your other passes." })
                    },
                    navigationIcon = nav, actions = barActions, colors = colors,
                )
            } else {
                TopAppBar(title = title, navigationIcon = nav, actions = barActions, colors = colors)
            }
        },
        content = content,
    )
}

private enum class DetailPhase { Loading, Failed, Pass }

@Composable
private fun TicketDetailBody(
    ticket: Ticket?,
    loading: Boolean,
    error: String?,
    now: Instant,
    walletLoading: Boolean,
    actions: TicketDetailActions,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val phase = when {
        ticket == null && loading -> DetailPhase.Loading
        ticket == null -> DetailPhase.Failed
        else -> DetailPhase.Pass
    }
    Crossfade(phase, modifier.fillMaxSize(), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "ticket") { shown ->
        when {
            shown == DetailPhase.Loading -> LoadingState(message = "Loading your ticket…")
            shown == DetailPhase.Failed || ticket == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Couldn't load this ticket",
                    body = error ?: "It may have been removed.",
                    actionLabel = "Try again",
                    onAction = actions.onRetry,
                )
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "pass") { PassCard(ticket, actions) }
                    if (error != null) {
                        item(key = "offline") { OfflineBanner("Couldn't refresh — showing your saved pass. It still works offline.", Modifier.animateItem(), onRetry = actions.onRetry) }
                    }
                    if (ticket.confirmed) item(key = "wallet") { WalletButton(walletLoading, actions.onWallet, Modifier.animateItem()) }
                    item(key = "countdown") { CountdownCard(ticket, now) }
                    item(key = "venue") { VenueCard(ticket, actions.onDirections) }
                    ticket.travelInbound?.let { tr -> item(key = "travel") { TravelCard(tr, ticket.event.timezone, Modifier.animateItem()) } }
                    if (ticket.messages.isNotEmpty()) item(key = "messages") { MessagesCard(ticket.messages, now, Modifier.animateItem()) }
                    item(key = "safety") { SafetyCard(actions) }
                }
            }
        }
    }
}

// ---------- Pass card ----------

@Composable
private fun PassCard(t: Ticket, actions: TicketDetailActions) {
    val cs = MaterialTheme.colorScheme
    val status = TicketLogic.status(t)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Notches line up with the perforation wherever it ends up after layout.
    var perforationY by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
    Surface(
        shape = TicketShape(corner = 32.dp, notchRadius = 14.dp, notchFromTop = perforationY, notchFraction = 0.78f),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Header band
            Column(
                Modifier.fillMaxWidth().background(cs.primary).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ATTENDEE PASS", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary.copy(alpha = 0.85f), letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    t.shortCode?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = cs.onPrimary, letterSpacing = 1.sp,
                            modifier = Modifier.semantics { contentDescription = "Short code ${it.toCharArray().joinToString(" ")}" })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(t.event.name, style = MaterialTheme.typography.headlineMedium, color = cs.onPrimary, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.semantics { heading() })
                t.attendeeName?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.titleMedium, color = cs.onPrimary.copy(alpha = 0.9f))
                }
            }
            // Body
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (t.confirmed) {
                    Surface(
                        onClick = actions.onShowQr,
                        shape = RoundedCornerShape(24.dp),
                        color = Color.White, // QR always black on white for scanners
                        shadowElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth()
                            .onGloballyPositioned { actions.onQrBounds(Rect(it.positionInRoot(), it.size.toSize())) }
                            .semantics { role = Role.Button; contentDescription = "Check-in QR code. Tap to enlarge" },
                    ) {
                        QrCode(t.qrPayload, Modifier.padding(12.dp).fillMaxWidth())
                    }
                    Spacer(Modifier.height(14.dp))
                    if (status == TicketLogic.Status.CheckedIn) {
                        Pill("You're checked in", MaterialTheme.status.success, MaterialTheme.status.onSuccess, icon = Icons.Outlined.CheckCircle)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Show this at check-in · tap to enlarge", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                } else {
                    Box(
                        Modifier.size(88.dp).clip(MaterialShapesCookie).background(MaterialTheme.status.warningContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.HourglassTop, null, Modifier.size(40.dp), tint = MaterialTheme.status.onWarningContainer) }
                    Spacer(Modifier.height(16.dp))
                    StatusPill(status)
                    Spacer(Modifier.height(12.dp))
                    Text("Your pass unlocks once registration is complete.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    val reason = when (status) {
                        is TicketLogic.Status.Incomplete -> status.reason
                        is TicketLogic.Status.Closed -> status.reason
                        else -> ""
                    }
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (status is TicketLogic.Status.Incomplete && t.onboardingUrl != null) {
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = actions.onCompleteRegistration, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 52.dp)) {
                            Text("Complete registration")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            DashedDivider(
                cs.onSurface.copy(alpha = 0.2f),
                Modifier.padding(horizontal = 28.dp).onGloballyPositioned { c ->
                    val y = c.positionInParent().y + c.size.height / 2f
                    perforationY = with(density) { y.toDp() }
                },
            )
            // Stub tiles
            val (venue, _) = TicketLogic.venueLines(t.event)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StubTile("VENUE", t.event.locationCity?.takeIf { it.isNotBlank() } ?: venue, t.event.locationCountry, Modifier.weight(1f))
                StubTile("DOORS", Time.time(t.event.startsAt, t.event.timezone) ?: "TBA", Time.day(t.event.startsAt, t.event.timezone), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StubTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun WalletButton(loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        if (loading) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        else Icon(Icons.Outlined.AccountBalanceWallet, null)
        Spacer(Modifier.width(10.dp))
        Text(if (loading) "Preparing your pass…" else "Add to Google Wallet", style = MaterialTheme.typography.titleSmall)
    }
}

// ---------- Info cards ----------

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = container, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = accent)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = accent, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun CountdownCard(t: Ticket, now: Instant) {
    val cs = MaterialTheme.colorScheme
    val cd = TicketLogic.countdown(t.event, now)
    val live = cd == TicketLogic.Countdown.Live
    InfoCard(
        icon = if (live) Icons.Outlined.Celebration else Icons.Outlined.Event,
        title = when (cd) {
            is TicketLogic.Countdown.Upcoming -> "Countdown"
            TicketLogic.Countdown.Live -> "Happening now"
            TicketLogic.Countdown.Ended -> "Event ended"
            TicketLogic.Countdown.Unknown -> "When"
        },
        container = if (live) cs.tertiaryContainer else cs.surfaceContainer,
        accent = if (live) cs.onTertiaryContainer else cs.primary,
    ) {
        when (cd) {
            is TicketLogic.Countdown.Upcoming -> {
                Text(TicketLogic.clock(cd.remaining), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black,
                    modifier = Modifier.semantics { contentDescription = TicketLogic.relativeLabel(t.event, now) })
                Text("until doors open", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
            TicketLogic.Countdown.Live -> {
                Text("Enjoy the event!", style = MaterialTheme.typography.headlineSmall, color = cs.onTertiaryContainer)
            }
            TicketLogic.Countdown.Ended -> Text("Thanks for coming! 👋", style = MaterialTheme.typography.titleMedium)
            TicketLogic.Countdown.Unknown -> Text("Dates to be announced", style = MaterialTheme.typography.titleMedium)
        }
        val tz = t.event.timezone
        if (t.event.startsAt != null) {
            Spacer(Modifier.height(14.dp))
            val content = if (live) cs.onTertiaryContainer else cs.onSurface
            DateRow("Starts", Time.dayTime(t.event.startsAt, tz), content)
            Time.dayTime(t.event.endsAt, tz)?.let { DateRow("Ends", it, content) }
            val zone = Time.zone(tz)
            if (tz != null && zone.rules != ZoneId.systemDefault().rules) {
                Spacer(Modifier.height(4.dp))
                Text("Times shown in ${t.event.locationCity ?: zone.id} time", style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun DateRow(label: String, value: String?, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.75f), modifier = Modifier.width(56.dp))
        Text(value ?: "TBA", style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun VenueCard(t: Ticket, onDirections: () -> Unit) {
    val (line1, line2) = TicketLogic.venueLines(t.event)
    InfoCard(Icons.Outlined.Place, "Venue") {
        Text(line1, style = MaterialTheme.typography.titleMedium)
        if (line2 != null) Text(line2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (TicketLogic.hasVenue(t.event)) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onDirections, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Directions, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Directions")
            }
        }
    }
}

private fun modeIcon(mode: String?): ImageVector = when (mode) {
    "train" -> Icons.Outlined.Train
    "car" -> Icons.Outlined.DirectionsCar
    "bus" -> Icons.Outlined.DirectionsBus
    else -> Icons.Outlined.Flight
}

@Composable
private fun TravelCard(tr: TicketTravel, tz: String?, modifier: Modifier = Modifier) {
    InfoCard(modeIcon(tr.mode), "Arriving", modifier) {
        if (tr.legs.isNotEmpty()) {
            tr.legs.forEachIndexed { i, leg ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${leg.departureAirport ?: "?"}  →  ${leg.arrivalAirport ?: "?"}", style = MaterialTheme.typography.titleMedium)
                        val dep = Time.dayTime(leg.departureTime, tz)
                        val arr = Time.time(leg.arrivalTime, tz)
                        Text(listOfNotNull(dep?.let { "Departs $it" }, arr?.let { "arrives $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    leg.flightCode?.let {
                        Pill(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        } else {
            val title = listOfNotNull(tr.carrier, tr.flightNumber).joinToString(" ").ifBlank { tr.mode?.replaceFirstChar { it.uppercase() } ?: "Travel" }
            Text(title, style = MaterialTheme.typography.titleMedium)
            val route = listOfNotNull(tr.departureCity, tr.arrivalCity).joinToString("  →  ")
            if (route.isNotBlank()) Text(route, style = MaterialTheme.typography.bodyMedium)
            val times = listOfNotNull(Time.dayTime(tr.departureTime, tz)?.let { "Departs $it" }, Time.time(tr.arrivalTime, tz)?.let { "arrives $it" }).joinToString(" · ")
            if (times.isNotBlank()) Text(times, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text("Times in the event's local time. The event team knows your plans.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Server message bodies are HTML; show them as plain text (no WebView, no remote content). */
internal fun htmlToText(html: String?): String =
    html?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty()

@Composable
private fun MessagesCard(messages: List<TicketMessage>, now: Instant, modifier: Modifier = Modifier) {
    InfoCard(Icons.Outlined.Mail, if (messages.size == 1) "Message from the organisers" else "Messages from the organisers", modifier) {
        messages.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                listOfNotNull(m.senderName ?: "Event team", Time.ago(m.deliveredAt, now)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            m.subject?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            val body = htmlToText(m.body)
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SafetyCard(actions: TicketDetailActions) {
    InfoCard(Icons.Outlined.HealthAndSafety, "Safety") {
        Text("If something's wrong at the event, you can always get help — anonymously if you prefer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        SafetyRow(Icons.Outlined.ReportProblem, "Report an incident", "hack.club/incident", actions.onReportIncident)
        SafetyRow(Icons.Outlined.Call, "24/7 event hotline", TicketLogic.HOTLINE_DISPLAY, actions.onCallHotline)
    }
}

@Composable
private fun SafetyRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(MaterialShapes.Circle.toShape()).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Full-screen QR ----------

/**
 * Door mode: the biggest possible QR on pure white, with name and short code as a fallback for
 * staff to type. Deliberately theme-independent — scanners need maximum contrast.
 */
@Composable
fun FullScreenQr(t: Ticket, onClose: () -> Unit) {
    val ink = Color(0xFF121217)
    Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing)) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Outlined.Close, "Close full-screen QR", tint = ink)
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(t.event.name, style = MaterialTheme.typography.titleMedium, color = ink.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(t.attendeeName ?: "Attendee", style = MaterialTheme.typography.headlineMedium, color = ink, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            QrCode(t.qrPayload, Modifier.widthIn(max = 420.dp).fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            t.shortCode?.let {
                Surface(color = Color(0xFFF1F1F4), shape = RoundedCornerShape(16.dp)) {
                    Text(it, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = ink, letterSpacing = 4.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { contentDescription = "Short code ${it.toCharArray().joinToString(" ")}" })
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                if (t.checkedIn) "You're checked in ✓" else "Brightness turned up for scanning",
                style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.6f), textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Hosts [FullScreenQr] over the pass. Opening grows it out of the pass's QR code ([origin], in this box's
 * coordinates; null grows it from the middle). A predictive back gesture shrinks it towards ~90 % with rounded
 * corners and a slight fade, following the finger; letting go closes it back into the QR, cancelling springs back.
 */
@Composable
private fun QrOverlay(ticket: Ticket?, open: Boolean, onClose: () -> Unit, origin: () -> Rect?) {
    val appear = remember { Animatable(if (open) 1f else 0f) }
    val back = remember { Animatable(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val openSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val closeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val cancelSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    // Remember what was shown so the closing animation still has something to draw.
    var shown by remember { mutableStateOf(ticket) }
    if (open && ticket != null) shown = ticket

    LaunchedEffect(open) {
        if (open) {
            back.snapTo(0f)
            appear.animateTo(1f, openSpec)
        } else {
            appear.animateTo(0f, closeSpec)
            back.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = open) { events ->
        try {
            events.collect { e ->
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                back.snapTo(e.progress)
            }
            haptics.gestureEnd()
            onClose()
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, cancelSpec) }
            throw e
        }
    }

    val visible by remember { derivedStateOf { appear.value > 0.001f } }
    val t = shown
    if (t == null || (!open && !visible)) return

    QrOverlayFrame(t, appear = { appear.value }, back = { back.value }, fromLeft = { fromLeft }, origin = origin, onClose = onClose)
}

/**
 * The full-screen QR drawn at a point of its open/close animation ([appear], 0 = collapsed into the pass's QR,
 * 1 = open) and of a predictive back gesture ([back], 0..1). Lambdas so the animation only redraws, never recomposes.
 */
@Composable
internal fun QrOverlayFrame(
    ticket: Ticket,
    appear: () -> Float,
    back: () -> Float,
    fromLeft: () -> Boolean,
    origin: () -> Rect?,
    onClose: () -> Unit,
) {
    // Dim the pass behind while the QR is up, so the shrinking card reads as floating above it.
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear().coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.4f)))
    Box(
        Modifier.fillMaxSize()
            // Swallow touches so nothing underneath reacts while the QR is up.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .graphicsLayer {
                val a = appear()
                val b = back().coerceIn(0f, 1f)
                val from = origin()
                val startScale = from?.let { (it.width / size.width).coerceIn(0.2f, 1f) } ?: 0.85f
                val scale = lerp(startScale, 1f, a) * lerp(1f, 0.9f, b)
                scaleX = scale
                scaleY = scale
                // Travel from the QR's centre to the middle of the screen as it opens.
                val dx = from?.let { it.center.x - size.width / 2f } ?: 0f
                val dy = from?.let { it.center.y - size.height / 2f } ?: 0f
                // During the gesture, drift a little away from the edge being swiped from.
                translationX = dx * (1f - a) + (if (fromLeft()) 1f else -1f) * 12.dp.toPx() * b
                translationY = dy * (1f - a)
                // The white card turns opaque quickly so the pass never shows through the QR; see the content fade below.
                alpha = (a * 3f).coerceIn(0f, 1f)
                // Corners: rounded like the QR tile while small, square when open, rounding again with the gesture.
                // Divided by the scale so the radius looks the same on screen at any size.
                val radius = maxOf(lerp(24.dp.toPx(), 0f, a.coerceIn(0f, 1f)), 32.dp.toPx() * b)
                shape = RoundedCornerShape(radius / scale.coerceAtLeast(0.01f))
                clip = true
            }
            .background(Color.White),
    ) {
        // The QR and text fade in a touch behind the card, and dim slightly as a back gesture progresses.
        Box(Modifier.graphicsLayer { alpha = (appear() * 1.6f).coerceIn(0f, 1f) * lerp(1f, 0.8f, back().coerceIn(0f, 1f)) }) {
            FullScreenQr(ticket, onClose = onClose)
        }
    }
}
