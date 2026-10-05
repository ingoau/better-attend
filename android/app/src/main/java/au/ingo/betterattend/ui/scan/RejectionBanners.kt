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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.repo.ScanRejection
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time

/**
 * The scanner's interrupt: one red banner per scan the server turned down after the card said
 * "Confirming…". They stay until dismissed; tapping one opens that person.
 */
@Composable
fun ScanAlertBanners(
    alerts: List<ScanRejection>,
    timezone: String?,
    onOpen: ((ScanRejection) -> Unit)?,
    onDismiss: (ScanRejection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (alerts.isEmpty()) return
    val s = MaterialTheme.status
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        alerts.take(VISIBLE_ALERTS).forEach { a ->
            val open = onOpen?.takeIf { a.participantEventId != null }
            Surface(
                color = s.danger,
                contentColor = s.onDanger,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(onClickLabel = "Open ${a.name}") { open(a) } else Modifier),
            ) {
                Row(Modifier.heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.headline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("Rejected by Attend", a.contextName, Time.time(a.scannedAt, timezone)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (open != null) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                    IconButton(onClick = { onDismiss(a) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Outlined.Close, "Dismiss alert for ${a.name}") }
                }
            }
        }
        if (alerts.size > VISIBLE_ALERTS) {
            Text("+${alerts.size - VISIBLE_ALERTS} more rejected", style = MaterialTheme.typography.labelMedium, color = s.danger,
                modifier = Modifier.padding(start = 14.dp))
        }
    }
}

private const val VISIBLE_ALERTS = 3

/** "2 offline check-ins were rejected" */
fun offlineRejectionsTitle(count: Int): String =
    if (count == 1) "1 offline check-in was rejected" else "$count offline check-ins were rejected"

/**
 * Home's banner for queued scans the server turned down when they synced. Each row names the person
 * and the reason and opens them; it stays until dismissed.
 */
@Composable
fun OfflineRejectionsCard(
    rejections: List<ScanRejection>,
    timezone: String?,
    onOpen: ((ScanRejection) -> Unit)?,
    onDismissAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether a row may open its person (their event's access, not the current one's). */
    canOpen: (ScanRejection) -> Boolean = { true },
) {
    if (rejections.isEmpty()) return
    val s = MaterialTheme.status
    Surface(color = s.dangerContainer, contentColor = s.onDangerContainer, shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, Modifier.size(22.dp), tint = s.danger)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(offlineRejectionsTitle(rejections.size), style = MaterialTheme.typography.titleMedium)
                    Text("Scanned while offline, then turned down by Attend.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDismissAll, shapes = ButtonDefaults.shapes()) { Text("Dismiss", color = s.onDangerContainer) }
            }
            rejections.forEach { r ->
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = s.onDangerContainer.copy(alpha = 0.12f))
                val open = onOpen?.takeIf { r.participantEventId != null && canOpen(r) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (open != null) Modifier.clickable(onClickLabel = "Open ${r.name}") { open(r) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                r.reason.replaceFirstChar { it.uppercase() }, r.contextName, Time.dayTime(r.scannedAt, timezone),
                                "still recorded on Attend, undo it from their page".takeIf { r.stillRecorded },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (open != null) Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open ${r.name}", Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Shown when the encryption key is unavailable: nothing is saved on the device, so the app is online only. */
@Composable
fun StorageUnavailableBanner(modifier: Modifier = Modifier, container: Color = MaterialTheme.status.warningContainer, content: Color = MaterialTheme.status.onWarningContainer) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LockOpen, null, Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Secure storage unavailable", style = MaterialTheme.typography.titleSmall)
                Text(
                    "This phone's encryption key couldn't be used, so nothing is saved to it. BetterAttend works online only: " +
                        "offline scans are kept until the app closes, and you'll sign in again next time.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
