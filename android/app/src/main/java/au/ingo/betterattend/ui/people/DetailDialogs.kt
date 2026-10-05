package au.ingo.betterattend.ui.people

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.ingo.betterattend.data.model.ContextScanSummary
import au.ingo.betterattend.ui.theme.status
import au.ingo.betterattend.util.Time

private const val ALL = "__all__"

/**
 * Lists every context with its scan count so staff choose exactly what to remove (no 3-button
 * Android alert limit). Doubles as the confirmation because undo deletes scan records.
 */
@Composable
fun UndoCheckInDialog(
    name: String,
    scans: List<ContextScanSummary>,
    tz: String?,
    onConfirm: (scanContextId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = defaultUndoSelection(scans)
    var selected by rememberSaveable { mutableStateOf(initial) }
    BasicAlertDialog(onDismissRequest = onDismiss) {
        UndoCheckInCard(name, scans, tz, selected, { selected = it }, { onConfirm(selected.takeUnless { it == ALL }) }, onDismiss)
    }
}

/** The dialog's card, separate so it can be screenshot-tested without a window. */
@Composable
fun UndoCheckInCard(
    name: String,
    scans: List<ContextScanSummary>,
    tz: String?,
    selected: String,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.widthIn(max = 480.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.Undo, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Undo check-in for $name?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Choose which scans to delete. This can't be reversed, but you can check them in again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.selectableGroup()) {
                scans.forEach { s ->
                    val count = if (s.scanCount == 1) "1 scan" else "${s.scanCount} scans"
                    ChoiceRow(
                        title = s.scanContextName ?: "Scan",
                        subtitle = listOfNotNull(count, Time.time(s.firstScannedAt, tz)?.let { "first at $it" }).joinToString(" · "),
                        selected = selected == s.scanContextId,
                    ) { onSelect(s.scanContextId) }
                }
                if (scans.size > 1) {
                    val total = scans.sumOf { it.scanCount }
                    ChoiceRow("Everywhere", "All $total scans in ${scans.size} places", selected == ALL) { onSelect(ALL) }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                Button(
                    onClick = onConfirm,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Delete scans") }
            }
        }
    }
}

/** Default selection for [UndoCheckInCard]: the only context, else the check-in context, else everything. */
fun defaultUndoSelection(scans: List<ContextScanSummary>): String = when {
    scans.size == 1 -> scans.first().scanContextId
    else -> scans.firstOrNull { it.checksIn }?.scanContextId ?: ALL
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                else ButtonDefaults.buttonColors(),
                shapes = ButtonDefaults.shapes(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } },
    )
}

// ---------------- NFC write sheet ----------------

@Composable
fun NfcWriteSheet(
    state: NfcWriteState,
    name: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        NfcWriteSheetContent(state, name, onRetry, onOpenSettings, onDismiss)
    }
}

@Composable
fun NfcWriteSheetContent(
    state: NfcWriteState,
    name: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    animate: Boolean = true,
) {
    val s = MaterialTheme.status
    val cs = MaterialTheme.colorScheme
    data class Copy(val title: String, val body: String)
    val copy = when (state) {
        NfcWriteState.Idle, NfcWriteState.Preparing -> Copy("Getting badge ready…", "Fetching $name's badge code from Attend.")
        is NfcWriteState.Waiting -> Copy(
            "Hold the badge to the back of your phone",
            "Keep it still until it's done. " + if (state.hasSlackLink) "Any phone that taps it will open $name's Hack Club badge page."
            else "$name has no Slack account linked, so the badge will only work with Attend scanners.",
        )
        NfcWriteState.Writing -> Copy("Writing badge…", "Don't move the badge.")
        NfcWriteState.Success -> Copy("Badge ready", "$name can now tap in at any Attend scanner.")
        NfcWriteState.Unsupported -> Copy("This phone can't write badges", "It doesn't have NFC. Use another phone to write $name's badge.")
        NfcWriteState.Disabled -> Copy("Turn on NFC", "NFC is off. Switch it on in settings, then come back.")
        is NfcWriteState.Failed -> Copy("Badge not written", state.message)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        val motion = MaterialTheme.motionScheme
        // Each step (waiting → writing → done / failed) morphs in: the graphic pops, the copy crossfades.
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.7f)) togetherWith
                    (fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.9f))
            },
            contentAlignment = Alignment.Center,
            label = "nfcGraphic",
            modifier = Modifier.size(160.dp),
        ) { shown ->
            Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                when (shown) {
                    is NfcWriteState.Waiting -> WaitingGraphic(animate)
                    NfcWriteState.Preparing, NfcWriteState.Idle, NfcWriteState.Writing -> LoadingIndicator(Modifier.size(96.dp))
                    NfcWriteState.Success -> ShapeIcon(MaterialShapes.Sunny.toShape(), s.successContainer, s.onSuccessContainer, Icons.Outlined.Check)
                    NfcWriteState.Unsupported -> ShapeIcon(MaterialShapes.Cookie9Sided.toShape(), cs.surfaceContainerHighest, cs.onSurfaceVariant, Icons.Outlined.PhonelinkErase)
                    NfcWriteState.Disabled -> ShapeIcon(MaterialShapes.Cookie9Sided.toShape(), cs.secondaryContainer, cs.onSecondaryContainer, Icons.Outlined.SettingsRemote)
                    is NfcWriteState.Failed -> ShapeIcon(MaterialShapes.Cookie9Sided.toShape(), cs.errorContainer, cs.onErrorContainer, Icons.Outlined.ErrorOutline)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = copy,
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using SizeTransform(clip = false) },
            label = "nfcCopy",
        ) { shown ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    shown.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(8.dp))
                Text(shown.body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            when (state) {
                NfcWriteState.Success -> Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("Done") }
                NfcWriteState.Disabled -> {
                    OutlinedButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("Cancel") }
                    Button(onClick = onOpenSettings, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("Open settings") }
                }
                is NfcWriteState.Failed -> {
                    OutlinedButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("Close") }
                    if (state.retryable) Button(onClick = onRetry, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("Try again") }
                }
                NfcWriteState.Unsupported -> Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("OK") }
                else -> OutlinedButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).height(56.dp)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ShapeIcon(shape: androidx.compose.ui.graphics.Shape, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(140.dp).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(64.dp))
    }
}

/** Slowly spinning scalloped shape with a pulsing NFC glyph: "waiting for a tag". */
@Composable
private fun WaitingGraphic(animate: Boolean) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "nfc")
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "rot")
    val pulse by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(150.dp).rotate(if (animate) rotation else 0f).scale(if (animate) pulse else 1f)
                .clip(MaterialShapes.SoftBurst.toShape()).background(cs.primaryContainer),
        )
        Icon(Icons.Outlined.Nfc, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(64.dp))
    }
}
