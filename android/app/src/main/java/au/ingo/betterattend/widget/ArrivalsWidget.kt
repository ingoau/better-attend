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
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.unit.ColorProvider
import au.ingo.betterattend.R
import au.ingo.betterattend.util.Time
import java.time.Instant

/** Organizer widget for travel events: who's waiting at the airport and who's next. */
class ArrivalsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.Small, WidgetSizes.Wide))
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetSizes.Wide))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.current(context)
        provideContent {
            val snap by WidgetUpdater.snapshot.collectAsState()
            AttendGlanceTheme { ArrivalsContent(snap ?: initial, Instant.now()) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AttendGlanceTheme { ArrivalsContent(WidgetSamples.snapshot, WidgetSamples.now) } }
    }
}

class ArrivalsWidgetReceiver : AttendWidgetReceiver() {
    override val glanceAppWidget = ArrivalsWidget()
}

@Composable
fun ArrivalsContent(snap: WidgetSnapshot, now: Instant) {
    val context = LocalContext.current
    val compact = LocalSize.current.width < WidgetSizes.Wide.width
    val org = snap.organizer
    WidgetFrame(onClick = openApp(context, if (org?.travelEnabled == true) "travel" else "home")) {
        val travel = org?.travel
        when {
            !snap.signedIn -> SignedOutMessage(compact)
            org == null -> WidgetMessage(R.drawable.ic_widget_event, "No event selected", "Open Attend to choose one", compact)
            !org.travelEnabled -> WidgetMessage(R.drawable.ic_widget_flight_land, "No travel for ${org.eventName}", "Arrivals appear for events with travel", compact)
            travel == null -> WidgetMessage(R.drawable.ic_widget_flight_land, "Arrivals", "Open Attend to load travel", compact)
            else -> ArrivalCounts(org.eventName, org.timezone, travel, now, compact)
        }
    }
}

@Composable
private fun ArrivalCounts(eventName: String, tz: String?, t: TravelWidgetData, now: Instant, compact: Boolean) {
    val c = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize().padding(top = 6.dp, bottom = 8.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_widget_flight_land, c.primary, 16.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(if (compact) "Arrivals" else "Arrivals · $eventName", style = textStyle(13.sp, c.onSurfaceVariant, FontWeight.Medium), maxLines = 1,
                modifier = GlanceModifier.defaultWeight())
            if (!compact) RefreshButton()
        }
        Spacer(GlanceModifier.defaultWeight())
        if (compact) {
            Text("${t.awaitingPickup}", style = textStyle(32.sp, c.primary, FontWeight.Bold), maxLines = 1,
                modifier = GlanceModifier.semantics { contentDescription = "${t.awaitingPickup} awaiting pickup" })
            Text("awaiting pickup", style = textStyle(12.sp, c.onSurface, FontWeight.Medium), maxLines = 1)
        } else {
            Row(GlanceModifier.fillMaxWidth()) {
                StatTile("${t.awaitingPickup}", "Waiting", c.primaryContainer, c.onPrimaryContainer, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                StatTile("${t.collected}", "Collected", c.secondaryContainer, c.onSecondaryContainer, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                StatTile("${t.checkedIn}", "Checked in", c.secondaryContainer, c.onSecondaryContainer, GlanceModifier.defaultWeight())
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Text(nextArrivalLabel(t, tz, compact), style = textStyle(12.sp, c.onSurfaceVariant), maxLines = 1)
    }
}

@Composable
private fun StatTile(value: String, label: String, bg: ColorProvider, fg: ColorProvider, modifier: GlanceModifier) {
    Column(
        modifier.cornerRadiusCompat(14.dp).background(bg).padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "$value $label" },
    ) {
        Text(value, style = textStyle(20.sp, fg, FontWeight.Bold), maxLines = 1)
        Text(label, style = textStyle(10.sp, fg), maxLines = 1)
    }
}

internal fun nextArrivalLabel(t: TravelWidgetData, tz: String?, compact: Boolean): String {
    val at = Time.time(t.nextArrivalAt, tz) ?: return "No more arrivals scheduled"
    val who = t.nextArrivalName ?: "Someone"
    val minor = if (t.nextArrivalMinor) " (minor)" else ""
    return if (compact) "Next $at · $who" else listOfNotNull("Next: $who$minor · $at", t.nextArrivalReference).joinToString(" · ")
}
