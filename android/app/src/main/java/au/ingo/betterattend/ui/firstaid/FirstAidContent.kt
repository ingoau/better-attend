package au.ingo.betterattend.ui.firstaid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.scan.ScanAdmission
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.CountFilterChips
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.components.LoadingState
import au.ingo.betterattend.ui.components.NoAccessState
import au.ingo.betterattend.ui.components.OfflineBanner
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.PillSearchField
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.theme.status
import java.time.Instant

@Composable
fun FirstAidContent(
    state: FirstAidUiState,
    /** null = the default for this list ([FirstAidLogic.defaultFilter]). */
    filter: FirstAidFilter?,
    query: String,
    onFilter: (FirstAidFilter) -> Unit,
    onQuery: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (participantEventId: String) -> Unit,
    onCall: (phone: String) -> Unit,
    onPrint: (people: List<Participant>, filterLabel: String) -> Unit,
    now: Instant = Instant.now(),
) {
    val haptics = rememberHaptics()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val event = state.event
    val sensitive = state.sensitive
    val roster = state.roster?.takeIf { it.syncedAt != null || it.participants.isNotEmpty() }
    val people = remember(roster, sensitive) { FirstAidLogic.people(roster?.participants.orEmpty(), sensitive) }
    val active = filter ?: FirstAidLogic.defaultFilter(people)
    val shown = remember(people, active, query) { FirstAidLogic.filter(people, active, query) }
    val counts = remember(people) { FirstAidLogic.counts(people) }
    val rosterAt = ScanAdmission.rosterTime(state.roster)
    val ready = state.eventsLoaded && state.canView && roster != null

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("First-aid sheet") },
                subtitle = { event?.let { Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = { IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (ready) {
                        IconButton(onClick = { haptics.click(); onPrint(shown, if (query.isBlank()) active.label else "${active.label}, matching “${query.trim()}”") }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Print, "Print or save as PDF")
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.eventsLoaded -> LoadingState()
                !state.canView -> NoAccessState(onBack = onBack)
                roster == null && (!state.rosterRead || state.syncing) -> LoadingState(message = "Loading the roster…")
                roster == null -> EmptyState(
                    Icons.Outlined.CloudSync, "The roster isn't on this phone yet",
                    body = state.syncError?.let { "The first-aid sheet works offline once the roster has downloaded. $it" }
                        ?: "The first-aid sheet works offline once the roster has downloaded.",
                    actionLabel = "Try again", onAction = onRefresh,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    if (people.isNotEmpty()) {
                        PillSearchField(query, onQuery, "Search name, email or code", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
                        CountFilterChips(FirstAidFilter.entries, active, { it.label }, { counts[it] }, onFilter)
                        Spacer(Modifier.height(8.dp))
                    }
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (ScanAdmission.isRosterStale(rosterAt, now)) {
                            item(key = "stale") {
                                OfflineBanner(
                                    listOfNotNull(ScanAdmission.rosterAgeLabel(rosterAt, now), state.syncError).joinToString(". ").ifEmpty { "Roster not synced yet" },
                                    onRetry = onRefresh,
                                )
                            }
                        }
                        if (!sensitive) item(key = "notice") { SensitiveNotice() }
                        when {
                            people.isEmpty() -> item(key = "empty") {
                                EmptyState(Icons.Outlined.HealthAndSafety, FirstAidLogic.EMPTY, body = ScanAdmission.rosterAgeLabel(rosterAt, now))
                            }
                            shown.isEmpty() && query.isNotBlank() -> item(key = "nomatch") {
                                EmptyState(Icons.Outlined.PersonSearch, "No one matches “${query.trim()}”")
                            }
                            shown.isEmpty() -> item(key = "nobody_here") {
                                EmptyState(
                                    Icons.Outlined.HealthAndSafety, "No one here right now has medical or safety flags",
                                    body = "${counts[FirstAidFilter.Everyone]} registered people do.",
                                    actionLabel = "Show everyone", onAction = { haptics.tick(); onFilter(FirstAidFilter.Everyone) },
                                )
                            }
                        }
                        items(shown, key = { it.participantEventId }) { p ->
                            FirstAidCard(p, sensitive, event?.timezone, onOpen = { haptics.click(); onOpen(p.participantEventId) }, onCall = { haptics.click(); onCall(it) }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SensitiveNotice() {
    val s = MaterialTheme.status
    Surface(color = s.infoContainer, contentColor = s.onInfoContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(FirstAidLogic.NO_SENSITIVE_NOTICE, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun flagIcon(label: String): ImageVector = when (label) {
    "Anaphylaxis risk" -> Icons.Outlined.Warning
    "Refrigerated medication" -> Icons.Outlined.AcUnit
    "Cross-contamination risk" -> Icons.Outlined.Restaurant
    "High support needs" -> Icons.Outlined.SupportAgent
    else -> Icons.Outlined.MedicalServices
}

@Composable
fun FirstAidCard(p: Participant, sensitive: Boolean, tz: String?, onOpen: () -> Unit, onCall: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    val urgent = FirstAidLogic.priority(p, sensitive)
    val accent = when (urgent) {
        0 -> s.danger
        1, 2 -> s.warning
        else -> cs.outlineVariant
    }
    val flags = FirstAidLogic.flags(p)
    val details = FirstAidLogic.details(p, sensitive)
    val contacts = FirstAidLogic.contacts(p, sensitive)
    Surface(onClick = onOpen, color = cs.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.padding(start = 10.dp, top = 18.dp, bottom = 18.dp).width(4.dp).fillMaxHeight().clip(CircleShape).background(accent))
            Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.fullName ?: p.name, p.headshotUrl, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.fullName?.takeIf { it.isNotBlank() } ?: p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (!p.pronouns.isNullOrBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(p.pronouns, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        Text(FirstAidLogic.presence(p, tz), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (p.isCheckedIn) Pill("Here", s.successContainer, s.onSuccessContainer, icon = Icons.Outlined.CheckCircle)
                    else Pill("Not here", cs.surfaceContainerHighest, cs.onSurfaceVariant, icon = Icons.Outlined.RadioButtonUnchecked)
                }
                if (flags.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        flags.forEach { f ->
                            Pill(
                                f.label,
                                if (f.danger) s.dangerContainer else s.warningContainer,
                                if (f.danger) s.onDangerContainer else s.onWarningContainer,
                                icon = flagIcon(f.label),
                            )
                        }
                    }
                }
                if (details.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        details.forEach { d ->
                            Column(Modifier.semantics(mergeDescendants = true) {}) {
                                Text(d.label, style = MaterialTheme.typography.labelMedium, color = if (d.danger) s.danger else cs.onSurfaceVariant)
                                Text(
                                    d.value,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (d.danger) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (d.danger) s.danger else cs.onSurface,
                                )
                            }
                        }
                    }
                }
                if (contacts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = cs.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Contacts", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    contacts.forEach { c ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    listOfNotNull(c.name, c.relationship?.takeIf { !it.equals(c.name, true) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                (c.phone ?: c.email)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1) }
                            }
                            if (c.phone != null) {
                                FilledTonalIconButton(onClick = { onCall(c.phone) }, modifier = Modifier.semantics { contentDescription = "Call ${c.name}" }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Outlined.Call, null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
