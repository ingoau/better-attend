package au.ingo.betterattend.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import au.ingo.betterattend.R
import au.ingo.betterattend.util.Time
import java.time.Instant

/** Organizer widget: live check-in progress for the selected event. */
class CheckInWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.Small, WidgetSizes.Wide, WidgetSizes.Large))
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetSizes.Wide, WidgetSizes.Large))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.current(context)
        provideContent {
            val snap by WidgetUpdater.snapshot.collectAsState()
            AttendGlanceTheme { CheckInContent(snap ?: initial, Instant.now()) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AttendGlanceTheme { CheckInContent(WidgetSamples.snapshot, WidgetSamples.now) } }
    }
}

class CheckInWidgetReceiver : AttendWidgetReceiver() {
    override val glanceAppWidget = CheckInWidget()
}

@Composable
fun CheckInContent(snap: WidgetSnapshot, now: Instant) {
    val context = LocalContext.current
    val size = LocalSize.current
    val compact = size.width < WidgetSizes.Wide.width
    val large = size.height >= WidgetSizes.Large.height && !compact
    WidgetFrame(onClick = openApp(context, "home")) {
        val org = snap.organizer
        when {
            !snap.signedIn -> SignedOutMessage(compact)
            org == null -> WidgetMessage(R.drawable.ic_widget_event, "No event selected", "Open Attend to choose one", compact)
            !org.hasCounts -> WidgetMessage(R.drawable.ic_widget_groups, org.eventName, "Open Attend to sync check-ins", compact)
            org.expected == 0 -> WidgetMessage(R.drawable.ic_widget_groups, org.eventName, "No participants yet", compact)
            else -> CheckInCounts(org, now, compact, large, size.height.value)
        }
    }
}

@Composable
private fun CheckInCounts(org: OrganizerWidgetData, now: Instant, compact: Boolean, large: Boolean, heightDp: Float) {
    val c = GlanceTheme.colors
    val numberSize = when { compact -> 34.sp; large -> 44.sp; else -> 36.sp }
    val wide = !compact && !large
    Column(GlanceModifier.fillMaxSize().padding(top = 6.dp, bottom = 8.dp)) {
        if (wide) {
            // 4x2 is only ~110dp tall: title and number share the top block, refresh and trend on the right.
            Row(GlanceModifier.fillMaxWidth()) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(org.eventName, style = textStyle(13.sp, c.onSurfaceVariant, FontWeight.Medium), maxLines = 1)
                    BigCount(org, numberSize, large = false)
                }
                Column(horizontalAlignment = Alignment.End) {
                    RefreshButton()
                    if (org.lastHour > 0) Text("+${org.lastHour} last hour", style = textStyle(12.sp, c.tertiary, FontWeight.Medium), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.defaultWeight())
        } else {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(org.eventName, style = textStyle(13.sp, c.onSurfaceVariant, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                if (large) RefreshButton()
            }
            Spacer(GlanceModifier.defaultWeight())
            BigCount(org, numberSize, large, compact)
        }
        Spacer(GlanceModifier.height(4.dp))
        if (org.nobodyYet) {
            // An empty bar says nothing; keep the space so the layout doesn't jump when check-in starts.
            Spacer(GlanceModifier.height(8.dp))
        } else {
            LinearProgressIndicator(
                progress = org.progress,
                color = c.primary,
                backgroundColor = c.secondaryContainer,
                modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadiusCompat(4.dp),
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    org.nobodyYet && compact -> "expected"
                    org.nobodyYet -> "No one checked in yet"
                    org.notArrived == 0 -> "Everyone's here"
                    else -> "${org.notArrived} not here yet"
                },
                style = textStyle(12.sp, c.onSurface, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight(),
            )
            if (!compact) Text(updatedLabel(org.updatedAt, now), style = textStyle(11.sp, c.onSurfaceVariant), maxLines = 1)
        }
        // Only as many station rows as fit; the rest are a tap away in the app.
        val rows = ((heightDp - 172f) / 24f).toInt().coerceIn(0, 6)
        if (large && org.contexts.isNotEmpty() && rows > 0) {
            Spacer(GlanceModifier.height(10.dp))
            Column(GlanceModifier.fillMaxWidth().cornerRadiusCompat(16.dp).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)) {
                org.contexts.take(rows).forEach { ctx ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(
                            when { ctx.isTravel -> R.drawable.ic_widget_flight_land; ctx.checksIn -> R.drawable.ic_widget_check_circle; else -> R.drawable.ic_widget_qr_scan },
                            c.onSurfaceVariant, 14.dp,
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Text(ctx.name, style = textStyle(12.sp, c.onSurfaceVariant), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                        Text("${ctx.count}", style = textStyle(13.sp, c.onSurface, FontWeight.Bold), maxLines = 1)
                    }
                }
            }
        }
        if (compact) Spacer(GlanceModifier.height(2.dp))
    }
}

@Composable
private fun BigCount(org: OrganizerWidgetData, numberSize: androidx.compose.ui.unit.TextUnit, large: Boolean, compact: Boolean = false) {
    val c = GlanceTheme.colors
    val suffixStyle = textStyle(if (numberSize.value < 36f) 15.sp else 18.sp, c.onSurfaceVariant, FontWeight.Medium)
    val suffixPad = GlanceModifier.padding(bottom = if (large) 7.dp else 5.dp)
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = GlanceModifier.fillMaxWidth().semantics {
            contentDescription = if (org.nobodyYet) "${org.expected} expected, no one checked in yet"
                else "${org.checkedIn} of ${org.expected} checked in"
        },
    ) {
        if (org.nobodyYet) {
            // Before check-in starts, "0 / 120" buries the useful number: lead with who's coming.
            Text("${org.expected}", style = textStyle(numberSize, c.primary, FontWeight.Bold), maxLines = 1)
            if (!compact) Text(" expected", style = suffixStyle, maxLines = 1, modifier = suffixPad)
        } else {
            Text("${org.checkedIn}", style = textStyle(numberSize, c.primary, FontWeight.Bold), maxLines = 1)
            Text(" / ${org.expected}", style = suffixStyle, maxLines = 1, modifier = suffixPad)
        }
        if (large && org.lastHour > 0) {
            Spacer(GlanceModifier.defaultWeight())
            Text("+${org.lastHour} last hour", style = textStyle(13.sp, c.tertiary, FontWeight.Medium), maxLines = 1,
                modifier = GlanceModifier.padding(start = 8.dp, bottom = 9.dp))
        }
    }
}

internal fun updatedLabel(iso: String?, now: Instant): String =
    Time.ago(iso, now)?.let { if (it == "just now") "Updated just now" else "Updated $it" } ?: "Not synced yet"
