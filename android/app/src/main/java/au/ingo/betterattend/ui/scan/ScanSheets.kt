package au.ingo.betterattend.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.data.repo.PendingScan
import au.ingo.betterattend.data.repo.ScanInput
import au.ingo.betterattend.data.repo.ScanLogEntry
import au.ingo.betterattend.data.repo.ScanOutcome
import au.ingo.betterattend.scan.RejectReason
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.EmptyState
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time

@Composable
private fun SheetTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------- Find person

/**
 * Manual check-in: filters the cached roster instantly, then merges server results. Typed or pasted
 * ticket ids can be checked in directly; 8-character short codes resolve through search.
 */
@Composable
fun FindPersonContent(
    state: SearchUiState,
    contextName: String?,
    checksIn: Boolean,
    timezone: String?,
    selectedContextId: String?,
    onQuery: (String) -> Unit,
    onCheckIn: (Participant) -> Unit,
    onSubmitDirect: (ScanInput) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val action = if (checksIn) "Check in" else "Scan"
    Column(modifier.fillMaxWidth()) {
        SheetTitle("Find person", contextName?.let { "$action at $it" })
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            placeholder = { Text("Name, email, ticket code or ID") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                state.directInput?.let(onSubmitDirect) ?: state.results.singleOrNull()?.let(onCheckIn)
            }),
        )
        Box(Modifier.fillMaxWidth().height(8.dp)) {
            if (state.remoteLoading) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 2.dp))
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.heightIn(min = 240.dp)) {
            state.directInput?.let { input ->
                item(key = "direct") {
                    DirectInputRow(input, action, Modifier.animateItem()) { onSubmitDirect(input) }
                }
            }
            if (!state.canSearch) {
                item(key = "no-search") {
                    Hint("Your role can't look people up. Scan their ticket, tap their badge, or paste their ticket ID above.")
                }
            } else if (state.query.isBlank()) {
                item(key = "hint") {
                    Hint(
                        if (state.rosterEmpty) "Type a name, email, or the 8-character code on their ticket."
                        else "Type a name, email, or the 8-character code on their ticket. Results appear instantly, even offline.",
                    )
                }
            } else if (state.results.isEmpty() && !state.remoteLoading && state.directInput == null) {
                item(key = "empty") {
                    EmptyState(Icons.Outlined.PersonSearch, "No one matches “${state.query.trim()}”",
                        body = state.remoteError?.let { "Showing offline results only. $it" } ?: "Check the spelling, or try their email.")
                }
            }
            if (state.results.isNotEmpty() && state.remoteError != null) {
                item(key = "remote-error") { Hint("Showing offline results only. ${state.remoteError}") }
            }
            items(state.results, key = { it.participantEventId }) { p ->
                PersonRow(p, action, timezone, selectedContextId, Modifier.animateItem()) { onCheckIn(p) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
}

@Composable
private fun DirectInputRow(input: ScanInput, action: String, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("Use this ticket ID") },
        supportingContent = { Text(input.participantId.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.QrCode2, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        },
        trailingContent = { Button(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(action) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)),
    )
}

@Composable
private fun PersonRow(p: Participant, action: String, timezone: String?, selectedContextId: String?, modifier: Modifier, onAction: () -> Unit) {
    val here = p.scansByContext.firstOrNull { it.scanContextId == selectedContextId }
    val statusText: String
    val statusColor: androidx.compose.ui.graphics.Color
    when {
        !p.isActive -> { statusText = (p.status ?: "inactive").replaceFirstChar { it.uppercase() }; statusColor = MaterialTheme.status.danger }
        here != null -> { statusText = "Scanned here ${here.firstScannedAt?.let { firstScanLabel(it, timezone) }.orEmpty()}".trim(); statusColor = MaterialTheme.status.success }
        p.isCheckedIn -> { statusText = "Checked in"; statusColor = MaterialTheme.status.success }
        else -> { statusText = "Not checked in"; statusColor = MaterialTheme.colorScheme.onSurfaceVariant }
    }
    ListItem(
        headlineContent = { Text(p.fullName ?: p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val sub = listOfNotNull(p.pronouns, p.shortCode).joinToString(" · ")
                Text(sub, maxLines = 1)
                Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
        },
        leadingContent = { Avatar(p.name, p.headshotUrl, size = 44.dp) },
        trailingContent = {
            if (here != null || !p.isActive) FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
            else Button(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
        },
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

// ---------------------------------------------------------------- Offline queue

@Composable
fun PendingQueueContent(
    pending: List<PendingScan>,
    syncing: Boolean,
    timezone: String?,
    onSyncNow: () -> Unit,
    onDiscard: (PendingScan) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SheetTitle(
            "Waiting to sync",
            if (pending.isEmpty()) null else "${pending.size} ${if (pending.size == 1) "scan" else "scans"} saved while offline",
        )
        if (pending.isEmpty()) {
            EmptyState(Icons.Outlined.CloudDone, "All caught up", body = "Every scan has reached Attend.")
            return
        }
        Surface(
            color = MaterialTheme.status.infoContainer,
            contentColor = MaterialTheme.status.onInfoContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("These send automatically once you're connected. Their original scan times are kept.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSyncNow, enabled = !syncing, shapes = ButtonDefaults.shapes()) {
                    if (syncing) LoadingIndicator(Modifier.size(18.dp)) else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (syncing) "Syncing" else "Sync now")
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            items(pending, key = { it.clientScanId }) { p ->
                ListItem(
                    headlineContent = { Text(p.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(listOfNotNull(p.scanContextName, "saved ${Time.time(p.scannedAt, timezone)}").joinToString(" · "))
                            p.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    },
                    leadingContent = { Icon(Icons.Outlined.CloudQueue, null, tint = MaterialTheme.status.info) },
                    trailingContent = {
                        IconButton(onClick = { onDiscard(p) }) { Icon(Icons.Outlined.DeleteOutline, "Discard scan for ${p.displayName()}") }
                    },
                    // Synced or discarded rows slide away instead of vanishing.
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Recent scans

fun ScanOutcome.kind(): ResultKind = when (this) {
    is ScanOutcome.Scanned -> ResultKind.Scanned
    is ScanOutcome.AlreadyScanned -> ResultKind.AlreadyScanned
    is ScanOutcome.Queued -> ResultKind.SavedOffline
    is ScanOutcome.Failed -> ResultKind.Rejected
    is ScanOutcome.Rejected -> if (reason == RejectReason.AlreadyCheckedIn) ResultKind.AlreadyScanned else ResultKind.Rejected
}

private fun ScanOutcome.label(): String = when (this) {
    is ScanOutcome.Scanned -> "Scanned"
    is ScanOutcome.AlreadyScanned -> "Already scanned"
    is ScanOutcome.Queued -> "Saved offline"
    is ScanOutcome.Failed -> if (notFound) "Not registered" else message
    is ScanOutcome.Rejected -> reason.title + if (offline) " (offline check)" else ""
}

@Composable
fun RecentScansContent(
    entries: List<ScanLogEntry>,
    timezone: String?,
    onOpen: (Participant) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SheetTitle("Recent scans", if (entries.isEmpty()) null else "From this device, newest first")
        if (entries.isEmpty()) {
            EmptyState(Icons.Outlined.History, "No scans yet", body = "Scans you make on this device appear here so you can double-check what just happened.")
            return
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(entries, key = { it.outcome.clientScanId + it.at }) { e ->
                val p = e.outcome.participant
                val kind = e.outcome.kind()
                val c = kind.colors()
                ListItem(
                    headlineContent = {
                        Text(p?.name ?: (e.outcome as? ScanOutcome.Queued)?.pending?.displayName() ?: "Unknown attendee", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(listOfNotNull(e.outcome.label(), e.contextName, Time.time(e.at, timezone)).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Box {
                            if (p != null) Avatar(p.name, p.headshotUrl, size = 44.dp)
                            else Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(c.container), contentAlignment = Alignment.Center) {
                                Icon(kind.icon(), null, tint = c.onContainer)
                            }
                            if (p != null) {
                                Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(RoundedCornerShape(10.dp)).background(c.strong), contentAlignment = Alignment.Center) {
                                    Icon(kind.icon(), null, Modifier.size(14.dp), tint = c.onStrong)
                                }
                            }
                        }
                    },
                    trailingContent = if (p != null) ({ Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }) else null,
                    modifier = Modifier.animateItem().then(
                        if (p != null) Modifier.clickable(onClickLabel = "Open ${p.name}") { onOpen(p) } else Modifier,
                    ),
                )
            }
        }
    }
}

/** Small arrangement helper for chips row above the camera. */
internal val ChipSpacing = Arrangement.spacedBy(8.dp)
