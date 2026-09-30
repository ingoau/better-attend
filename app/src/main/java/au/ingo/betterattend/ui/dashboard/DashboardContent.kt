package au.ingo.betterattend.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.Scan
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.model.TravelCalendar
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.ui.components.AccountButton
import au.ingo.betterattend.ui.components.AnimatedNumber
import au.ingo.betterattend.ui.components.HapticPullToRefreshBox
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.MaterialShapesCookie
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.nav.Tab
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.ui.travel.modeIcon
import au.ingo.betterattend.util.Time
import java.time.Instant

@Composable
fun DashboardContent(
    state: DashboardState,
    onRefresh: () -> Unit,
    onPickEvent: () -> Unit,
    onAccount: () -> Unit,
    onSwitchTab: (Tab) -> Unit,
    onOpenParticipant: (participantEventId: String) -> Unit,
    onAnnounce: () -> Unit,
    onKiosk: () -> Unit,
    now: Instant = Instant.now(),
) {
    val event = state.event
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    if (event != null || !state.events.isNullOrEmpty()) EventTitle(event?.name, onPickEvent)
                    else Text("Home")
                },
                subtitle = { if (event != null) EventSubtitle(event, now) },
                actions = { AccountButton(state.user, onAccount) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val phase = when {
            state.events == null -> HomePhase.Loading
            state.events.isEmpty() -> HomePhase.NoEvents
            event == null -> HomePhase.PickEvent
            else -> HomePhase.Content
        }
        Crossfade(
            targetState = phase,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "home",
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
            when {
                shown == HomePhase.Loading || state.events == null -> LoadingState(message = "Loading your events…")
                shown == HomePhase.NoEvents || state.events.isEmpty() -> HapticPullToRefreshBox(isRefreshing = state.userRefreshing, onRefresh = onRefresh) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                Icons.Outlined.EventBusy,
                                "You're not on any events yet",
                                body = "To run check-in, an event admin needs to add you as staff for their event on attend.hackclub.com. " +
                                    "Once they do, it'll show up here.",
                                actionLabel = if (state.user?.isParticipant == true) "Open my tickets" else "Check again",
                                onAction = if (state.user?.isParticipant == true) ({ onSwitchTab(Tab.Tickets) }) else onRefresh,
                            )
                        }
                    }
                }
                shown == HomePhase.PickEvent || event == null -> EmptyState(
                    Icons.Outlined.EventAvailable, "Pick an event",
                    body = "Choose which event you're working on. You can switch any time from the title.",
                    actionLabel = "Choose event", onAction = onPickEvent,
                )
                else -> HapticPullToRefreshBox(isRefreshing = state.userRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    DashboardBody(state, event, now, onRefresh, onSwitchTab, onOpenParticipant, onAnnounce, onKiosk)
                }
            }
            }
        }
    }
}

/** Which top-level state Home is in; changes crossfade instead of cutting. */
private enum class HomePhase { Loading, NoEvents, PickEvent, Content }

/** A Home card that fades/slides into place when it appears, moves or leaves (e.g. the offline banner). */
private fun LazyListScope.card(key: String, content: @Composable () -> Unit) {
    item(key = key) { Box(Modifier.animateItem()) { content() } }
}

@Composable
private fun EventTitle(name: String?, onClick: () -> Unit) {
    Row(
        Modifier.clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Switch event", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Event: ${name ?: "none chosen"}" }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name ?: "Choose an event", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Outlined.ExpandMore, null)
    }
}

@Composable
private fun EventSubtitle(event: Event, now: Instant) {
    val phase = Time.phase(event.startsAt, event.endsAt, now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        DashboardLogic.subtitle(event)?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
        }
        PhasePill(phase)
    }
}

@Composable
private fun PhasePill(phase: Time.Phase) {
    val s = MaterialTheme.status
    when (phase) {
        Time.Phase.Live -> Pill("Live", s.successContainer, s.onSuccessContainer, icon = Icons.Outlined.Sync)
        Time.Phase.Upcoming -> Pill("Upcoming", s.infoContainer, s.onInfoContainer, icon = Icons.Outlined.Schedule)
        Time.Phase.Past -> Pill("Ended", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.TaskAlt)
        Time.Phase.Unknown -> Unit
    }
}

