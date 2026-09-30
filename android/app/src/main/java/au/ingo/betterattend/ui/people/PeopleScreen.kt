package au.ingo.betterattend.ui.people

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.GroupOff
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.EventStats
import au.ingo.betterattend.ui.LocalAppContainer
import au.ingo.betterattend.ui.components.AccountButton
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.EventSwitcherTitle
import au.ingo.betterattend.ui.components.HapticPullToRefreshBox
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.nav.AppNavigator
import au.ingo.betterattend.ui.nav.Tab
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun PeopleScreen(nav: AppNavigator) {
    val c = LocalAppContainer.current
    val vm: PeopleViewModel = viewModel(factory = PeopleViewModel.Factory(c))
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val eventId = state.event?.id

    // Delta-sync on open and every minute while the list is on screen (RESUMED = the visible tab).
    LaunchedEffect(eventId, lifecycle) {
        if (eventId == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.sync()
                delay(60_000)
            }
        }
    }
    var manualRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(state.syncing) { if (!state.syncing) manualRefresh = false }
    val now by produceState(Instant.now()) {
        while (true) { delay(30_000); value = Instant.now() }
    }

    PeopleContent(
        state = state,
        now = now,
        refreshing = manualRefresh && state.syncing,
        onRefresh = { manualRefresh = true; vm.sync() },
        onQuery = vm::setQuery,
        onQuick = vm::setQuick,
        onOptions = vm::setOptions,
        onSort = vm::setSort,
        onOpen = { p, order ->
            state.event?.let {
                // Detail can then swipe to the previous / next person in this same list.
                ParticipantBrowseOrder.set(it.id, p.participantEventId, order)
                nav.openParticipant(it.id, p.participantEventId)
            }
        },
        onChooseEvent = nav::openEventPicker,
        onAccount = nav::openSettings,
        onOpenScanner = { nav.switchTab(Tab.Scan) },
    )
}

@Composable
fun PeopleContent(
    state: PeopleUiState,
    now: Instant = Instant.now(),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onQuery: (String) -> Unit = {},
    onQuick: (QuickFilter) -> Unit = {},
    onOptions: (FilterOptions) -> Unit = {},
    onSort: (SortOrder) -> Unit = {},
    /** A row was tapped: the person, plus the participantEventIds of the list they were tapped in, in display order. */
    onOpen: (Participant, List<String>) -> Unit = { _, _ -> },
    onChooseEvent: () -> Unit = {},
    onAccount: () -> Unit = {},
    onOpenScanner: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    // Every quick-filter change (chips, summary numbers, "Show everyone") gets the same selection tick.
    val onQuickTick: (QuickFilter) -> Unit = { f -> if (f != state.quick) haptics.tick(); onQuick(f) }
    val event = state.event
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val canSensitive = event?.canViewSensitiveData == true
    val roster = state.roster.orEmpty()

    val result = remember(roster, state.query, state.quick, state.options, state.sort, canSensitive) {
        PeopleFilter.apply(roster, state.query, state.quick, state.options, state.sort, canSensitive)
    }
    val items = remember(result, state.sort) { PeopleFilter.withHeaders(result.participants, state.sort) }

    val subtitle = when {
        event == null -> null
        !event.canViewParticipants -> "People"
        state.syncing && state.lastSyncAt == null -> "People · Syncing…"
        state.lastSyncAt != null -> "People · Updated ${Time.ago(state.lastSyncAt, now)}"
        else -> "People"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { EventSwitcherTitle(event?.name, subtitle, onChooseEvent) },
                actions = { AccountButton(state.user, onAccount) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.eventsLoaded -> LoadingState()
                event == null -> EmptyState(
                    Icons.Outlined.EventBusy, "No event selected",
                    body = "Choose an event to see who's registered and who's arrived.",
                    actionLabel = "Choose event", onAction = onChooseEvent,
                )
                !event.canViewParticipants -> EmptyState(
                    Icons.Outlined.GroupOff, "The people list isn't available to you",
                    body = "Your role on ${event.name} is scan-only, so participant details are hidden. " +
                        "You can still check people in by scanning their ticket or badge.",
                    actionLabel = "Open scanner", onAction = onOpenScanner,
                )
                state.roster == null -> LoadingState(message = "Loading people…")
                // Only a partial roster so far and the full sync is still coming.
                !state.rosterComplete && roster.isEmpty() && state.syncError == null -> LoadingState(message = "Loading people…")
                roster.isEmpty() && state.syncError != null -> EmptyState(
                    Icons.Outlined.CloudOff, "Couldn't load people",
                    body = state.syncError, actionLabel = "Try again", onAction = onRefresh,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    SearchRow(state.query, onQuery, state.options.activeCount) { sheetOpen = true }
                    QuickChips(state.quick, result.counts.takeIf { state.rosterComplete }, onQuickTick)
                    AnimatedVisibility(
                        visible = state.options.activeCount > 0,
                        enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                    ) {
                        // Keep showing the last count while it animates away.
                        var shown by remember { mutableIntStateOf(state.options.activeCount) }
                        if (state.options.activeCount > 0) shown = state.options.activeCount
                        ActiveFiltersLine(shown) { haptics.tick(); onOptions(FilterOptions()) }
                    }
                    HapticPullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                        PeopleList(state, roster, result, items, now, onOpen, onQuickTick) {
                            haptics.tick(); onQuick(QuickFilter.All); onOptions(FilterOptions()); onQuery("")
                        }
                    }
                }
            }
        }
    }

    if (sheetOpen && event != null) {
        val statuses = remember(roster) { roster.mapNotNull { it.status }.distinct().sortedBy { statusOrder.indexOf(it).let { i -> if (i < 0) 99 else i } } }
        val diets = remember(roster) { roster.mapNotNull { it.dietType }.distinct().sorted() }
        PeopleFilterSheet(
            options = state.options, sort = state.sort, contexts = state.contexts, statuses = statuses, dietTypes = diets,
            canViewSensitive = canSensitive, resultCount = result.participants.size,
            // Ticks come from this window's haptics: the sheet's own window ignores the Haptics setting.
            onOptions = { if (it != state.options) haptics.tick(); onOptions(it) },
            onSort = { if (it != state.sort) haptics.tick(); onSort(it) },
            onDismiss = { sheetOpen = false },
        )
    }
}

