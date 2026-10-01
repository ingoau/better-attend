package au.ingo.betterattend.ui.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.ingo.betterattend.data.model.Event
import au.ingo.betterattend.ui.dashboard.DashboardLogic
import au.ingo.betterattend.ui.theme.HackClub
import au.ingo.betterattend.ui.theme.brandColorScheme
import au.ingo.betterattend.ui.theme.seededColorScheme
import au.ingo.betterattend.util.Time
import java.text.NumberFormat
import java.time.Instant

/** Printed at the bottom of every card. */
const val SHARE_CARD_LINK = "inw.sh/better-attend"

enum class ShareCardColour(val label: String, val seed: Color) {
    Red("Red", HackClub.Red),
    Orange("Orange", HackClub.Orange),
    Green("Green", HackClub.Green),
    Blue("Blue", HackClub.Blue),
    Purple("Purple", HackClub.Purple);

    fun scheme(dark: Boolean): ColorScheme = if (this == Red) brandColorScheme(dark) else seededColorScheme(seed, dark)
}

enum class ShareCardStyle(val label: String) { Tonal("Tonal"), Bold("Bold"), Playful("Playful"), Outline("Outline") }

enum class ShareCardLayout(val label: String, val icon: ImageVector) {
    Row("Row", Icons.Outlined.ViewColumn),
    Grid("Grid", Icons.Outlined.GridView),
    List("List", Icons.Outlined.ViewAgenda);

    /** Tiles per row for [count] numbers; a row never holds more than three. */
    fun columns(count: Int): Int = when (this) {
        Row -> when {
            count <= 3 -> count
            count == 4 -> 2
            else -> 3
        }
        Grid -> if (count == 1) 1 else 2
        List -> 1
    }.coerceAtLeast(1)
}

data class ShareCardOptions(
    val colour: ShareCardColour = ShareCardColour.Red,
    /** null follows the app's light / dark theme. */
    val dark: Boolean? = null,
    val style: ShareCardStyle = ShareCardStyle.Tonal,
    val layout: ShareCardLayout = ShareCardLayout.Row,
    val showIcons: Boolean = true,
    /** Event dates, city and the "as of" time. */
    val showDetails: Boolean = true,
    /** "of 120" and a progress bar for numbers that have a total. */
    val showTotals: Boolean = true,
)