@Composable
private fun DashboardBody(
    state: DashboardState,
    event: Event,
    now: Instant,
    onRefresh: () -> Unit,
    onSwitchTab: (Tab) -> Unit,
    onOpenParticipant: (String) -> Unit,
    onAnnounce: () -> Unit,
    onKiosk: () -> Unit,
) {
    // A roster without `syncedAt` is partial (e.g. a few people learned from scans before the first full sync):
    // counting it would say "3 of 3 checked in", so treat it as not loaded yet.
    val roster = state.roster?.takeIf { it.syncedAt != null }
    val stats = remember(roster, now) { roster?.let { EventStats.from(it.participants, now) } }
    val phase = Time.phase(event.startsAt, event.endsAt, now)
    val arrivals = remember(state.travel, now) { DashboardLogic.arrivals(state.travel, now) }
    val updated = DashboardLogic.updated(state.lastUpdated ?: Time.parse(state.roster?.lastSyncAt), now)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        card(key = "status") { StatusLine(state.refreshing, updated, state.pendingScans) }
        if (state.error != null) {
            card(key = "offline") {
                OfflineBanner(
                    if (roster != null || state.scans != null) "Showing saved numbers. ${state.error}" else state.error,
                    onRetry = onRefresh,
                )
            }
        }

        if (state.canViewParticipants) {
            if (stats == null) {
                card(key = "hero_loading") { HeroLoading(failed = state.error != null && !state.refreshing, onRetry = onRefresh) }
            } else {
                card(key = "hero") { Hero(event, stats, phase, now) }
            }
            card(key = "actions") { QuickActions(showFind = true, onSwitchTab, onAnnounce, onKiosk) }
            if (stats != null) {
                card(key = "tiles") {
                    StatTiles(stats, DashboardLogic.needsAttention(roster!!.participants), onOpenPeople = { onSwitchTab(Tab.People) })
                }
                val progress = DashboardLogic.contextProgress(state.contexts, stats, now)
                // Before doors open every bar is empty, so skip them.
                if (progress.isNotEmpty() && (phase != Time.Phase.Upcoming || progress.any { it.count > 0 })) card(key = "contexts") { ContextsCard(progress) }
            }
            if (arrivals != null) card(key = "arrivals") { ArrivalsCard(arrivals, event.timezone ?: state.travel?.eventTimezone) { onSwitchTab(Tab.Travel) } }
            if (roster != null && phase != Time.Phase.Upcoming) {
                val recent = DashboardLogic.recentCheckIns(roster.participants)
                card(key = "recent") { RecentCheckIns(recent, now, onOpenParticipant, onSeeAll = { onSwitchTab(Tab.People) }) }
            }
        } else {
            card(key = "explain") { LimitedAccessCard(event) }
            card(key = "scans_hero") { ScansHero(state.scans, event.timezone, now) }
            card(key = "actions") { QuickActions(showFind = false, onSwitchTab, onAnnounce, onKiosk) }
            if (arrivals != null) card(key = "arrivals") { ArrivalsCard(arrivals, event.timezone ?: state.travel?.eventTimezone) { onSwitchTab(Tab.Travel) } }
            val scans = state.scans
            if (!scans.isNullOrEmpty()) {
                card(key = "recent_scans") { RecentScans(DashboardLogic.scanFeed(scans, event.timezone, now).recent, state.travel, now) }
            }
        }
    }
}

// ---------------------------------------------------------------- status

