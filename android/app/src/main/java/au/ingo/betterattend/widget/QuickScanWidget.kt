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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import au.ingo.betterattend.R

/** 1x1 / 2x1 launcher for the scanner: one tap and the camera is up. */
class QuickScanWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(WidgetSizes.Tiny, WidgetSizes.QuickWide))
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetSizes.QuickWide))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetUpdater.current(context)
        provideContent {
            val snap by WidgetUpdater.snapshot.collectAsState()
            AttendGlanceTheme { QuickScanContent(snap ?: initial) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { AttendGlanceTheme { QuickScanContent(WidgetSamples.snapshot) } }
    }
}

class QuickScanWidgetReceiver : AttendWidgetReceiver() {
    override val glanceAppWidget = QuickScanWidget()
}

@Composable
fun QuickScanContent(snap: WidgetSnapshot) {
    val context = LocalContext.current
    val c = GlanceTheme.colors
    val wide = LocalSize.current.width >= WidgetSizes.QuickWide.width
    val event = snap.organizer?.eventName?.takeIf { snap.signedIn }
    WidgetFrame(onClick = openApp(context, "scan"), padding = 8.dp, background = c.primaryContainer) {
        Box(
            GlanceModifier.fillMaxSize().semantics { contentDescription = if (event != null) "Scan tickets for $event" else "Open the scanner" },
            contentAlignment = Alignment.Center,
        ) {
            if (!wide) {
                Glyph(R.drawable.ic_widget_qr_scan, c.onPrimaryContainer, 30.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(40.dp).cornerRadiusCompat(20.dp).background(c.primary), contentAlignment = Alignment.Center) {
                        Glyph(R.drawable.ic_widget_qr_scan, c.onPrimary, 22.dp)
                    }
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text("Scan", style = textStyle(16.sp, c.onPrimaryContainer, FontWeight.Bold), maxLines = 1)
                        if (event != null) Text(event, style = textStyle(11.sp, c.onPrimaryContainer), maxLines = 1)
                    }
                }
            }
        }
    }
}
