package au.ingo.betterattend.ui.travel

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Commute
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.TravelEntry
import au.ingo.betterattend.ui.components.SegmentedItem
import au.ingo.betterattend.ui.components.ProvideEntranceStagger
import au.ingo.betterattend.ui.components.staggeredEntrance
import au.ingo.betterattend.ui.components.MaterialShapesCookie4
import au.ingo.betterattend.ui.components.MaterialShapesClover
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.HapticPullToRefreshBox
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun TravelContent(
    state: TravelUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: TravelFilter,
    onFilterChange: (TravelFilter) -> Unit,
    mode: TravelMode?,
    onModeChange: (TravelMode?) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (TravelEntry) -> Unit,
    onPickEvent: () -> Unit,
    now: Instant = Instant.now(),
) {
    val event = state.event
    val cal = state.calendar
    val tz = cal?.eventTimezone ?: event?.timezone
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            // A large title that collapses as the list scrolls.
            LargeFlexibleTopAppBar(
                title = { Text("Travel") },
                subtitle = { if (event != null) Text(event.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    val label = TravelLogic.zoneLabel(tz, now)
                    if (label != null) {
                        val differs = TravelLogic.deviceZoneDiffers(tz, now)
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    snackbar.showSnackbar(
                                        "Times are shown in the event's timezone ($tz)" +
                                            if (differs) ", not your phone's." else ".",
                                    )
                                }
                            },
                            label = { Text(label) },
                            leadingIcon = { Icon(if (differs) Icons.Outlined.Public else Icons.Outlined.Schedule, null, Modifier.size(AssistChipDefaults.IconSize)) },
                            modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "Times shown in $tz" },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val phase = when {
            event == null -> TravelPhase.NoEvent
            !event.travelEnabled -> TravelPhase.Disabled
            cal == null && state.error != null -> TravelPhase.Failed
            cal == null -> TravelPhase.Loading
            else -> TravelPhase.Content
        }
        Crossfade(phase, Modifier.fillMaxSize().padding(padding), MaterialTheme.motionScheme.defaultEffectsSpec(), label = "travel") { shown ->
            Box(Modifier.fillMaxSize()) {
            // `shown` is what this layer of the crossfade draws; the live state fills in its details.
            when {
                shown == TravelPhase.NoEvent || event == null -> EmptyState(
                    Icons.Outlined.EventBusy, "No event selected",
                    body = "Pick an event to see who's arriving and leaving.",
                    actionLabel = "Choose event", onAction = onPickEvent,
                )
                shown == TravelPhase.Disabled || !event.travelEnabled -> EmptyState(
                    Icons.Outlined.Luggage, "Travel isn't on for this event",
                    body = "Once travel is enabled on attend.hackclub.com, arrivals, departures and airport pickups show up here.",
                )
                shown == TravelPhase.Failed || (cal == null && state.error != null) -> EmptyState(
                    Icons.Outlined.CloudOff, "Couldn't load travel", body = state.error ?: "Something went wrong.",
                    actionLabel = "Try again", onAction = onRefresh,
                )
                shown == TravelPhase.Loading || cal == null -> LoadingState(message = "Loading travel…")
                else -> TravelList(state, cal.entries, tz, query, onQueryChange, filter, onFilterChange, mode, onModeChange, onRefresh, onOpen, now)
            }
            }
        }
    }
}

private enum class TravelPhase { NoEvent, Disabled, Failed, Loading, Content }

/** What the list area shows; swaps crossfade rather than cut (e.g. a filter that matches nothing). */
private enum class ListPhase { NoTravel, NoMatches, Rows }

