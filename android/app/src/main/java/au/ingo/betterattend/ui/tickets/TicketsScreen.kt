package au.ingo.betterattend.ui.tickets

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.AppContainer
import au.ingo.betterattend.data.api.friendlyMessage
import au.ingo.betterattend.data.model.Ticket
import au.ingo.betterattend.data.model.User
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.AccountButton
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.SectionHeader
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.Instant

// ---------- State ----------

class TicketsViewModel(private val container: AppContainer) : ViewModel() {
    val tickets = container.tickets.tickets
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var lastRefresh = 0L

    /** Refreshes unless we did so very recently (tab switches shouldn't hammer the API). */
    fun refresh(force: Boolean = false) {
        if (_refreshing.value) return
        if (!force && System.currentTimeMillis() - lastRefresh < 60_000) return
        viewModelScope.launch {
            _refreshing.value = true
            container.tickets.refresh()
                .onSuccess { _error.value = null; lastRefresh = System.currentTimeMillis() }
                .onFailure { _error.value = it.friendlyMessage }
            _refreshing.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TicketsViewModel(container) as T
        }
    }
}

@Composable
fun TicketsScreen(nav: AppNavigator, showAccount: Boolean) {
    val container = LocalAppContainer.current
    val vm: TicketsViewModel = viewModel(factory = TicketsViewModel.factory(container))
    val tickets by vm.tickets.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }

    TicketsContent(
        tickets = tickets,
        refreshing = refreshing,
        error = error,
        user = container.auth.currentUser,
        showAccount = showAccount,
        now = now,
        onRefresh = { vm.refresh(force = true) },
        onOpen = { t ->
            if (t.confirmed) nav.openTicket(t.id)
            else {
                val url = t.onboardingUrl
                if (url == null || !context.openWeb(url)) {
                    Toast.makeText(context, "Couldn't open your registration. Check you have a web browser installed.", Toast.LENGTH_LONG).show()
                }
            }
        },
        onAccount = nav::openSettings,
    )
}

// ---------- Content ----------

@Composable
fun TicketsContent(
    tickets: List<Ticket>?,
    refreshing: Boolean,
    error: String?,
    user: User?,
    showAccount: Boolean,
    now: Instant,
    onRefresh: () -> Unit,
    onOpen: (Ticket) -> Unit,
    onAccount: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val (current, past) = remember(tickets, now) { TicketLogic.sorted(tickets.orEmpty(), now) }
    val haptics = rememberHaptics()
    val open: (Ticket) -> Unit = { haptics.click(); onOpen(it) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("My tickets") },
                subtitle = when {
                    tickets.isNullOrEmpty() -> null
                    current.isEmpty() -> ({ Text("No upcoming events") })
                    else -> ({ Text(if (current.size == 1) "1 upcoming event" else "${current.size} upcoming events") })
                },
                actions = { if (showAccount) AccountButton(user, onAccount) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        // Same feel as HapticPullToRefreshBox (kept a plain PullToRefreshBox for the expressive indicator).
        LaunchedEffect(pullState) {
            snapshotFlow { pullState.distanceFraction >= 1f }.distinctUntilChanged().drop(1).collect { armed ->
                if (armed) haptics.threshold() else haptics.frequentTick()
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing && tickets != null,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = refreshing && tickets != null,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            val phase = when {
                tickets.isNullOrEmpty() && error != null && !refreshing -> ListPhase.Failed
                tickets == null -> ListPhase.Loading
                tickets.isEmpty() -> ListPhase.Empty
                else -> ListPhase.Tickets
            }
            Crossfade(phase, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tickets") { shown ->
            when {
                shown == ListPhase.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = "Couldn't load your tickets",
                        body = error ?: "Something went wrong.",
                        actionLabel = "Try again",
                        onAction = onRefresh,
                    )
                }
                shown == ListPhase.Loading || tickets == null -> LoadingState(message = "Fetching your tickets…")
                shown == ListPhase.Empty || tickets.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.ConfirmationNumber,
                            title = "No tickets yet",
                            body = "When you register for a Hack Club event, your ticket shows up here. It's saved on your phone, so it works even without signal at the door.",
                        )
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (error != null) {
                        item(key = "offline") {
                            OfflineBanner("You're offline. Showing your saved tickets — QR codes still work.", Modifier.animateItem(), onRetry = onRefresh)
                        }
                    }
                    items(current, key = { it.id }) { t ->
                        TicketCard(t, now, featured = t.id == current.firstOrNull()?.id, onClick = { open(t) }, modifier = Modifier.animateItem())
                    }
                    if (past.isNotEmpty()) {
                        item(key = "past_header") { SectionHeader("Past events", Modifier.animateItem()) }
                        items(past, key = { it.id }) { t -> TicketCard(t, now, featured = false, past = true, onClick = { open(t) }, modifier = Modifier.animateItem()) }
                    }
                }
            }
            }
        }
    }
}

