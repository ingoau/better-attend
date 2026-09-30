package au.ingo.betterattend.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.ScanContext
import au.ingo.betterattend.ui.theme.status

/** Up to this many contexts are shown as a connected button group; beyond, a picker sheet. */
private const val MAX_INLINE = 4

/**
 * The "where am I scanning?" control at the top of the scanner. Connected toggle buttons for a
 * handful of contexts, one wide button + sheet for many, a static pill for one, nothing for none.
 */
@Composable
fun ContextSelector(
    contexts: List<ScanContext>?,
    selectedId: String?,
    timezone: String?,
    error: String?,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        contexts == null && error == null -> Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Loading checkpoints…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        contexts == null -> Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.status.warning)
            Spacer(Modifier.width(10.dp))
            Text("Couldn't load checkpoints. Scans still work if the event has just one.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        contexts.isEmpty() -> Unit
        contexts.size == 1 -> SingleContext(contexts.first(), timezone, modifier)
        contexts.size <= MAX_INLINE -> ConnectedContexts(contexts, selectedId, timezone, onSelect, modifier)
        else -> ContextPickerButton(contexts, selectedId, timezone, onSelect, modifier)
    }
}

@Composable
private fun ContextLabel(ctx: ScanContext, timezone: String?, emphasize: Boolean) {
    Column {
        Text(ctx.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val window = if (ctx.isLive()) "Now" else ctx.windowLabel(timezone)
        if (window != null) {
            Text(window, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = if (emphasize) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SingleContext(ctx: ScanContext, timezone: String?, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
        modifier = modifier.semantics { contentDescription = "Scanning at ${ctx.name}" },
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ctx.icon(), null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            ContextLabel(ctx, timezone, emphasize = true)
        }
    }
}

@Composable
private fun ConnectedContexts(contexts: List<ScanContext>, selectedId: String?, timezone: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        contexts.forEachIndexed { i, ctx ->
            val checked = ctx.id == selectedId
            ToggleButton(
                checked = checked,
                onCheckedChange = { onSelect(ctx.id) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    contexts.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.heightIn(min = 56.dp).semantics {
                    role = Role.RadioButton
                    stateDescription = if (checked) "Selected" else "Not selected"
                },
            ) {
                Icon(if (checked) Icons.Outlined.CheckCircle else ctx.icon(), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                ContextLabel(ctx, timezone, emphasize = checked)
            }
        }
    }
}

@Composable
private fun ContextPickerButton(contexts: List<ScanContext>, selectedId: String?, timezone: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = contexts.firstOrNull { it.id == selectedId }
    Surface(
        onClick = { open = true },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Checkpoint: ${selected?.name ?: "none"}. Tap to change." },
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(selected?.icon() ?: Icons.Outlined.UnfoldMore, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (selected != null) ContextLabel(selected, timezone, emphasize = true)
                else Text("Choose a checkpoint", style = MaterialTheme.typography.labelLarge)
            }
            Text("${contexts.size} checkpoints", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.UnfoldMore, null)
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ContextListContent(contexts, selectedId, timezone) { open = false; onSelect(it) }
        }
    }
}

/** Sheet body listing every context with its icon and window. */
@Composable
fun ContextListContent(contexts: List<ScanContext>, selectedId: String?, timezone: String?, onSelect: (String) -> Unit) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text("Choose checkpoint", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            items(contexts, key = { it.id }) { ctx ->
                val checked = ctx.id == selectedId
                ListItem(
                    headlineContent = { Text(ctx.name) },
                    supportingContent = {
                        val parts = listOfNotNull(
                            if (ctx.checksIn) "Checks people in" else if (ctx.isTravelPickup) "Airport pickup" else null,
                            if (ctx.isLive()) "Happening now" else ctx.windowLabel(timezone),
                        )
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                    },
                    leadingContent = { Icon(ctx.icon(), null) },
                    trailingContent = { if (checked) Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { role = Role.RadioButton }
                        .clickable { onSelect(ctx.id) },
                )
            }
        }
    }
}