@Composable
private fun StatusLine(refreshing: Boolean, updated: String?, pending: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (refreshing) Icons.Outlined.Sync else Icons.Outlined.CheckCircle, null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (refreshing) "Syncing…" else updated ?: "Not synced yet",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (pending > 0) {
            Pill(
                "$pending scan${if (pending == 1) "" else "s"} to sync",
                MaterialTheme.status.warningContainer, MaterialTheme.status.onWarningContainer,
                icon = Icons.Outlined.CloudUpload,
            )
        }
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun HeroCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun Hero(event: Event, stats: EventStats, phase: Time.Phase, now: Instant) {
    val upcoming = phase == Time.Phase.Upcoming
    val value: Int
    val total: Int
    val caption: String
    val eyebrow: String
    val chips = mutableListOf<Pair<ImageVector, String>>()
    if (upcoming) {
        value = stats.confirmed
        total = stats.registered
        caption = "registrations complete"
        eyebrow = DashboardLogic.countdown(event.startsAt, event.timezone, now) ?: "Upcoming"
        Time.dayTime(event.startsAt, event.timezone)?.let { chips += Icons.Outlined.Schedule to "Doors $it" }
        DashboardLogic.notComplete(stats).takeIf { it > 0 }?.let { chips += Icons.Outlined.HourglassTop to "$it still onboarding" }
    } else {
        value = DashboardLogic.checkedInConfirmed(stats)
        total = stats.confirmed
        caption = "checked in"
        eyebrow = if (phase == Time.Phase.Past) DashboardLogic.ended(event.endsAt, event.timezone, now) ?: "Final numbers" else "Check-in"
        chips += Icons.Outlined.PersonSearch to if (stats.notArrived == 0) "Everyone's here" else "${stats.notArrived} not here yet"
        if (phase != Time.Phase.Past) chips += Icons.AutoMirrored.Outlined.TrendingUp to "+${stats.checkedInLastHour} in the last hour"
    }
    val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()
    // The ring sweeps to the new value instead of jumping when a sync brings in more check-ins.
    val ring by animateFloatAsState(fraction, MaterialTheme.motionScheme.slowSpatialSpec(), label = "ring")

    HeroCard {
        Column(Modifier.clearAndSetSemantics {
            contentDescription = "$eyebrow. $value of $total $caption, $percent percent. " + chips.joinToString(". ") { it.second }
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(eyebrow, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedNumber(value, style = MaterialTheme.typography.displayLarge)
                        Text(
                            " / $total", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                    Text(caption, style = MaterialTheme.typography.titleMedium)
                }
                Box(contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val stroke = with(density) { Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round) }
                    CircularWavyProgressIndicator(
                        progress = { ring },
                        modifier = Modifier.size(124.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                        stroke = stroke,
                        trackStroke = stroke,
                    )
                    AnimatedNumber(percent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, suffix = "%")
                }
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { (icon, text) -> HeroChip(icon, text) }
                }
            }
        }
    }
}

@Composable
private fun HeroChip(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    // Tint derived from the hero's own content colour so it stays legible in light and dark.
    val onHero = androidx.compose.material3.LocalContentColor.current
    Surface(shape = CircleShape, color = onHero.copy(alpha = 0.12f), contentColor = onHero, modifier = modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeroLoading(failed: Boolean, onRetry: () -> Unit) {
    HeroCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 124.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (failed) "Couldn't load participants" else "Loading participants…", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (failed) "Numbers will appear once we can reach Attend." else "The first sync downloads the whole roster, so it can take a moment.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (failed) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text("Try again") }
                }
            }
            if (!failed) {
                Spacer(Modifier.width(16.dp))
                LoadingIndicator(Modifier.size(72.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------- quick actions

private data class QuickAction(val label: String, val icon: ImageVector, val primary: Boolean, val onClick: () -> Unit)

@Composable
private fun QuickActions(showFind: Boolean, onSwitchTab: (Tab) -> Unit, onAnnounce: () -> Unit, onKiosk: () -> Unit) {
    val haptics = rememberHaptics()
    val actions = buildList {
        add(QuickAction("Scan", Icons.Outlined.QrCodeScanner, true) { onSwitchTab(Tab.Scan) })
        if (showFind) add(QuickAction("Find", Icons.Outlined.Search, false) { onSwitchTab(Tab.People) })
        add(QuickAction("Announce", Icons.Outlined.Campaign, false, onAnnounce))
        add(QuickAction("Kiosk", Icons.Outlined.StayCurrentPortrait, false, onKiosk))
    }.map { a -> a.copy(onClick = { haptics.click(); a.onClick() }) }
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        actions.forEach { a ->
            customItem(
                buttonGroupContent = {
                    val source = remember { MutableInteractionSource() }
                    val modifier = Modifier.weight(1f).animateWidth(source).heightIn(min = 76.dp)
                    val label: @Composable () -> Unit = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(a.icon, null, Modifier.size(26.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(a.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                    val padding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                    if (a.primary) {
                        Button(onClick = a.onClick, shapes = ButtonDefaults.shapes(), interactionSource = source, contentPadding = padding, modifier = modifier) { label() }
                    } else {
                        FilledTonalButton(onClick = a.onClick, shapes = ButtonDefaults.shapes(), interactionSource = source, contentPadding = padding, modifier = modifier) { label() }
                    }
                },
                menuContent = { menu ->
                    DropdownMenuItem(
                        text = { Text(a.label) },
                        leadingIcon = { Icon(a.icon, null) },
                        onClick = { menu.dismiss(); a.onClick() },
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------- stat tiles

@Composable
private fun StatTiles(stats: EventStats, attention: Int, onOpenPeople: () -> Unit) {
    val haptics = rememberHaptics()
    val onOpenPeople = { haptics.click(); onOpenPeople() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Registered", stats.registered, Icons.Outlined.Groups, "Not withdrawn", Modifier.weight(1f), onOpenPeople)
            StatTile("Confirmed", stats.confirmed, Icons.Outlined.Verified, "Registration complete", Modifier.weight(1f), onOpenPeople)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Not complete", DashboardLogic.notComplete(stats), Icons.Outlined.HourglassTop, "Still onboarding", Modifier.weight(1f), onOpenPeople)
            StatTile("Withdrawn", stats.withdrawn, Icons.Outlined.PersonOff, "Incl. rejected", Modifier.weight(1f), onOpenPeople)
        }
        if (attention > 0) AttentionTile(stats, attention, onOpenPeople)
    }
}

@Composable
private fun StatTile(label: String, value: Int, icon: ImageVector, hint: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.semantics { contentDescription = "$label: $value. $hint" },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            AnimatedNumber(value, style = MaterialTheme.typography.headlineMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AttentionTile(stats: EventStats, attention: Int, onClick: () -> Unit) {
    val s = MaterialTheme.status
    val detail = listOfNotNull(
        stats.anaphylaxis.takeIf { it > 0 }?.let { "$it anaphylaxis risk" },
        stats.highSupport.takeIf { it > 0 }?.let { "$it high support" },
    ).joinToString(" · ")
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = s.dangerContainer,
        contentColor = s.onDangerContainer,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$attention need attention: $detail. Opens People." },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(MaterialShapesCookie).background(s.danger), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.WarningAmber, null, tint = s.onDanger)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("$attention need attention", style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

// ---------------------------------------------------------------- scan contexts

@Composable
private fun ContextsCard(rows: List<ContextProgress>) {
    DashCard(title = "Scan points") {
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            ContextRow(row)
        }
    }
}

@Composable
private fun ContextRow(row: ContextProgress) {
    Column(Modifier.clearAndSetSemantics {
        contentDescription = "${row.context.name}${if (row.active) ", happening now" else ""}: ${row.count} of ${row.total}"
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(contextIcon(row.context), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(row.context.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (row.active) {
                Spacer(Modifier.width(8.dp))
                Pill("Now", MaterialTheme.status.successContainer, MaterialTheme.status.onSuccessContainer)
            }
            Spacer(Modifier.weight(1f))
            Text("${row.count}", style = MaterialTheme.typography.titleMedium)
            Text(" / ${row.total}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (row.active) {
            LinearWavyProgressIndicator(progress = { row.fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { row.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round)
        }
    }
}

private fun contextIcon(c: ScanContext): ImageVector {
    val n = c.name.lowercase()
    return when {
        c.checksIn -> Icons.Outlined.HowToReg
        c.isTravelPickup || c.isAirport -> Icons.Outlined.FlightLand
        listOf("lunch", "dinner", "breakfast", "meal", "snack", "food").any { it in n } -> Icons.Outlined.Restaurant
        else -> Icons.Outlined.TaskAlt
    }
}

// ---------------------------------------------------------------- arrivals

@Composable
private fun ArrivalsCard(a: ArrivalsSummary, tz: String?, onOpen: () -> Unit) {
    val s = MaterialTheme.status
    DashCard(title = "Arrivals", onClick = onOpen, action = "Travel") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("To collect", a.awaitingPickup, s.warningContainer, s.onWarningContainer, Modifier.weight(1f))
            MiniStat("Picked up", a.collected, s.successContainer, s.onSuccessContainer, Modifier.weight(1f))
            MiniStat("Checked in", a.checkedIn, s.infoContainer, s.onInfoContainer, Modifier.weight(1f))
        }
        if (a.next.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Next to collect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.next.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Time.time(e.primaryTimeAt, tz) ?: "—", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(72.dp))
                    Icon(modeIcon(e.mode, e.direction), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (e.isUnaccompaniedMinor) {
                                Spacer(Modifier.width(6.dp))
                                Pill("UM", s.warningContainer, s.onWarningContainer)
                            }
                        }
                        val sub = listOfNotNull(e.route, e.reference).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: Int, container: Color, content: Color, modifier: Modifier) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            AnimatedNumber(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- recent check-ins

@Composable
private fun RecentCheckIns(recent: List<Participant>, now: Instant, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    val haptics = rememberHaptics()
    // People already listed when the card first appeared show straight away; anyone who checks in after that
    // (picked up by a background sync) slides in at the top while the rest of the list eases down.
    val initial = remember { recent.mapTo(HashSet()) { it.participantEventId } }
    DashCard(title = "Recently checked in", action = "People", onAction = onSeeAll) {
        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        if (recent.isEmpty()) {
            Text("No one's checked in yet. Scans at a check-in point show up here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent.forEachIndexed { i, p ->
          key(p.participantEventId) {
            val visible = remember { MutableTransitionState(p.participantEventId in initial).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = visible,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            ) {
            Column {
            if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Surface(
                onClick = { haptics.click(); onOpen(p.participantEventId) },
                color = Color.Transparent,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.name, p.headshotUrl, size = 40.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.fullName ?: p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Checked in ${Time.ago(p.checkedInAt, now)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (p.hasSafetyAlert) {
                        Icon(Icons.Outlined.WarningAmber, "Has a safety alert", tint = MaterialTheme.status.danger, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
            }
          }
        }
        }
    }
}

// ---------------------------------------------------------------- limited-access fallback

@Composable
private fun LimitedAccessCard(event: Event) {
    val s = MaterialTheme.status
    Surface(color = s.infoContainer, contentColor = s.onInfoContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    DashboardLogic.roleLabel(event.role)?.let { "You're $it on this event" } ?: "Limited access",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "You can scan tickets and send announcements, but your role can't see participant details. " +
                        "Home shows live scan activity" + (if (event.travelEnabled) " and travel" else "") + " instead.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ScansHero(scans: List<Scan>?, tz: String?, now: Instant) {
    if (scans == null) {
        HeroLoading(failed = false, onRetry = {})
        return
    }
    val feed = remember(scans, tz, now) { DashboardLogic.scanFeed(scans, tz, now) }
    HeroCard {
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = "${feed.today}${if (feed.capped) " or more" else ""} scans today, ${feed.uniquePeopleToday} people"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                AnimatedNumber(feed.today, style = MaterialTheme.typography.displayLarge, suffix = if (feed.capped) "+" else "")
                Text("scans", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                HeroChip(Icons.Outlined.Groups, "${feed.uniquePeopleToday} people scanned")
            }
            Box(
                Modifier.size(112.dp).clip(MaterialShapesCookie).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun RecentScans(scans: List<Scan>, travel: TravelCalendar?, now: Instant) {
    val names = remember(travel) { travel?.entries?.mapNotNull { e -> e.participantEventId?.let { it to e.name } }?.toMap().orEmpty() }
    DashCard(title = "Latest scans") {
        scans.forEachIndexed { i, scan ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            val who = scan.participantEventId?.let(names::get)
                ?: "Attendee ${(scan.participantId ?: scan.participantEventId)?.substringBefore('-')?.uppercase() ?: ""}".trim()
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val ctx = scan.scanContext
                Icon(
                    when {
                        ctx == null -> Icons.Outlined.QrCodeScanner
                        ctx.checksIn -> Icons.Outlined.HowToReg
                        ctx.isTravelPickup -> Icons.Outlined.FlightLand
                        else -> Icons.Outlined.TaskAlt
                    },
                    null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(who, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(ctx?.name, scan.scannedBy?.let { "by $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                Text(Time.ago(scan.scannedAt, now) ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- shared card

@Composable
private fun DashCard(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val onAction = onAction?.let { go -> { haptics.click(); go() } }
    val onClick = onClick?.let { go -> { haptics.click(); go() } }
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                val go = onAction ?: onClick
                if (action != null && go != null) {
                    TextButton(onClick = go) {
                        Text(action)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            }
            content()
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth(), content = body)
    } else {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth(), content = body)
    }
}
