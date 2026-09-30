package au.ingo.betterattend.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import au.ingo.betterattend.widget.ArrivalsContent
import au.ingo.betterattend.widget.AttendGlanceTheme
import au.ingo.betterattend.widget.CheckInContent
import au.ingo.betterattend.widget.QuickScanContent
import au.ingo.betterattend.widget.TicketContent
import au.ingo.betterattend.widget.WidgetSamples
import au.ingo.betterattend.widget.WidgetSizes
import au.ingo.betterattend.widget.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the real Glance widgets (compose -> RemoteViews -> inflated views) onto a
 * wallpaper-coloured sheet, light and dark. Output: build/outputs/roborazzi/widgets_*.png
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetScreenshots {
    private val snap = WidgetSamples.snapshot
    private val now = WidgetSamples.now
    private val signedOut = WidgetSnapshot.SignedOut

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun render(size: DpSize, content: @Composable () -> Unit): Bitmap {
        val ctx = context()
        val rv = runBlocking {
            GlanceRemoteViews().compose(ctx, size, appWidgetOptions = Bundle()) { AttendGlanceTheme(dynamic = false) { content() } }.remoteViews
        }
        val parent = FrameLayout(ctx)
        val view: View = rv.apply(ctx, parent)
        val d = ctx.resources.displayMetrics.density
        val w = (size.width.value * d).roundToInt()
        val h = (size.height.value * d).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    /** Lays out rows of widgets on a flat "wallpaper" and writes a PNG. */
    private fun sheet(name: String, dark: Boolean, rows: List<List<Pair<DpSize, @Composable () -> Unit>>>) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        val d = context().resources.displayMetrics.density
        val gap = (16 * d).roundToInt()
        val rendered = rows.map { row -> row.map { (size, c) -> render(size, c) } }
        val width = rendered.maxOf { r -> r.sumOf { it.width } + gap * (r.size + 1) }
        val height = rendered.sumOf { r -> r.maxOf { it.height } } + gap * (rendered.size + 1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (dark) 0xFF2B2D35.toInt() else 0xFFB9C6D6.toInt())
        var y = gap
        rendered.forEach { r ->
            var x = gap
            r.forEach { bmp -> canvas.drawBitmap(bmp, x.toFloat(), y.toFloat(), Paint()); x += bmp.width + gap }
            y += r.maxOf { it.height } + gap
        }
        val file = File("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun organizerSheet(dark: Boolean) = sheet("widgets_organizer", dark, listOf(
        listOf(WidgetSizes.Small to { CheckInContent(snap, now) }, WidgetSizes.Small to { ArrivalsContent(snap, now) }),
        listOf(WidgetSizes.Wide to { CheckInContent(snap, now) }),
        listOf(WidgetSizes.Large to { CheckInContent(snap, now) }),
        listOf(WidgetSizes.Wide to { ArrivalsContent(snap, now) }),
    ))

    private fun otherSheet(dark: Boolean) = sheet("widgets_participant", dark, listOf(
        listOf(WidgetSizes.Tiny to { QuickScanContent(snap) }, WidgetSizes.QuickWide to { QuickScanContent(snap) }),
        listOf(WidgetSizes.Small to { TicketContent(snap, now) }, WidgetSizes.Small to { TicketContent(snap.copy(ticket = null), now) }),
        listOf(WidgetSizes.Wide to { TicketContent(snap, now) }),
        listOf(WidgetSizes.Small to { CheckInContent(signedOut, now) }, WidgetSizes.Small to { CheckInContent(snap.copy(organizer = snap.organizer!!.copy(hasCounts = false)), now) }),
    ))

    /** Before check-in starts: leads with the expected count instead of "0 / 120". */
    private fun beforeSheet(dark: Boolean) = run {
        val before = snap.copy(organizer = snap.organizer!!.copy(checkedIn = 0, notArrived = 120, lastHour = 0,
            contexts = snap.organizer!!.contexts.map { it.copy(count = 0) }))
        sheet("widgets_before_checkin", dark, listOf(
            listOf(WidgetSizes.Small to { CheckInContent(before, now) }),
            listOf(WidgetSizes.Wide to { CheckInContent(before, now) }),
            listOf(WidgetSizes.Large to { CheckInContent(before, now) }),
        ))
    }

    @Test fun beforeCheckInLight() = beforeSheet(false)
    @Test fun beforeCheckInDark() = beforeSheet(true)
    @Test fun organizerLight() = organizerSheet(false)
    @Test fun organizerDark() = organizerSheet(true)
    @Test fun participantLight() = otherSheet(false)
    @Test fun participantDark() = otherSheet(true)
}
