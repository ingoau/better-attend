package au.ingo.betterattend.ui.scan

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.Participant
import au.ingo.betterattend.ui.components.Avatar
import au.ingo.betterattend.ui.components.Pill
import au.ingo.betterattend.ui.theme.status
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ResultKind.badgeShape(): Shape = when (this) {
    ResultKind.Scanned -> MaterialShapes.SoftBurst.toShape()
    ResultKind.AlreadyScanned -> MaterialShapes.Cookie9Sided.toShape()
    ResultKind.SavedOffline -> MaterialShapes.Cookie6Sided.toShape()
    ResultKind.Rejected -> MaterialShapes.Cookie4Sided.toShape()
    ResultKind.Checking, ResultKind.Undone -> CircleShape
}

/** The big colour-coded icon badge for an outcome (spinner while checking). */
@Composable
fun OutcomeBadge(kind: ResultKind, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val c = kind.colors()
    Box(modifier.size(size).clip(kind.badgeShape()).background(c.strong), contentAlignment = Alignment.Center) {
        if (kind == ResultKind.Checking) LoadingIndicator(Modifier.size(size * 0.8f), color = c.onStrong)
        else Icon(kind.icon(), null, Modifier.size(size * 0.5f), tint = c.onStrong)
    }
}

/**
 * The scanner's result card: outcome, who it was, safety alerts and actions. Swipe down or tap
 * close to dismiss. The outcome is announced to screen readers via a live region.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanResultCard(
    card: ScanCard,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
    onDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = card.kind.colors()
    val scope = rememberCoroutineScope()
    val offset = remember(card.key) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val p = card.participant

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceAtLeast(0f)) } },
                onDragStopped = { v ->
                    if (offset.value > threshold || v > 1200f) onDismiss() else offset.animateTo(0f)
                },
            ),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(10.dp).animateContentSize()) {
            // Drag handle (also tappable to dismiss).
            Box(
                Modifier.fillMaxWidth().height(14.dp).clickable(onClickLabel = "Dismiss result", onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
            // Outcome header.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.container).padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clearAndSetSemantics {
                        liveRegion = if (card.kind == ResultKind.Checking) LiveRegionMode.Polite else LiveRegionMode.Assertive
                        contentDescription = listOfNotNull(card.title, p?.name, card.contextName, card.message).joinToString(". ")
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutcomeBadge(card.kind)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(card.title, style = MaterialTheme.typography.headlineSmall, color = colors.onContainer, maxLines = 1)
                        card.message?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss result", tint = colors.onContainer) }
            }

            if (p != null) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.name, p.headshotUrl, size = 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val full = p.fullName?.takeIf { it.isNotBlank() }
                        val headline = if (full != null && full.startsWith(p.name)) full else p.name
                        Text(headline, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(full?.takeIf { it != headline }, p.pronouns, card.contextName).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                SafetyAlerts(p, Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp))
            } else if (card.contextName != null && card.kind != ResultKind.Rejected) {
                Text(card.contextName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp))
            }

            val showActions = card.canUndo || card.retryable || (onDetails != null && p != null) || card.busy
            if (showActions) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (card.canUndo || card.busy) {
                        TextButton(onClick = onUndo, enabled = !card.busy, shapes = ButtonDefaults.shapes()) {
                            if (card.busy) LoadingIndicator(Modifier.size(18.dp)) else Icon(Icons.AutoMirrored.Outlined.Undo, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Undo")
                        }
                    }
                    if (card.retryable) {
                        OutlinedButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (onDetails != null && p != null) {
                        FilledTonalButton(onClick = onDetails, shapes = ButtonDefaults.shapes()) {
                            Text("Details"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            } else Spacer(Modifier.height(4.dp))
        }
    }
}

/** Prominent pills for the minimum safety information staff must see at a glance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SafetyAlerts(p: Participant, modifier: Modifier = Modifier) {
    if (!p.hasSafetyAlert) return
    val s = MaterialTheme.status
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p.hasAnaphylaxisRisk) Pill("Anaphylaxis risk", s.danger, s.onDanger, icon = Icons.Outlined.MedicalServices)
        if (p.requiresRefrigeration) Pill("Refrigerated meds", s.info, s.onInfo, icon = Icons.Outlined.AcUnit)
        if (p.highSupportFlag) Pill("High support", s.warning, s.onWarning, icon = Icons.Outlined.Groups)
    }
}

/** Keeps [card] visible; the kiosk variant hides after a delay. */
@Composable
fun AutoHide(key: String, millis: Long, enabled: Boolean, onHide: () -> Unit) {
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        kotlinx.coroutines.delay(millis)
        onHide()
    }
}