@Composable
private fun TravelList(
    state: TravelUiState,
    all: List<TravelEntry>,
    tz: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: TravelFilter,
    onFilterChange: (TravelFilter) -> Unit,
    mode: TravelMode?,
    onModeChange: (TravelMode?) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (TravelEntry) -> Unit,
    now: Instant,
) {
    val canOpen = state.event?.canViewParticipants == true
    val filtered = remember(all, query, filter, mode) { TravelLogic.filter(all, query, filter, mode) }
    val today = remember(tz, now) { TravelLogic.today(tz, now) }
    val sections = remember(filtered, today) { TravelLogic.sections(filtered, today) }
    val counts = remember(all, query, mode) { TravelLogic.filterCounts(all, query, mode) }
    val modeCounts = remember(all, query, filter) { TravelLogic.modeCounts(all, query, filter) }
    val showModes = remember(all) { TravelLogic.modesPresent(all).size > 1 }
    val listState = rememberLazyListState()
    val haptics = rememberHaptics()

    Column(Modifier.fillMaxSize()) {
        if (all.isNotEmpty()) {
            SearchField(query, onQueryChange, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            val statusChips = TravelFilter.entries.filter { it == TravelFilter.All || it == filter || (counts[it] ?: 0) > 0 }
            val chipRowState = rememberLazyListState()
            LaunchedEffect(filter) {
                val index = statusChips.indexOf(filter)
                if (index > 0) chipRowState.animateScrollToItem(index)
            }
            LazyRow(
                state = chipRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                items(statusChips, key = { it.name }) { f ->
                    CountChip(
                        selected = filter == f,
                        label = if (f == TravelFilter.Minors) "UMs" else f.label,
                        count = counts[f] ?: 0,
                        onClick = { haptics.tick(); onFilterChange(if (filter == f) TravelFilter.All else f) },
                        description = f.label,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (showModes) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(TravelMode.entries.filter { it in modeCounts || it == mode }, key = { it.name }) { m ->
                        CountChip(
                            selected = mode == m,
                            label = m.label,
                            count = modeCounts[m] ?: 0,
                            icon = modeIcon(m.wire, null),
                            onClick = { haptics.tick(); onModeChange(if (mode == m) null else m) },
                            description = "${m.label} journeys",
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
        if (state.error != null) {
            OfflineBanner("Showing saved travel. ${state.error}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), onRetry = onRefresh)
        }
        HapticPullToRefreshBox(isRefreshing = state.userRefreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            val listPhase = when {
                all.isEmpty() -> ListPhase.NoTravel
                filtered.isEmpty() -> ListPhase.NoMatches
                else -> ListPhase.Rows
            }
            Crossfade(listPhase, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "travel_list") { shown ->
            when (shown) {
                ListPhase.NoTravel -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            Icons.Outlined.FlightLand, "No travel yet",
                            body = "Participant journeys appear here once confirmed participants add their travel details.",
                        )
                    }
                }
                ListPhase.NoMatches -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        EmptyState(
                            Icons.Outlined.SearchOff, "No matches",
                            body = if (query.isNotBlank()) "No journeys match “${query.trim()}” with these filters." else "No journeys match these filters.",
                            actionLabel = "Clear filters",
                            onAction = { haptics.click(); onQueryChange(""); onFilterChange(TravelFilter.All); onModeChange(null) },
                        )
                    }
                }
                ListPhase.Rows -> ProvideEntranceStagger { LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    sections.forEach { section ->
                        // Headers fade in/out as filtering adds or empties a day; no placement animation, which
                        // would fight the sticky positioning.
                        stickyHeader(key = "h_${section.key}") { SectionHeaderRow(section, Modifier.animateItem(placementSpec = null)) }
                        itemsIndexed(section.entries, key = { _, it -> it.id }) { i, entry ->
                            TravelRow(
                                entry, tz, canOpen, onClick = { haptics.click(); onOpen(entry) },
                                index = i, count = section.entries.size, modifier = Modifier.animateItem(),
                            )
                        }
                    }
                } }
            }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search name, route or flight") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Outlined.Close, "Clear search") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CountChip(
    selected: Boolean,
    label: String,
    count: Int,
    onClick: () -> Unit,
    description: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(buildAnnotatedString {
                append(label)
                append("  ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(count.toString()) }
            })
        },
        leadingIcon = when {
            selected -> ({ Icon(Icons.Outlined.CheckCircle, null, Modifier.size(FilterChipDefaults.IconSize)) })
            icon != null -> ({ Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) })
            else -> null
        },
        modifier = modifier.heightIn(min = 40.dp).semantics { contentDescription = "$description, $count" },
    )
}

@Composable
private fun SectionHeaderRow(section: TravelSection, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.title, style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.primary)
            if (section.subtitle != null) {
                Spacer(Modifier.width(8.dp))
                Text(section.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "${section.entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                        .semantics { contentDescription = "${section.entries.size} journeys" },
                )
            }
        }
    }
}

