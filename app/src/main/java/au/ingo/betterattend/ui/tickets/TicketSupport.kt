package au.ingo.betterattend.ui.tickets

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// ---------- QR ----------

/**
 * Encodes [payload] as a QR code with a 4-module quiet zone, one pixel per module. Scaled up
 * with nearest-neighbour filtering it stays razor sharp at any size.
 */
fun qrBitmap(payload: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Always black on white (scanners need contrast), whatever the theme. */
@Composable
fun QrCode(payload: String, modifier: Modifier = Modifier, description: String = "Check-in QR code") {
    val bitmap = remember(payload) { runCatching { qrBitmap(payload) }.getOrNull() }
    Box(modifier.aspectRatio(1f).background(Color.White).semantics { contentDescription = description }) {
        if (bitmap != null) {
            Image(
                bitmap, contentDescription = null, contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------- Brightness ----------

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * While [enabled] and the screen is resumed, pushes the window to full brightness and keeps the
 * screen on so the QR scans first time at a dim door. Restores the previous values on pause,
 * when disabled, and when leaving the screen.
 */
@Composable
fun BrightScreenEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(enabled, activity, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose {}
        val window = activity.window
        var applied = false
        var previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        var hadKeepOn = false
        fun apply() {
            if (applied) return
            val attrs = window.attributes
            previousBrightness = attrs.screenBrightness
            hadKeepOn = attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            window.attributes = attrs
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applied = true
        }
        fun restore() {
            if (!applied) return
            val attrs = window.attributes
            attrs.screenBrightness = previousBrightness
            window.attributes = attrs
            if (!hadKeepOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applied = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply()
                Lifecycle.Event.ON_PAUSE -> restore()
                else -> Unit
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) apply()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            restore()
        }
    }
}

// ---------- Links ----------

/** Opens a web page in a Custom Tab (falls back to any browser). Returns false if nothing can. */
fun Context.openWeb(url: String): Boolean = try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
    true
} catch (_: ActivityNotFoundException) {
    openUri(url)
}

/** Fires an ACTION_VIEW for tel:, geo:, https: etc. Returns false if no app can handle it. */
fun Context.openUri(uri: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

// ---------- Shape ----------

/**
 * A ticket stub: rounded rectangle with semicircular notches cut into both sides at
 * [notchFromTop] (or [notchFraction] of the height when [notchFromTop] is null).
 */
data class TicketShape(
    private val corner: Dp = 28.dp,
    private val notchRadius: Dp = 12.dp,
    private val notchFromTop: Dp? = null,
    private val notchFraction: Float = 0.5f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }
        val r = with(density) { notchRadius.toPx() }
        val y = notchFromTop?.let { with(density) { it.toPx() } }?.coerceIn(r + c, size.height - r - c) ?: (size.height * notchFraction)
        val base = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, c, c))
        }
        val notches = Path().apply {
            addOval(Rect(-r, y - r, r, y + r))
            addOval(Rect(size.width - r, y - r, size.width + r, y + r))
        }
        return Outline.Generic(Path().apply { op(base, notches, PathOperation.Difference) })
    }
}