/** The image that gets shared: event name, the chosen numbers and [SHARE_CARD_LINK], in its own colour scheme. */
@Composable
fun ShareCard(
    event: Event,
    stats: List<ShareStat>,
    options: ShareCardOptions,
    dark: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val scheme = remember(options.colour, dark) { options.colour.scheme(dark) }
    MaterialTheme(colorScheme = scheme) {
        val cs = MaterialTheme.colorScheme
        val outline = options.style == ShareCardStyle.Outline
        // Expressive dark schemes have bright containers, so dark cards flip which layer carries the colour.
        val (container, content) = when (options.style) {
            ShareCardStyle.Tonal -> if (dark) cs.surfaceContainerHigh to cs.onSurface else cs.primaryContainer to cs.onPrimaryContainer
            ShareCardStyle.Bold -> cs.primary to cs.onPrimary
            ShareCardStyle.Playful -> cs.surfaceContainer to cs.onSurface
            ShareCardStyle.Outline -> cs.surface to cs.onSurface
        }
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(36.dp),
            border = if (outline) BorderStroke(2.dp, cs.outline) else null,
            modifier = modifier,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(event.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = DashboardLogic.subtitle(event)
                if (options.showDetails && details != null) {
                    Text(details, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                StatTiles(stats, options, dark)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SHARE_CARD_LINK, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val asOf = Time.dayTime(now.toString(), event.timezone)
                    if (options.showDetails && asOf != null) {
                        Text("As of $asOf", style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.8f), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The container / content / accent colours for the [index]th tile. */
private data class TilePalette(val container: Color, val content: Color, val accent: Color, val border: BorderStroke?)

@Composable
private fun tilePalette(style: ShareCardStyle, index: Int, dark: Boolean): TilePalette {
    val cs = MaterialTheme.colorScheme
    return when (style) {
        ShareCardStyle.Tonal ->
            if (dark) TilePalette(cs.primaryContainer, cs.onPrimaryContainer, cs.onPrimaryContainer, null)
            else TilePalette(cs.surfaceContainerLowest, cs.onSurface, cs.primary, null)
        ShareCardStyle.Bold ->
            if (dark) TilePalette(cs.onPrimary, cs.primary, cs.primary, null)
            else TilePalette(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, null)
        ShareCardStyle.Playful -> when (index % 3) {
            0 -> TilePalette(cs.primaryContainer, cs.onPrimaryContainer, if (dark) cs.onPrimaryContainer else cs.primary, null)
            1 -> TilePalette(cs.tertiaryContainer, cs.onTertiaryContainer, if (dark) cs.onTertiaryContainer else cs.tertiary, null)
            else -> TilePalette(cs.secondaryContainer, cs.onSecondaryContainer, if (dark) cs.onSecondaryContainer else cs.secondary, null)
        }
        ShareCardStyle.Outline -> TilePalette(Color.Transparent, cs.onSurface, cs.primary, BorderStroke(1.5.dp, cs.outlineVariant))
    }
}

/** Expressive shapes the Playful style puts behind each tile's icon. */
private val playfulShapes = listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny, MaterialShapes.SoftBurst, MaterialShapes.Cookie6Sided, MaterialShapes.Flower)

@Composable
private fun StatTiles(stats: List<ShareStat>, options: ShareCardOptions, dark: Boolean) {
    val columns = options.layout.columns(stats.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(columns).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, stat ->
                    val index = row * columns + col
                    val tile = Modifier.weight(1f).fillMaxHeight()
                    val p = tilePalette(options.style, index, dark)
                    if (options.layout == ShareCardLayout.List) ListTile(stat, index, options, p, tile)
                    else StatTile(stat, index, options, p, columns, tile)
                }
                // Keep a short last row's tiles the same width as the rows above.
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(stat: ShareStat, index: Int, options: ShareCardOptions, p: TilePalette, columns: Int, modifier: Modifier) {
    Surface(color = p.container, contentColor = p.content, shape = RoundedCornerShape(24.dp), border = p.border, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            // Three across leaves no room beside the label, so the icon goes on top and every label takes two
            // lines; either way the numbers line up along each row.
            val narrow = columns >= 3
            if (narrow) {
                if (options.showIcons) {
                    TileIcon(stat.icon, index, options.style, p)
                    Spacer(Modifier.height(8.dp))
                }
                Text(stat.label, style = MaterialTheme.typography.labelLarge, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (options.showIcons) {
                        TileIcon(stat.icon, index, options.style, p)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stat.label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            val max = when (columns) { 1 -> 72.sp; 2 -> 56.sp; else -> 44.sp }
            Text(
                stat.display,
                style = MaterialTheme.typography.displayLarge,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = max),
            )
            Spacer(Modifier.weight(1f))
            Totals(stat, options, p)
        }
    }
}

@Composable
private fun ListTile(stat: ShareStat, index: Int, options: ShareCardOptions, p: TilePalette, modifier: Modifier) {
    Surface(color = p.container, contentColor = p.content, shape = RoundedCornerShape(24.dp), border = p.border, modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (options.showIcons) {
                TileIcon(stat.icon, index, options.style, p)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stat.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Totals(stat, options, p)
            }
            Spacer(Modifier.width(12.dp))
            Text(stat.display, style = MaterialTheme.typography.displaySmall, maxLines = 1)
        }
    }
}

@Composable
private fun TileIcon(icon: ImageVector, index: Int, style: ShareCardStyle, p: TilePalette) {
    if (style == ShareCardStyle.Playful) {
        val shape = playfulShapes[index % playfulShapes.size].toShape()
        Box(Modifier.size(32.dp).clip(shape).background(p.accent), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = p.container)
        }
    } else {
        Icon(icon, null, Modifier.size(20.dp), tint = p.accent)
    }
}

@Composable
private fun Totals(stat: ShareStat, options: ShareCardOptions, p: TilePalette) {
    val total = stat.total
    val fraction = stat.fraction
    if (!options.showTotals || total == null || fraction == null) return
    Text(
        "of ${NumberFormat.getIntegerInstance().format(total)}",
        style = MaterialTheme.typography.labelLarge,
        color = LocalContentColor.current.copy(alpha = 0.75f),
    )
    Spacer(Modifier.height(8.dp))
    LinearWavyProgressIndicator(
        progress = { fraction },
        color = p.accent,
        trackColor = p.accent.copy(alpha = 0.2f),
        modifier = Modifier.fillMaxWidth(),
    )
}
