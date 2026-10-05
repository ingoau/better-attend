package au.ingo.betterattend.ui.rollcall

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.data.repo.RollCall
import au.ingo.betterattend.data.repo.RollCallExpected
import au.ingo.betterattend.data.repo.Roster
import au.ingo.betterattend.scan.ScanAdmission
import au.ingo.betterattend.ui.components.AnimatedNumber
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.CountFilterChips
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.MaterialShapesCookie
import au.ingo.betterattend.ui.components.NoAccessState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.PillSearchField
import au.ingo.betterattend.ui.components.SectionHeader
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.people.AlertIcons
import au.ingo.betterattend.ui.people.checkInLine
import au.ingo.betterattend.ui.people.statusLabel
import au.ingo.betterattend.ui.scan.StorageUnavailableBanner
import au.ingo.betterattend.ui.scan.icon
import au.ingo.betterattend.ui.scan.isLive
import au.ingo.betterattend.ui.scan.windowLabel
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.launch
import java.time.Instant

/** The user's choices on the start screen. */
data class RollCallSetupChoice(val expected: RollCallExpected, val recording: TickRecording)

/** Every callback the roll call screens need, bundled so screenshot tests can pass no-ops. */
data class RollCallActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onExpected: (RollCallExpected) -> Unit = {},
    val onRecording: (TickRecording) -> Unit = {},
    val onStart: () -> Unit = {},
    val onFilter: (RollCallFilter) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onAdd: (id: String, name: String) -> Unit = { _, _ -> },
    val onFinish: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onEnd: () -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onOpenParticipant: (String) -> Unit = {},
)

@Composable
fun RollCallContent(
    state: RollCallUiState,
    snackbar: SnackbarHostState,
    setup: RollCallSetupChoice,
    filter: RollCallFilter,
    query: String,
    actions: RollCallActions,
    now: Instant = Instant.now(),
) {
    val rc = state.rollCall
    when {
        !state.eventsLoaded || (state.canView && !state.loaded) -> Bare("Roll call", actions.onBack) { LoadingState() }
        !state.canView -> Bare("Roll call", actions.onBack) { NoAccessState(onBack = actions.onBack) }
        rc == null -> RollCallSetupContent(state, setup, actions, now)
        !rc.isFinished -> RollCallRunningContent(state, rc, filter, query, snackbar, actions, now)
        else -> RollCallSummaryContent(state, rc, snackbar, actions, now)
    }
}

