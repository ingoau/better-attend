package au.ingo.betterattend.ui.people

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time
import java.time.Instant

fun noteTypeLabel(t: String) = when (t) { "safeguarding" -> "Safeguarding"; "logistical" -> "Logistics"; else -> "Ops" }

private val noteTypeIcons = mapOf("ops" to Icons.Outlined.Build, "safeguarding" to Icons.Outlined.Shield, "logistical" to Icons.Outlined.LocalShipping)

/**
 * Notes card: composer on top (type + visibility as connected button groups), newest notes below.
 * [onAdd] returns true when the note was accepted so the composer clears.
 */
@Composable
fun NotesSection(
    notes: List<NoteItem>?,
    error: String?,
    now: Instant,
    onAdd: (content: String, type: String, sensitivity: String) -> Boolean,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    initialDraft: String = "",
    initialType: String = "ops",
    initialSensitivity: String = "normal",
) {
    SectionCard(if (notes.isNullOrEmpty()) "Notes" else "Notes (${notes.size})", Icons.AutoMirrored.Outlined.StickyNote2) {
        NoteComposer(onAdd, initialDraft, initialType, initialSensitivity)
        when {
            notes == null && error != null -> Text(
                "Couldn't load notes. $error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            notes.isNullOrEmpty() -> Text(
                if (notes == null) "Loading notes…" else "No notes yet. Handovers, incidents and requests go here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            // New notes grow in rather than shoving the list down in one frame.
            else -> Column(
                Modifier.padding(top = 12.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notes.forEach { key(it.note.id) { NoteRow(it, now, onRetry, onDiscard) } }
            }
        }
    }
}

@Composable
fun NoteComposer(
    onAdd: (content: String, type: String, sensitivity: String) -> Boolean,
    initialDraft: String = "",
    initialType: String = "ops",
    initialSensitivity: String = "normal",
) {
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    var sensitivity by rememberSaveable { mutableStateOf(initialSensitivity) }
    val tooLong = draft.trim().length > MAX_NOTE_LENGTH
    val haptics = rememberHaptics()
    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Add a note for the team…") },
            minLines = 2,
            maxLines = 6,
            isError = tooLong,
            supportingText = {
                Text(
                    if (tooLong) "Too long: ${draft.trim().length}/$MAX_NOTE_LENGTH" else "${draft.trim().length}/$MAX_NOTE_LENGTH",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        ConnectedChoice(NOTE_TYPES.map { it to noteTypeLabel(it) }, type, { if (it != type) haptics.tick(); type = it })
        Spacer(Modifier.height(8.dp))
        Text("Sensitivity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        ConnectedChoice(
            listOf("normal" to "Normal", "restricted" to "Restricted"),
            sensitivity, { if (it != sensitivity) haptics.tick(); sensitivity = it },
            icons = mapOf("normal" to Icons.Outlined.LockOpen, "restricted" to Icons.Outlined.Lock),
        )
        if (sensitivity == "restricted") {
            // Attend flags restricted notes but doesn't hide them from other staff, so say so.
            Text(
                "Marked restricted. Staff with People access can still read it, so keep details to what's needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (onAdd(draft, type, sensitivity)) draft = "" },
            enabled = draft.isNotBlank() && !tooLong,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add note")
        }
    }
}

@Composable
private fun NoteRow(item: NoteItem, now: Instant, onRetry: (String) -> Unit, onDiscard: (String) -> Unit) {
    val n = item.note
    val restricted = n.sensitivity == "restricted"
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        border = if (restricted) BorderStroke(1.dp, MaterialTheme.status.warning) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        n.author?.name ?: n.author?.email ?: "Someone",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        " · " + (Time.ago(n.createdAt, now) ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Pill(noteTypeLabel(n.noteType), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, icon = noteTypeIcons[n.noteType])
                if (restricted) {
                    Spacer(Modifier.width(4.dp))
                    Pill("Restricted", MaterialTheme.status.warningContainer, MaterialTheme.status.onWarningContainer, icon = Icons.Outlined.Lock)
                }
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer { Text(n.content, style = MaterialTheme.typography.bodyLarge) }
            when {
                item.pending -> Text(
                    "Saving…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                item.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Not saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onDiscard(n.id) }) { Text("Discard") }
                    TextButton(onClick = { onRetry(n.id) }) { Text("Retry") }
                }
            }
        }
    }
}
