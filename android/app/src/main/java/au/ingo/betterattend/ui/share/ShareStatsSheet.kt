package au.ingo.betterattend.ui.share

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.ui.components.rememberHaptics
import au.ingo.betterattend.util.Time
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * The share screen a long-press on a Home number opens: a live preview of the card, the numbers on it
 * (starting with [startId]) and a few ways to style it, then the system share sheet with the card as a PNG.
 */
@Composable
fun ShareStatsSheet(
    event: Event,
    available: List<ShareStat>,
    startId: String,
    options: ShareCardOptions,
    onOptionsChange: (ShareCardOptions) -> Unit,
    onDismiss: () -> Unit,
    now: Instant = Instant.now(),
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var selected by rememberSaveable(startId) { mutableStateOf(arrayListOf(startId)) }
    var busy by remember { mutableStateOf(false) }
    val fileName = remember(event.name, now) { shareFileName(event, now) }

    // Android 9 and older can't add to the shared Pictures folder without a storage permission, so there the
    // user picks where the file goes instead.
    var pendingSave by remember { mutableStateOf<Bitmap?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bitmap = pendingSave
        pendingSave = null
        if (uri != null && bitmap != null) scope.launch {
            val ok = runCatching { writePng(context, uri, bitmap) }.isSuccess
            if (ok) haptics.confirm() else haptics.reject()
            Toast.makeText(context, if (ok) "Image saved" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
        }
    }

    /** Renders the preview to a bitmap and hands it to [action]; nothing happens until the preview has drawn once. */
    fun withImage(action: suspend (Bitmap) -> Unit) {
        if (busy || layer.size.width <= 0 || layer.size.height <= 0) return
        haptics.click()
        busy = true
        scope.launch {
            try {
                action(layer.toImageBitmap().asAndroidBitmap())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                haptics.reject()
                Toast.makeText(context, "Couldn't create the image", Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ShareStatsEditor(
            event = event,
            available = available,
            selected = selected,
            onSelectedChange = { selected = ArrayList(it) },
            options = options,
            onOptionsChange = onOptionsChange,
            now = now,
            busy = busy,
            // Record the preview into a layer as it draws, so sharing and saving export exactly what's on screen.
            cardModifier = Modifier.drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            },
            onShare = { withImage { shareImage(context, it, event.name) } },
            onSave = {
                withImage { bitmap ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToPictures(context, bitmap, fileName)
                        haptics.confirm()
                        Toast.makeText(context, "Saved to Pictures/$SAVE_FOLDER", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingSave = bitmap
                        saveAs.launch(fileName)
                    }
                }
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/** The sheet's contents, split out so screenshot tests can render it without a window. */
@Composable
fun ShareStatsEditor(
    event: Event,
    available: List<ShareStat>,
    selected: List<String>,
    onSelectedChange: (List<String>) -> Unit,
    options: ShareCardOptions,
    onOptionsChange: (ShareCardOptions) -> Unit,
    now: Instant,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    cardModifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val byId = available.associateBy { it.id }
    // A number can drop off Home (e.g. the event ends) while the sheet is open; never show an empty card.
    val onCard = selected.mapNotNull(byId::get).ifEmpty { available.take(1) }
    val appDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val dark = options.dark ?: appDark

    Column(modifier.fillMaxWidth()) {
        Text(
            "Share stats",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp).semantics { heading() },
        )
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
                contentDescription = "Preview: ${event.name}. " + onCard.joinToString(". ") { "${it.label} ${it.display}" }
            },
        ) {
            ShareCard(event, onCard, options, dark, now, cardModifier.fillMaxWidth())
        }

        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp),
        ) {
            Section("Numbers on the card", "Up to ${ShareStats.MAX_ON_CARD}")
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                available.forEach { stat ->
                    val isOn = stat.id in selected
                    val full = selected.size >= ShareStats.MAX_ON_CARD
                    FilterChip(
                        selected = isOn,
                        onClick = { haptics.tick(); onSelectedChange(ShareStats.toggle(selected, stat.id)) },
                        enabled = isOn || !full,
                        label = { Text("${stat.label} · ${stat.display}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(if (isOn) Icons.Outlined.Check else stat.icon, null, Modifier.size(FilterChipDefaults.IconSize))
                        },
                    )
                }
            }

            Section("Colour")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShareCardColour.entries.forEach { c ->
                    Swatch(c, selected = options.colour == c) {
                        if (options.colour != c) haptics.tick()
                        onOptionsChange(options.copy(colour = c))
                    }
                }
            }

            Section("Style")
            Connected(ShareCardStyle.entries, options.style, label = { it.label }) {
                if (options.style != it) haptics.tick()
                onOptionsChange(options.copy(style = it))
            }

            Section("Layout")
            Connected(ShareCardLayout.entries, options.layout, label = { it.label }, icon = { it.icon }) {
                if (options.layout != it) haptics.tick()
                onOptionsChange(options.copy(layout = it))
            }

            Spacer(Modifier.height(8.dp))
            Toggle("Dark card", dark) { haptics.toggle(it); onOptionsChange(options.copy(dark = it)) }
            Toggle("Icons", options.showIcons) { haptics.toggle(it); onOptionsChange(options.copy(showIcons = it)) }
            Toggle("Dates, place and time", options.showDetails) { haptics.toggle(it); onOptionsChange(options.copy(showDetails = it)) }
            Toggle("Totals and progress", options.showTotals) { haptics.toggle(it); onOptionsChange(options.copy(showTotals = it)) }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onSave,
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Save image", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            Button(
                onClick = onShare,
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Share image", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A colour seed shown in its own scheme's primary; the chosen one morphs into a cookie with a tick. */
@Composable
private fun Swatch(colour: ShareCardColour, selected: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scheme = remember(colour, dark) { colour.scheme(dark) }
    val shape = if (selected) MaterialShapes.Cookie9Sided.toShape() else CircleShape
    Box(
        Modifier.size(48.dp)
            .clip(shape)
            .background(scheme.primary)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = colour.label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, tint = scheme.onPrimary)
    }
}

/** A connected button group picking one of [options] (same pattern as Settings' theme picker). */
@Composable
private fun <T> Connected(
    options: List<T>,
    value: T,
    label: (T) -> String,
    icon: ((T) -> androidx.compose.ui.graphics.vector.ImageVector)? = null,
    onChange: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = value == option,
                onCheckedChange = { onChange(option) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                if (icon != null) {
                    Icon(icon(option), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
    )
}

/** Saved cards go in Pictures/<this>. */
private const val SAVE_FOLDER = "BetterAttend"

/** "Campfire Canberra 2026-10-03 1130.png", in the event's timezone, without characters file systems reject. */
internal fun shareFileName(event: Event, now: Instant): String {
    val at = now.atZone(Time.zone(event.timezone))
    val stamp = "%04d-%02d-%02d %02d%02d".format(at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute)
    val name = event.name.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { "Event" }
    return "$name $stamp.png"
}

/** Adds [bitmap] to the shared Pictures folder (Android 10+, no permission needed). */
private suspend fun saveToPictures(context: Context, bitmap: Bitmap, fileName: String) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SAVE_FOLDER")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: error("MediaStore refused the image")
    try {
        writePng(context, uri, bitmap)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

private suspend fun writePng(context: Context, uri: Uri, bitmap: Bitmap) = withContext(Dispatchers.IO) {
    val out = context.contentResolver.openOutputStream(uri) ?: error("Couldn't open $uri")
    out.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
}

/** Writes [bitmap] to the share cache and opens the system share sheet with it. */
private suspend fun shareImage(context: Context, bitmap: Bitmap, eventName: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        File(dir, "better-attend-stats.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, "$eventName stats")
        // Lets the share sheet show a thumbnail and the receiving app read the file.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share $eventName stats"))
}