private val statusOrder = listOf("complete", "awaiting_guardian", "in_progress", "invited", "withdrawn", "rejected")

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit, activeFilters: Int, onFilters: () -> Unit) {
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search name, email or code") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") } }
            } else null,
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        )
        Spacer(Modifier.width(8.dp))
        BadgedBox(badge = { if (activeFilters > 0) Badge { Text("$activeFilters") } }) {
            FilledTonalIconButton(
                onClick = onFilters,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Outlined.Tune, if (activeFilters > 0) "Filter and sort, $activeFilters active" else "Filter and sort")
            }
        }
    }
}

@Composable
/** [counts] is null while the roster is only partially loaded, so chips don't show misleading numbers. */
private fun QuickChips(selected: QuickFilter, counts: Map<QuickFilter, Int>?, onQuick: (QuickFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(QuickFilter.entries, key = { it.name }) { f ->
            val on = f == selected
            val count = counts?.get(f) ?: counts?.let { 0 }
            FilterChip(
                selected = on,
                onClick = { onQuick(f) },
                label = {
                    Text(f.label)
                    if (count != null) {
                        Spacer(Modifier.width(6.dp))
                        AnimatedCount(count, fontWeight = FontWeight.Bold)
                    }
                },
                leadingIcon = when {
                    on -> { { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } }
                    f == QuickFilter.NeedsAttention && (count ?: 0) > 0 -> {
                        { Icon(Icons.Outlined.Warning, null, Modifier.size(FilterChipDefaults.IconSize), tint = MaterialTheme.colorScheme.error) }
                    }
                    else -> null
                },
                modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = if (count != null) "${f.label}, $count" else f.label },
            )
        }
    }
}