private enum class ListPhase { Failed, Loading, Empty, Tickets }

@Composable
private fun TicketCard(t: Ticket, now: Instant, featured: Boolean, onClick: () -> Unit, past: Boolean = false, modifier: Modifier = Modifier) {
    val status = TicketLogic.status(t)
    val cs = MaterialTheme.colorScheme
    val container = when {
        featured -> cs.primaryContainer
        past -> cs.surfaceContainerLow
        else -> cs.surfaceContainerHigh
    }
    val content = if (featured) cs.onPrimaryContainer else cs.onSurface
    val subtle = if (featured) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant
    val start = Time.zoned(t.event.startsAt, t.event.timezone)
    val action = if (t.confirmed) "Opens your pass" else "Opens registration in your browser"
    Surface(
        onClick = onClick,
        shape = TicketShape(corner = 28.dp, notchRadius = 12.dp, notchFromTop = 104.dp),
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Button; contentDescription = "${t.event.name}. ${statusLabel(status)}. $action" },
    ) {
        Column {
            Row(Modifier.height(104.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                // Calendar block in an expressive shape.
                Box(
                    Modifier.size(64.dp).clip(MaterialShapes.Cookie4Sided.toShape())
                        .background(if (featured) cs.primary else cs.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    val on = if (featured) cs.onPrimary else cs.onSecondaryContainer
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(start?.month?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())?.uppercase() ?: "TBA",
                            style = MaterialTheme.typography.labelSmall, color = on, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(start?.dayOfMonth?.toString() ?: "–", style = MaterialTheme.typography.titleLarge, color = on, fontWeight = FontWeight.Black, lineHeight = 22.sp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.event.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    val dates = Time.range(t.event.startsAt, t.event.endsAt, t.event.timezone) ?: "Dates to be announced"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dates, style = MaterialTheme.typography.bodyMedium, color = subtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        t.event.locationCity?.takeIf { it.isNotBlank() }?.let { city ->
                            Text("  ·  ", color = subtle, style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Outlined.Place, null, Modifier.size(14.dp), tint = subtle)
                            Text(city, style = MaterialTheme.typography.bodyMedium, color = subtle, maxLines = 1)
                        }
                    }
                }
            }
            DashedDivider(color = content.copy(alpha = 0.25f), modifier = Modifier.padding(horizontal = 20.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(status)
                if (!past && status !is TicketLogic.Status.Closed) {
                    CountdownPill(TicketLogic.relativeLabel(t.event, now), live = TicketLogic.countdown(t.event, now) == TicketLogic.Countdown.Live)
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = subtle, modifier = Modifier.size(20.dp))
            }
            if (status is TicketLogic.Status.Incomplete) {
                Surface(
                    color = MaterialTheme.status.warningContainer,
                    contentColor = MaterialTheme.status.onWarningContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.HourglassTop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Tap to finish registration", style = MaterialTheme.typography.labelLarge)
                            Text(status.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(s: TicketLogic.Status) = when (s) {
    TicketLogic.Status.Ready -> "Ready"
    TicketLogic.Status.CheckedIn -> "Checked in"
    is TicketLogic.Status.Incomplete -> s.label
    is TicketLogic.Status.Closed -> s.label
}

@Composable
internal fun StatusPill(s: TicketLogic.Status, modifier: Modifier = Modifier) {
    val st = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    when (s) {
        TicketLogic.Status.Ready -> Pill("Ready", st.successContainer, st.onSuccessContainer, modifier, Icons.Outlined.QrCode2)
        TicketLogic.Status.CheckedIn -> Pill("Checked in", st.success, st.onSuccess, modifier, Icons.Outlined.Check)
        is TicketLogic.Status.Incomplete -> Pill(s.label, st.warningContainer, st.onWarningContainer, modifier, Icons.Outlined.HourglassTop)
        is TicketLogic.Status.Closed -> Pill(s.label, cs.surfaceContainerHighest, cs.onSurfaceVariant, modifier, Icons.Outlined.Block)
    }
}

@Composable
internal fun CountdownPill(label: String, live: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    if (live) Pill(label, cs.tertiary, cs.onTertiary, modifier, Icons.Outlined.Schedule)
    else Pill(label, cs.surfaceContainerHighest, cs.onSurface, modifier, Icons.Outlined.Schedule)
}

@Composable
internal fun DashedDivider(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
        )
    }
}
