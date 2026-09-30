package au.ingo.betterattend.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Commute
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time

/** Label + icon + colours for a participant's state, so status never relies on colour alone. */
@Immutable
data class StatusVisual(val label: String, val icon: ImageVector, val container: Color, val content: Color, val strong: Color)

@Composable
fun statusVisual(p: Participant): StatusVisual {
    val s = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    return when {
        p.status == "withdrawn" -> StatusVisual("Withdrawn", Icons.Outlined.Block, cs.surfaceContainerHighest, cs.onSurfaceVariant, cs.onSurfaceVariant)
        p.status == "rejected" -> StatusVisual("Rejected", Icons.Outlined.Block, cs.surfaceContainerHighest, cs.onSurfaceVariant, cs.onSurfaceVariant)
        p.isCheckedIn -> StatusVisual("Here", Icons.Outlined.CheckCircle, s.successContainer, s.onSuccessContainer, s.success)
        p.status == "complete" -> StatusVisual("Not here", Icons.Outlined.RadioButtonUnchecked, cs.surfaceContainerHigh, cs.onSurfaceVariant, cs.outline)
        p.status == "awaiting_guardian" -> StatusVisual("Awaiting parent", Icons.Outlined.FamilyRestroom, s.warningContainer, s.onWarningContainer, s.warning)
        p.status == "invited" -> StatusVisual("Invited", Icons.Outlined.MarkEmailUnread, s.infoContainer, s.onInfoContainer, s.info)
        else -> StatusVisual("Registering", Icons.Outlined.HourglassTop, s.warningContainer, s.onWarningContainer, s.warning)
    }
}

/** Human label for a raw §8.1 status (filter sheet, detail). */
fun statusLabel(status: String?): String = when (status) {
    "invited" -> "Invited"
    "in_progress" -> "Registering"
    "awaiting_guardian" -> "Awaiting parent"
    "complete" -> "Complete"
    "withdrawn" -> "Withdrawn"
    "rejected" -> "Rejected"
    null -> "Unknown"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun StatusPill(p: Participant, modifier: Modifier = Modifier) {
    val v = statusVisual(p)
    Pill(v.label, v.container, v.content, modifier, icon = v.icon)
}

/** A safety flag shown as a compact icon in lists and a full banner on the detail screen. */
data class SafetyFlag(val label: String, val icon: ImageVector, val danger: Boolean)

fun safetyFlags(p: Participant): List<SafetyFlag> = buildList {
    if (p.hasAnaphylaxisRisk) add(SafetyFlag("Anaphylaxis risk", Icons.Outlined.Warning, danger = true))
    if (p.requiresRefrigeration) add(SafetyFlag("Refrigerated medication", Icons.Outlined.AcUnit, danger = false))
    if (p.highSupportFlag) add(SafetyFlag("High support needs", Icons.Outlined.SupportAgent, danger = false))
    if (p.travelInbound?.isUnaccompaniedMinor == true || p.travelOutbound?.isUnaccompaniedMinor == true)
        add(SafetyFlag("Unaccompanied minor travel", Icons.Outlined.ChildCare, danger = false))
}

/** Tiny tinted icons for list rows; announced as one phrase for TalkBack. */
@Composable
fun AlertIcons(p: Participant, modifier: Modifier = Modifier) {
    val flags = safetyFlags(p)
    if (flags.isEmpty()) return
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = flags.joinToString { it.label } },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        flags.forEach { f ->
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (f.danger) MaterialTheme.status.dangerContainer else MaterialTheme.status.warningContainer,
                contentColor = if (f.danger) MaterialTheme.status.onDangerContainer else MaterialTheme.status.onWarningContainer,
            ) {
                Icon(f.icon, null, Modifier.padding(3.dp).size(14.dp))
            }
        }
    }
}

fun travelModeIcon(mode: String?): ImageVector = when (mode) {
    "plane" -> Icons.Outlined.Flight
    "train" -> Icons.Outlined.Train
    "car" -> Icons.Outlined.DirectionsCar
    "bus" -> Icons.Outlined.DirectionsBus
    else -> Icons.Outlined.Commute
}

fun travelModeLabel(mode: String?): String = when (mode) {
    "plane" -> "Flight"
    "train" -> "Train"
    "car" -> "Car"
    "bus" -> "Bus"
    null -> "Travel"
    else -> "Other"
}

/** "Checked in 9:41 AM · Check-in desk" or null. */
fun checkInLine(p: Participant, tz: String?): String? {
    if (!p.isCheckedIn) return null
    val scan = PeopleFilter.checkInScan(p)
    val time = Time.time(p.checkedInAt, tz) ?: return "Checked in"
    return listOfNotNull("Checked in $time", scan?.scanContextName).joinToString(" · ")
}

@Composable
fun ParticipantRow(
    p: Participant,
    timezone: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val v = statusVisual(p)
    val secondary = checkInLine(p, timezone) ?: p.email ?: statusLabel(p.status)
    Surface(onClick = onClick, color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(p.fullName ?: p.name, p.headshotUrl, size = 44.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!p.pronouns.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            p.pronouns,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    AlertIcons(p)
                }
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            // Icon + short label so state reads without colour.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(64.dp).clearAndSetSemantics { contentDescription = v.label },
            ) {
                Icon(v.icon, null, tint = v.strong, modifier = Modifier.size(22.dp))
                Text(v.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