@Composable
private fun ActiveFiltersLine(count: Int, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            if (count == 1) "1 filter on" else "$count filters on",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun PeopleList(
    state: PeopleUiState,
    roster: List<Participant>,
    result: FilterResult,
    items: List<PeopleListItem>,
    now: Instant,
    onOpen: (Participant, List<String>) -> Unit,
    onQuick: (QuickFilter) -> Unit,
    onClearAll: () -> Unit,
) {
    val tz = state.event?.timezone
    val searching = state.query.isNotBlank()
    // What detail swipes through: the list exactly as shown.
    val order = remember(result) { result.participants.map { it.participantEventId } }
    val remoteOrder = remember(state.remoteResults) { state.remoteResults.orEmpty().map { it.participantEventId } }
    // Every item animates in/out and to its new place, so filtering, sorting and sync updates glide instead of jumping.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.syncError != null) {
            item(key = "offline") {
                OfflineBanner(
                    "Showing saved list${state.lastSyncAt?.let { " from ${Time.ago(it, now)}" } ?: ""}. ${state.syncError}",
                    Modifier.animateItem().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (!searching && roster.isNotEmpty() && state.quick == QuickFilter.All) {
            item(key = "summary") {
                Box(Modifier.animateItem()) {
                    if (state.rosterComplete) SummaryCard(roster, result.counts, onQuick) else SummaryLoadingCard(roster.size, state.syncError != null)
                }
            }
        }
        items.forEach { item ->
            when (item) {
                is PeopleListItem.Header -> stickyHeader(key = item.key, contentType = "header") { LetterHeader(item.letter) }
                is PeopleListItem.Person -> item(key = item.key, contentType = "row") {
                    ParticipantRow(item.participant, tz, onClick = { onOpen(item.participant, order) }, modifier = Modifier.animateItem())
                }
            }
        }
        if (result.participants.isEmpty()) {
            val remoteRelevant = state.query.trim().length >= 2
            when {
                remoteRelevant && state.remoteSearching -> item(key = "remote_loading") {
                    Row(Modifier.animateItem().fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        LoadingIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Searching all of Attend…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                remoteRelevant && !state.remoteResults.isNullOrEmpty() -> {
                    item(key = "remote_h") { RemoteHeader(Modifier.animateItem()) }
                    items(state.remoteResults, key = { "r_" + it.participantEventId }) { p ->
                        ParticipantRow(p, tz, onClick = { onOpen(p, remoteOrder) }, modifier = Modifier.animateItem())
                    }
                }
                roster.isEmpty() -> item(key = "empty") {
                    Box(Modifier.animateItem()) {
                        EmptyState(Icons.Outlined.PersonSearch, "No one's registered yet", body = "Pull down to refresh once invitations go out.")
                    }
                }
                searching -> item(key = "nomatch") {
                    Box(Modifier.animateItem()) {
                        EmptyState(
                            Icons.Outlined.PersonSearch, "No matches for “${state.query.trim()}”",
                            body = state.remoteError?.let { "Couldn't search Attend: $it" }
                                ?: if (state.query.trim().length < 2) "Keep typing to search everyone registered." else "Check the spelling, or try their email or ticket code.",
                            actionLabel = if (state.quick != QuickFilter.All || state.options.activeCount > 0) "Search everyone" else null,
                            onAction = onClearAll,
                        )
                    }
                }
                else -> item(key = "filtered_empty") {
                    Box(Modifier.animateItem()) {
                        EmptyState(
                            Icons.Outlined.FilterAltOff, emptyTitle(state.quick),
                            body = if (state.options.activeCount > 0) "Try removing a filter." else null,
                            actionLabel = "Show everyone", onAction = onClearAll,
                        )
                    }
                }
            }
        }
    }
}

private fun emptyTitle(q: QuickFilter) = when (q) {
    QuickFilter.Here -> "No one's checked in yet"
    QuickFilter.NotHere -> "Everyone confirmed is here"
    QuickFilter.NeedsAttention -> "No safety alerts"
    QuickFilter.NotComplete -> "Every registration is complete"
    QuickFilter.Withdrawn -> "No one has withdrawn"
    QuickFilter.All -> "No one matches these filters"
}

@Composable
private fun RemoteHeader(modifier: Modifier = Modifier) {
    Text(
        "Found on Attend",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun LetterHeader(letter: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Text(
            letter,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun SummaryCard(roster: List<Participant>, counts: Map<QuickFilter, Int>, onQuick: (QuickFilter) -> Unit) {
    val stats = remember(roster) { EventStats.from(roster) }
    val total = stats.expected
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                AnimatedCount(stats.checkedIn, style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    "of $total here",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            val progress by animateFloatAsState(stats.progress, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "hereProgress")
            LinearWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat(counts[QuickFilter.NotHere] ?: 0, "not here", Modifier.weight(1f)) { onQuick(QuickFilter.NotHere) }
                MiniStat(counts[QuickFilter.NeedsAttention] ?: 0, "need attention", Modifier.weight(1f)) { onQuick(QuickFilter.NeedsAttention) }
                MiniStat(counts[QuickFilter.NotComplete] ?: 0, "incomplete", Modifier.weight(1f)) { onQuick(QuickFilter.NotComplete) }
            }
        }
    }
}

/** Stands in for [SummaryCard] while only part of the roster is known, instead of a misleading "3 of 3 here". */
@Composable
private fun SummaryLoadingCard(known: Int, failed: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp).semantics(mergeDescendants = true) {}) {
            Text(if (failed) "Full list not loaded yet" else "Loading everyone…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Showing $known ${if (known == 1) "person" else "people"} seen so far. " +
                    if (failed) "Numbers will appear once the full list syncs." else "Numbers will appear once the full list is in.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!failed) {
                Spacer(Modifier.height(12.dp))
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                )
            }
        }
    }
}

@Composable
private fun MiniStat(value: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = "Show $label", onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        AnimatedCount(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