@Composable
internal fun TravelRow(
    entry: TravelEntry,
    tz: String?,
    clickable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Position in its day's segmented group. */
    index: Int = 0,
    count: Int = 1,
) {
    val status = MaterialTheme.status
    val container = if (entry.isUnaccompaniedMinor) status.warningContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainer
    val inbound = entry.direction == "inbound"
    val time = Time.time(entry.primaryTimeAt, tz)
    val description = buildString {
        append(entry.name)
        if (entry.isUnaccompaniedMinor) append(", unaccompanied minor")
        append(if (inbound) ", arrives " else ", departs ")
        append(time ?: "unscheduled")
        entry.route?.let { append(", $it") }
        entry.reference?.let { append(", $it") }
        pickupInfo(entry.pickupState)?.let { append(", ${it.label}") }
    }
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val (clock, meridiem) = splitTime(time)
                Text(
                    clock ?: "—:—",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (time == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                meridiem?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).background(
                    if (inbound) status.successContainer else status.infoContainer,
                    if (inbound) MaterialShapesCookie4 else MaterialShapesClover,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    modeIcon(entry.mode, entry.direction), null, Modifier.size(22.dp),
                    tint = if (inbound) status.onSuccessContainer else status.onInfoContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isUnaccompaniedMinor) {
                        Spacer(Modifier.width(6.dp))
                        Pill("UM", status.warningContainer, status.onWarningContainer, icon = Icons.Outlined.Warning)
                    }
                }
                val routeLine = listOfNotNull(entry.route, entry.reference).joinToString(" · ")
                if (routeLine.isNotEmpty()) {
                    Text(routeLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    DirectionLabel(inbound)
                    pickupInfo(entry.pickupState)?.let { p ->
                        Pill(p.label, p.container(), p.content(), icon = p.icon)
                    }
                    entry.groups.take(2).forEach { g -> GroupDot(g.name, g.color) }
                    if (entry.groups.size > 2) {
                        Text("+${entry.groups.size - 2}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (clickable) Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SegmentedItem(
        index, count,
        // The row is one TalkBack stop, so its click action is restated here (clearing drops the inner one).
        modifier.staggeredEntrance().padding(start = 12.dp, end = 12.dp, bottom = if (index == count - 1) 8.dp else 0.dp).clearAndSetSemantics {
            contentDescription = description
            if (clickable) { role = Role.Button; semanticsOnClick { onClick(); true } }
        },
        onClick = if (clickable) onClick else null,
        color = container,
        content = content,
    )
}

@Composable
private fun DirectionLabel(inbound: Boolean) {
    val status = MaterialTheme.status
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (inbound) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, Modifier.size(16.dp),
            tint = if (inbound) status.success else status.info,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            if (inbound) "Arrives" else "Departs",
            style = MaterialTheme.typography.labelMedium,
            color = if (inbound) status.success else status.info,
        )
    }
}

@Composable
private fun GroupDot(name: String, color: String?) {
    val c = parseHexColor(color) ?: MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** "7:25 AM" -> ("7:25", "AM"); "07:25" -> ("07:25", null). Handles the narrow no-break space newer locales use. */
internal fun splitTime(time: String?): Pair<String?, String?> {
    if (time == null) return null to null
    val parts = time.trim().split(Regex("[\\s\u202F\u00A0]+"), limit = 2)
    return parts[0] to parts.getOrNull(1)?.takeIf { it.isNotBlank() }
}

/** Group colours are data ("#3b82f6"), not theme colours. */
internal fun parseHexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

internal data class PickupInfo(val label: String, val icon: ImageVector, val kind: Kind) {
    enum class Kind { Warning, Success, Info, Neutral }

    @Composable fun container(): Color = when (kind) {
        Kind.Warning -> MaterialTheme.status.warningContainer
        Kind.Success -> MaterialTheme.status.successContainer
        Kind.Info -> MaterialTheme.status.infoContainer
        Kind.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

    @Composable fun content(): Color = when (kind) {
        Kind.Warning -> MaterialTheme.status.onWarningContainer
        Kind.Success -> MaterialTheme.status.onSuccessContainer
        Kind.Info -> MaterialTheme.status.onInfoContainer
        Kind.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun pickupInfo(state: String?): PickupInfo? = when (state) {
    "awaiting_pickup" -> PickupInfo("Awaiting pickup", Icons.Outlined.Schedule, PickupInfo.Kind.Warning)
    "collected" -> PickupInfo("Picked up", Icons.Outlined.CheckCircle, PickupInfo.Kind.Success)
    "checked_in" -> PickupInfo("Checked in", Icons.Outlined.HowToReg, PickupInfo.Kind.Info)
    "pickup_not_needed" -> PickupInfo("No pickup", Icons.Outlined.DoNotDisturbOn, PickupInfo.Kind.Neutral)
    else -> null
}

internal fun modeIcon(mode: String?, direction: String?): ImageVector = when (mode) {
    "plane" -> when (direction) {
        "inbound" -> Icons.Outlined.FlightLand
        "outbound" -> Icons.Outlined.FlightTakeoff
        else -> Icons.Outlined.Flight
    }
    "train" -> Icons.Outlined.Train
    "bus" -> Icons.Outlined.DirectionsBus
    "car" -> Icons.Outlined.DirectionsCar
    else -> Icons.Outlined.Commute
}
