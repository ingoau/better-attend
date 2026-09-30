package au.ingo.betterattend.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time

/** Bottom sheet listing the organizer's events grouped into Happening now / Upcoming / Past. */
@Composable
fun EventPickerSheet(
    events: List<Event>,
    selectedId: String?,
    onSelect: (Event) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = events.size > 6)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        EventPickerContent(events, selectedId, onSelect)
    }
}

@Composable
fun EventPickerContent(events: List<Event>, selectedId: String?, onSelect: (Event) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val grouped = remember(events, query) {
        val q = query.trim().lowercase()
        val filtered = events.filter { e ->
            q.isEmpty() || q.split(' ').all { t -> listOfNotNull(e.name, e.slug, e.locationCity).any { it.lowercase().contains(t) } }
        }
        val byPhase = filtered.groupBy { Time.phase(it.startsAt, it.endsAt) }
        listOf(
            "Happening now" to byPhase[Time.Phase.Live].orEmpty(),
            "Upcoming" to byPhase[Time.Phase.Upcoming].orEmpty().sortedBy { it.startsAt },
            "Past" to byPhase[Time.Phase.Past].orEmpty().sortedByDescending { it.startsAt },
            "Undated" to byPhase[Time.Phase.Unknown].orEmpty(),
        ).filter { it.second.isNotEmpty() }
    }
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth()) {
        Text("Choose event", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(12.dp))
        if (events.size > 5) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search events") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (grouped.isEmpty()) {
            EmptyState(Icons.Outlined.EventBusy, if (events.isEmpty()) "No events yet" else "No matches",
                body = if (events.isEmpty()) "Ask an event admin to add you as staff on attend.hackclub.com." else null)
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            grouped.forEach { (title, list) ->
                item(key = "h_$title") { SectionHeader(title, Modifier.animateItem().padding(horizontal = 8.dp)) }
                items(list, key = { it.id }) { event ->
                    EventRow(event, event.id == selectedId, title == "Happening now", Modifier.animateItem()) {
                        haptics.confirm()
                        onSelect(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: Event, selected: Boolean, live: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(event.name, event.logoUrl, size = 44.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(event.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(Time.range(event.startsAt, event.endsAt, event.timezone), event.locationCity).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (live) {
                Pill("Live", MaterialTheme.status.successContainer, MaterialTheme.status.onSuccessContainer)
                Spacer(Modifier.width(8.dp))
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}
