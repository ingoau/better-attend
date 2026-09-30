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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import au.ingo.betterattend.R
import au.ingo.betterattend.ui.tickets.TicketLogic
import java.time.Instant

/** Participant widget: the next event and how long until doors open. Tap opens the pass. */
class TicketWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.Small, WidgetSizes.Wide))
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetSizes.Small, WidgetSizes.Wide))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.current(context)
        provideContent {
            val snap by WidgetUpdater.snapshot.collectAsState()
            AttendGlanceTheme { TicketContent(snap ?: initial, Instant.now()) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AttendGlanceTheme { TicketContent(WidgetSamples.snapshot, WidgetSamples.now) } }
    }
}

class TicketWidgetReceiver : AttendWidgetReceiver() {
    override val glanceAppWidget = TicketWidget()
}

@Composable
fun TicketContent(snap: WidgetSnapshot, now: Instant) {
    val context = LocalContext.current
    val compact = LocalSize.current.width < WidgetSizes.Wide.width
    val t = snap.ticket
    val action = openApp(context, "tickets", ticketId = t?.id?.takeIf { t.confirmed })
    WidgetFrame(onClick = action) {
        when {
            !snap.signedIn -> SignedOutMessage(compact)
            t == null -> WidgetMessage(R.drawable.ic_widget_ticket, "No upcoming events", "Your next ticket will show up here", compact)
            else -> TicketSummary(t, now, compact)
        }
    }
}

@Composable
private fun TicketSummary(t: TicketWidgetData, now: Instant, compact: Boolean) {
    val c = GlanceTheme.colors
    val countdown = TicketLogic.relativeLabel(t.startsAt, t.endsAt, t.timezone, now)
    val live = countdown == "Happening now"
    Column(GlanceModifier.fillMaxSize().padding(vertical = 12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_widget_ticket, c.primary, 16.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(if (compact) "My ticket" else "My ticket${t.city?.let { " · $it" } ?: ""}", style = textStyle(12.sp, c.onSurfaceVariant, FontWeight.Medium),
                maxLines = 1, modifier = GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(t.eventName, style = textStyle(if (compact) 16.sp else 18.sp, c.onSurface, FontWeight.Bold), maxLines = 2)
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                countdown,
                style = textStyle(if (compact) 13.sp else 14.sp, if (live) c.onTertiaryContainer else c.onPrimaryContainer, FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.cornerRadiusCompat(12.dp).background(if (live) c.tertiaryContainer else c.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            if (!compact) {
                Spacer(GlanceModifier.defaultWeight())
                val status = if (t.confirmed && !t.checkedIn) t.shortCode ?: t.statusLabel else t.statusLabel
                Text(status, style = textStyle(12.sp, if (t.confirmed) c.onSurfaceVariant else c.error, FontWeight.Medium), maxLines = 1)
            }
        }
        if (compact && !t.confirmed) {
            Spacer(GlanceModifier.height(4.dp))
            Text(t.statusLabel, style = textStyle(11.sp, c.error, FontWeight.Medium), maxLines = 1)
        }
    }
}
