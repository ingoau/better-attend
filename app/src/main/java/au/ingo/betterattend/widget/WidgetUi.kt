package au.ingo.betterattend.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import au.ingo.betterattend.MainActivity
import au.ingo.betterattend.R
import au.ingo.betterattend.ui.theme.brandColorScheme

/** Brand colours for launchers without dynamic colour (Android < 12) and for deterministic tests. */
val BrandGlanceColors: ColorProviders by lazy { ColorProviders(light = brandColorScheme(false), dark = brandColorScheme(true)) }

/** Material You on Android 12+ (matches the wallpaper like system widgets), Hack Club red otherwise. */
@Composable
fun AttendGlanceTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    GlanceTheme(colors = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else BrandGlanceColors, content = content)
}

/** Opens the app on a tab ("home", "scan", "travel", "tickets"), optionally straight into a ticket. */
fun openApp(context: Context, tab: String, ticketId: String? = null): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
            .apply { if (ticketId != null) putExtra(MainActivity.EXTRA_OPEN_TICKET, ticketId) }
    )

val refreshAction: Action get() = actionRunCallback<RefreshWidgetsAction>()

/** Widget container: themed background with the system corner radius, whole surface tappable. */
@Composable
fun WidgetFrame(onClick: Action, padding: Dp = 14.dp, background: ColorProvider = GlanceTheme.colors.widgetBackground, content: @Composable () -> Unit) {
    Scaffold(
        modifier = GlanceModifier.clickable(onClick),
        backgroundColor = background,
        horizontalPadding = padding,
    ) {
        Box(GlanceModifier.fillMaxSize()) { content() }
    }
}

@Composable
fun Glyph(@DrawableRes res: Int, tint: ColorProvider, size: Dp = 18.dp, description: String? = null) {
    Image(ImageProvider(res), contentDescription = description, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(size))
}

@Composable
fun RefreshButton() {
    CircleIconButton(
        imageProvider = ImageProvider(R.drawable.ic_widget_refresh),
        contentDescription = "Refresh",
        onClick = refreshAction,
        backgroundColor = null,
        contentColor = GlanceTheme.colors.onSurfaceVariant,
        modifier = GlanceModifier.size(36.dp),
    )
}

fun textStyle(
    size: TextUnit,
    color: ColorProvider,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
) = TextStyle(fontSize = size, color = color, fontWeight = weight, textAlign = align)

/** Centered icon + message, used for "Sign in", "No event" and similar states. */
@Composable
fun WidgetMessage(@DrawableRes icon: Int, title: String, body: String? = null, compact: Boolean = false) {
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Box(
            GlanceModifier.size(if (compact) 36.dp else 44.dp).cornerRadiusCompat(22.dp).background(GlanceTheme.colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Glyph(icon, GlanceTheme.colors.onPrimaryContainer, if (compact) 20.dp else 24.dp) }
        Spacer(GlanceModifier.height(8.dp))
        Text(title, style = textStyle(if (compact) 13.sp else 15.sp, GlanceTheme.colors.onSurface, FontWeight.Medium, TextAlign.Center), maxLines = 2)
        if (body != null && !compact) {
            Text(body, style = textStyle(12.sp, GlanceTheme.colors.onSurfaceVariant, align = TextAlign.Center), maxLines = 2)
        }
    }
}

@Composable
fun SignedOutMessage(compact: Boolean) =
    WidgetMessage(R.drawable.ic_widget_login, "Sign in to Attend", "Tap to open the app", compact)

/** Rounded corners where supported (Android 12+); square elsewhere. */
fun GlanceModifier.cornerRadiusCompat(radius: Dp): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(radius) else this