/** A plain screen with a back arrow, for loading and no-access states. */
@Composable
private fun Bare(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { RollCallTopBar(title, null, onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

@Composable
private fun RollCallTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    MediumFlexibleTopAppBar(
        title = { Text(title) },
        subtitle = { if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

// ---------------------------------------------------------------- setup

@Composable
fun RollCallSetupContent(state: RollCallUiState, setup: RollCallSetupChoice, actions: RollCallActions, now: Instant = Instant.now()) {
    val haptics = rememberHaptics()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val event = state.event
    val roster = state.completeRoster
    val participants = roster?.participants.orEmpty()
    val checkedIn = remember(participants) { RollCallLogic.expected(participants, RollCallExpected.CheckedIn).size }
    val registered = remember(participants) { RollCallLogic.expected(participants, RollCallExpected.Registered).size }
    val count = if (setup.expected == RollCallExpected.CheckedIn) checkedIn else registered
    val contexts = state.contexts
    val chosen = (setup.recording as? TickRecording.AtScanPoint)?.let { r -> contexts?.firstOrNull { it.id == r.contextId } }
    // A scan point that's no longer on the event can't be recorded at: don't start until they choose again.
    val recordingValid = setup.recording == TickRecording.PhoneOnly || chosen != null

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { RollCallTopBar("Roll call", event?.name, actions.onBack, scroll) },
        bottomBar = {
            if (roster != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            buildString {
                                append(people(count))
                                append(" · ")
                                append(chosen?.let { "Each tick is a scan at ${it.name}" } ?: "Ticks stay on this phone")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        Button(
                            onClick = { haptics.confirm(); actions.onStart() },
                            enabled = count > 0 && recordingValid,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.FactCheck, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start roll call", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                roster == null && state.syncing -> LoadingState(message = "Loading the roster…")
                roster == null -> EmptyState(
                    Icons.Outlined.CloudSync, "The roster isn't on this phone yet",
                    body = state.syncError?.let { "Roll call works offline once the roster has downloaded. $it" }
                        ?: "Roll call works offline once the roster has downloaded.",
                    actionLabel = "Try again", onAction = actions.onRefresh,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "intro") { Intro(rosterAt = ScanAdmission.rosterTime(roster), now = now) }
                    if (state.storageUnavailable) item(key = "storage") { StorageUnavailableBanner() }
                    if (state.syncError != null) item(key = "offline") { OfflineBanner("Using the saved roster. ${state.syncError}", onRetry = actions.onRefresh) }

                    item(key = "h_expected") { SectionHeader("Who should be here") }
                    item(key = "expected") {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceCard(
                                selected = setup.expected == RollCallExpected.CheckedIn,
                                icon = Icons.Outlined.HowToReg,
                                title = "Checked in",
                                body = "${people(checkedIn)} who've checked in",
                                onClick = { if (setup.expected != RollCallExpected.CheckedIn) haptics.tick(); actions.onExpected(RollCallExpected.CheckedIn) },
                            )
                            ChoiceCard(
                                selected = setup.expected == RollCallExpected.Registered,
                                icon = Icons.Outlined.Groups,
                                title = "Everyone registered",
                                body = "${people(registered)}, here or not",
                                onClick = { if (setup.expected != RollCallExpected.Registered) haptics.tick(); actions.onExpected(RollCallExpected.Registered) },
                            )
                        }
                    }

                    item(key = "h_record") { SectionHeader("Record ticks") }
                    item(key = "record") {
                        RecordingChoices(contexts, setup.recording, event?.timezone) { choice ->
                            if (choice != setup.recording) haptics.tick()
                            actions.onRecording(choice)
                        }
                    }
                }
            }
        }
    }
}

private fun people(n: Int) = if (n == 1) "1 person" else "$n people"

@Composable
private fun Intro(rosterAt: String?, now: Instant) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(MaterialShapesCookie).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.FactCheck, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Headcount", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    "The list of who should be here is frozen when you start. Tick people off as you see them: anyone left is missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ScanAdmission.rosterAgeLabel(rosterAt, now)?.let { age ->
                    Spacer(Modifier.height(6.dp))
                    val stale = ScanAdmission.isRosterStale(rosterAt, now)
                    Text(
                        if (stale) "$age. Pull the latest before you start if you can." else age,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stale) MaterialTheme.status.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A radio option as a full-width card: icon, title, supporting text. */
@Composable
private fun ChoiceCard(selected: Boolean, icon: ImageVector, title: String, body: String?, onClick: () -> Unit, trailing: String? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (selected) cs.secondaryContainer else cs.surfaceContainerLow,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .then(if (selected) Modifier.border(2.dp, cs.primary, MaterialTheme.shapes.large) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
            }
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun RecordingChoices(contexts: List<ScanContext>?, recording: TickRecording, tz: String?, onChoose: (TickRecording) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceCard(
            selected = recording == TickRecording.PhoneOnly,
            icon = Icons.Outlined.Smartphone,
            title = "Only on this phone",
            body = "Nothing is sent. Works offline.",
            onClick = { onChoose(TickRecording.PhoneOnly) },
        )
        when {
            contexts == null -> Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Loading scan points…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            contexts.isEmpty() -> Text(
                "This event has no scan points, so ticks can only stay on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            else -> {
                Text(
                    "Or record each tick as a scan at a scan point you choose:",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                )
                contexts.sortedBy { it.position }.forEach { ctx ->
                    val selected = (recording as? TickRecording.AtScanPoint)?.contextId == ctx.id
                    ChoiceCard(
                        selected = selected,
                        icon = ctx.icon(),
                        title = ctx.name,
                        body = listOfNotNull(
                            when {
                                ctx.checksIn -> "Ticking also checks people in"
                                else -> null
                            },
                            if (ctx.isLive()) "Happening now" else ctx.windowLabel(tz),
                        ).joinToString(" · ").ifEmpty { null },
                        onClick = { onChoose(TickRecording.AtScanPoint(ctx.id)) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- running

@Composable
fun RollCallRunningContent(
    state: RollCallUiState,
    rc: RollCall,
    filter: RollCallFilter,
    query: String,
    snackbar: SnackbarHostState,
    actions: RollCallActions,
    now: Instant = Instant.now(),
    addSheetOpen: Boolean = false,
) {
    val haptics = rememberHaptics()
    val tz = state.event?.timezone
    val roster = state.roster
    val counts = remember(rc) { RollCallLogic.counts(rc) }
    val chipCounts = remember(rc) { RollCallLogic.filterCounts(rc) }
    val rows = remember(rc, roster, filter, query) { RollCallLogic.rows(rc, roster, filter, query) }
    var adding by rememberSaveable { mutableStateOf(addSheetOpen) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val toggle: (RollCallRow) -> Unit = { row -> haptics.toggle(!row.accounted); actions.onToggle(row.id) }

    Scaffold(
        topBar = {
            RollCallTopBar("Roll call", RollCallLogic.subtitle(rc, tz), actions.onBack) {
                IconButton(onClick = { haptics.click(); adding = true }) { Icon(Icons.Outlined.PersonAdd, "Add someone") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Finish") },
                icon = { Icon(Icons.Outlined.Flag, null) },
                onClick = { haptics.click(); confirmFinish = true },
                expanded = !listState.canScrollBackward,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            CounterCard(counts, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            if (state.storageUnavailable) StorageUnavailableBanner(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            PillSearchField(query, actions.onQuery, "Search the list", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            CountFilterChips(RollCallFilter.entries, filter, { it.label }, { chipCounts[it] }, actions.onFilter)
            Spacer(Modifier.height(4.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (rows.isEmpty()) item(key = "empty") { RunningEmpty(filter, query, counts, Modifier.animateItem()) }
                items(rows, key = { it.id }) { row ->
                    SwipeToggleRow(row, onToggle = { toggle(row) }, modifier = Modifier.animateItem()) {
                        RollCallPersonRow(row, tz, rc.scanContextName, onToggle = { toggle(row) }, onOpen = { haptics.longPress(); actions.onOpenParticipant(row.id) })
                    }
                }
            }
        }
    }

    if (adding) {
        AddSomeoneSheet(
            rc = rc, roster = roster, tz = tz,
            onAdd = { p -> haptics.confirm(); actions.onAdd(p.participantEventId, p.fullName?.takeIf { it.isNotBlank() } ?: p.name); adding = false },
            onDismiss = { adding = false },
        )
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            icon = { Icon(Icons.Outlined.Flag, null) },
            title = { Text("Finish roll call?") },
            text = {
                Text(
                    if (counts.missing == 0) "Everyone is accounted for."
                    else "${people(counts.missing)} ${if (counts.missing == 1) "is" else "are"} still missing. You'll see a summary, and can go back to keep ticking.",
                )
            },
            confirmButton = { Button(onClick = { haptics.confirm(); confirmFinish = false; actions.onFinish() }) { Text("Finish") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep counting") } },
        )
    }
}

@Composable
private fun CounterCard(counts: RollCallCounts, modifier: Modifier = Modifier) {
    val s = MaterialTheme.status
    val done = counts.total > 0 && counts.missing == 0
    val progress by animateFloatAsState(counts.progress, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "rollcall")
    Surface(
        color = if (done) s.successContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (done) s.onSuccessContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${counts.accounted} of ${counts.total} accounted for, ${counts.missing} missing"
        },
    ) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(counts.accounted, style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold))
                Text(
                    " / ${counts.total}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (done) s.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (done) Pill("All here", s.success, s.onSuccess, icon = Icons.Outlined.CheckCircle, modifier = Modifier.padding(bottom = 10.dp))
                else Pill("${counts.missing} missing", s.dangerContainer, s.onDangerContainer, icon = Icons.Outlined.PersonSearch, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text("accounted for", style = MaterialTheme.typography.titleMedium, color = if (done) s.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(
                progress = { progress },
                color = if (done) s.success else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunningEmpty(filter: RollCallFilter, query: String, counts: RollCallCounts, modifier: Modifier) {
    when {
        query.isNotBlank() -> EmptyState(Icons.Outlined.PersonSearch, "No one matches “${query.trim()}”", modifier, body = "Use Add someone for people who aren't on the list.")
        filter == RollCallFilter.Missing && counts.total > 0 -> EmptyState(Icons.Outlined.CheckCircle, "Everyone's accounted for", modifier, body = "Tap Finish for the summary.")
        filter == RollCallFilter.Accounted -> EmptyState(Icons.AutoMirrored.Outlined.FactCheck, "No one ticked yet", modifier, body = "Tap someone on the Missing list when you see them.")
        else -> EmptyState(Icons.Outlined.Groups, "No one on the list", modifier, body = "Use Add someone to tick people who are here.")
    }
}

/** Swipe either way to tick / untick; the row springs back and the toggle happens. */
@Composable
private fun SwipeToggleRow(row: RollCallRow, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val s = MaterialTheme.status
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        onDismiss = {
            onToggle()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val toAccounted = !row.accounted
            val alignEnd = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().background(if (toAccounted) s.successContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            ) {
                val color = if (toAccounted) s.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(if (toAccounted) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.Undo, null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text(if (toAccounted) "Accounted for" else "Mark missing", style = MaterialTheme.typography.labelLarge, color = color)
            }
        },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RollCallPersonRow(row: RollCallRow, tz: String?, contextName: String?, onToggle: () -> Unit, onOpen: () -> Unit) {
    val p = row.participant
    val s = MaterialTheme.status
    val secondary: Pair<String, Color?> = when {
        row.accounted -> listOfNotNull(Time.time(row.tickedAt, tz)?.let { "Ticked $it" } ?: "Ticked", if (row.added) "Added" else null).joinToString(" · ") to null
        row.stillRecorded -> "Offline untick · still scanned at ${contextName ?: "the scan point"}" to s.warning
        p == null -> "No longer on the roster" to null
        else -> (checkInLine(p, tz) ?: statusLabel(p.status).let { if (p.isActive) "Not checked in · $it" else it }) to null
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClickLabel = if (row.accounted) "Mark missing" else "Mark accounted for", onClick = onToggle, onLongClickLabel = "Open details", onLongClick = onOpen)
            .semantics { stateDescription = if (row.accounted) "Accounted for" else "Missing" },
    ) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p?.fullName ?: row.name, p?.headshotUrl, size = 44.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!p?.pronouns.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(p.pronouns, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (p != null) {
                        Spacer(Modifier.width(6.dp))
                        AlertIcons(p)
                    }
                }
                Text(
                    secondary.first,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary.second ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledIconToggleButton(
                checked = row.accounted,
                onCheckedChange = { onToggle() },
                colors = IconButtonDefaults.filledIconToggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = s.success,
                    checkedContentColor = s.onSuccess,
                ),
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (row.accounted) "Accounted for ${row.name}" else "Mark ${row.name} accounted for" },
            ) {
                Icon(Icons.Outlined.Check, null)
            }
        }
    }
}

@Composable
private fun AddSomeoneSheet(rc: RollCall, roster: Roster?, tz: String?, onAdd: (Participant) -> Unit, onDismiss: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val candidates = remember(rc, roster, q) { RollCallLogic.addCandidates(rc, roster, q) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AddSomeoneBody(q, { q = it }, candidates, tz, onAdd, Modifier.navigationBarsPadding().imePadding())
    }
}

/** The "Add someone" sheet body (separate so it can be screenshot-tested). */
@Composable
fun AddSomeoneBody(query: String, onQuery: (String) -> Unit, candidates: List<Participant>, tz: String?, onAdd: (Participant) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Add someone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "Mark someone who isn't on the list as here.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
        )
        PillSearchField(query, onQuery, "Search everyone registered", Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            if (candidates.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "Everyone registered is already on the list." else "No one else matches “${query.trim()}”.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(candidates, key = { it.participantEventId }) { p ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.fullName ?: p.name, p.headshotUrl, size = 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.fullName?.takeIf { it.isNotBlank() } ?: p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            AlertIcons(p)
                        }
                        Text(checkInLine(p, tz) ?: statusLabel(p.status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    FilledTonalButton(onClick = { onAdd(p) }, shapes = ButtonDefaults.shapes()) { Text("Here") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- summary

@Composable
fun RollCallSummaryContent(state: RollCallUiState, rc: RollCall, snackbar: SnackbarHostState, actions: RollCallActions, now: Instant = Instant.now()) {
    val haptics = rememberHaptics()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val event = state.event
    val tz = event?.timezone
    val counts = remember(rc) { RollCallLogic.counts(rc) }
    val rows = remember(rc, state.roster) { RollCallLogic.allRows(rc, state.roster) }
    val missing = rows.filter { !it.accounted }
    val added = rows.filter { it.added && it.accounted }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val subtitle = listOfNotNull(Time.time(rc.finishedAt, tz)?.let { "Finished $it" }, RollCallLogic.duration(rc, now).takeIf { it.isNotEmpty() }).joinToString(" · ")

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            RollCallTopBar("Roll call summary", subtitle, actions.onBack, scroll) {
                TextButton(onClick = { haptics.click(); actions.onResume() }) { Text("Keep counting") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { haptics.click(); actions.onShare(RollCallLogic.missingShareText(rc, state.roster, event?.name ?: "Event", tz, now)) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (missing.isEmpty()) "Share" else "Share missing")
                    }
                    Button(onClick = { haptics.click(); confirmEnd = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Text("End roll call")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hero") { SummaryHero(counts) }
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryStat("Accounted", counts.accounted.toString(), Modifier.weight(1f))
                    SummaryStat("Added", added.size.toString(), Modifier.weight(1f))
                    SummaryStat("Took", RollCallLogic.duration(rc, now), Modifier.weight(1f))
                }
            }
            item(key = "recording") {
                Text(
                    rc.scanContextName?.let { "Ticks were recorded as scans at $it." } ?: "Ticks were kept on this phone; nothing was sent.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            if (missing.isNotEmpty()) {
                item(key = "h_missing") { SectionHeader("Missing · ${missing.size}") }
                summaryRows(missing, tz, actions.onOpenParticipant)
            }
            if (added.isNotEmpty()) {
                item(key = "h_added") { SectionHeader("Added · ${added.size}") }
                summaryRows(added, tz, actions.onOpenParticipant)
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End roll call?") },
            text = {
                Text(
                    "This clears the list from this phone." +
                        (rc.scanContextName?.let { " Scans recorded at $it stay in Attend." } ?: ""),
                )
            },
            confirmButton = { Button(onClick = { haptics.confirm(); confirmEnd = false; actions.onEnd() }) { Text("End") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SummaryHero(counts: RollCallCounts) {
    val s = MaterialTheme.status
    val done = counts.missing == 0
    Surface(
        color = if (done) s.successContainer else s.dangerContainer,
        contentColor = if (done) s.onSuccessContainer else s.onDangerContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(MaterialShapesCookie).background(if (done) s.success else s.danger),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, null, tint = if (done) s.onSuccess else s.onDanger, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                if (done) {
                    Text("Everyone's accounted for", style = MaterialTheme.typography.headlineSmall)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${counts.missing}", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold))
                        Text(" missing", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                Text("${counts.accounted} of ${counts.total} accounted for", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun LazyListScope.summaryRows(rows: List<RollCallRow>, tz: String?, onOpen: (String) -> Unit) {
    items(rows, key = { "s_" + it.id }) { row ->
        val p = row.participant
        Surface(
            onClick = { if (p != null) onOpen(row.id) },
            enabled = p != null,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(p?.fullName ?: row.name, p?.headshotUrl, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p?.fullName?.takeIf { it.isNotBlank() } ?: row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (!p?.pronouns.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(p.pronouns, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (p != null) {
                            Spacer(Modifier.width(6.dp))
                            AlertIcons(p)
                        }
                    }
                    val line = when {
                        row.accounted -> Time.time(row.tickedAt, tz)?.let { "Ticked $it" }
                        p != null -> checkInLine(p, tz) ?: "Not checked in"
                        else -> "No longer on the roster"
                    }
                    if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
