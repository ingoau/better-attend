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
            else -> CheckInCounts(org, now, compact, large)
        }
    }
}

@Composable
private fun CheckInCounts(org: OrganizerWidgetData, now: Instant, compact: Boolean, large: Boolean) {
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(vertical = 12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(org.eventName, style = textStyle(13.sp, c.onSurfaceVariant, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            if (!compact) RefreshButton()
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = GlanceModifier.semantics { contentDescription = "${org.checkedIn} of ${org.expected} checked in" },
        ) {
            Text("${org.checkedIn}", style = textStyle(if (compact) 34.sp else 44.sp, c.primary, FontWeight.Bold), maxLines = 1)
            Text(" / ${org.expected}", style = textStyle(if (compact) 15.sp else 18.sp, c.onSurfaceVariant, FontWeight.Medium),
                maxLines = 1, modifier = GlanceModifier.padding(bottom = if (compact) 5.dp else 7.dp))
            if (!compact) {
                Spacer(GlanceModifier.defaultWeight())
                if (org.lastHour > 0) {
                    Text("+${org.lastHour} last hour", style = textStyle(13.sp, c.tertiary, FontWeight.Medium), maxLines = 1,
                        modifier = GlanceModifier.padding(bottom = 8.dp))
                }
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        LinearProgressIndicator(
            progress = org.progress,
            color = c.primary,
            backgroundColor = c.secondaryContainer,
            modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadiusCompat(4.dp),
        )
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (org.notArrived == 0) "Everyone's here" else "${org.notArrived} not here yet",
                style = textStyle(12.sp, c.onSurface, FontWeight.Medium), maxLines = 1, modifier = GlanceModifier.defaultWeight(),
            )
            if (!compact) Text(updatedLabel(org.updatedAt, now), style = textStyle(11.sp, c.onSurfaceVariant), maxLines = 1)
        }
        if (large && org.contexts.isNotEmpty()) {
            Spacer(GlanceModifier.height(10.dp))
            Column(GlanceModifier.fillMaxWidth().cornerRadiusCompat(16.dp).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)) {
                org.contexts.take(4).forEach { ctx ->
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

internal fun updatedLabel(iso: String?, now: Instant): String =
    Time.ago(iso, now)?.let { if (it == "just now") "Updated just now" else "Updated $it" } ?: "Not synced yet"
