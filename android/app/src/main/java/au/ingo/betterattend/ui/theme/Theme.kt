package au.ingo.betterattend.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import au.ingo.betterattend.R
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.ingo.betterattend.data.store.ThemeMode
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/** Hack Club brand colours. */
object HackClub {
    val Red = Color(0xFFEC3750)
    val Orange = Color(0xFFFF8C37)
    val Yellow = Color(0xFFF1C40F)
    val Green = Color(0xFF33D6A6)
    val Cyan = Color(0xFF5BC0DE)
    val Blue = Color(0xFF338EDA)
    val Purple = Color(0xFFA633D6)
    val Dark = Color(0xFF17171D)
    val Darker = Color(0xFF121217)
}

/**
 * Semantic status colours that Material's scheme doesn't cover. Each has a strong colour
 * for icons/fills and a container + on-container pair for tinted cards and pills.
 */
@Immutable
data class StatusColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val onWarning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val info: Color, val onInfo: Color, val infoContainer: Color, val onInfoContainer: Color,
    val danger: Color, val onDanger: Color, val dangerContainer: Color, val onDangerContainer: Color,
)

private val LightStatus = StatusColors(
    success = Color(0xFF0E8A63), onSuccess = Color.White, successContainer = Color(0xFFC9F4E4), onSuccessContainer = Color(0xFF00382A),
    warning = Color(0xFFB85A00), onWarning = Color.White, warningContainer = Color(0xFFFFDCC2), onWarningContainer = Color(0xFF3A1C00),
    info = Color(0xFF1D6FB8), onInfo = Color.White, infoContainer = Color(0xFFD3E4FF), onInfoContainer = Color(0xFF001C38),
    danger = Color(0xFFC8102E), onDanger = Color.White, dangerContainer = Color(0xFFFFDAD9), onDangerContainer = Color(0xFF410008),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF5BE0B5), onSuccess = Color(0xFF00382A), successContainer = Color(0xFF00513D), onSuccessContainer = Color(0xFFB4F5DC),
    warning = Color(0xFFFFB783), onWarning = Color(0xFF4F2500), warningContainer = Color(0xFF703700), onWarningContainer = Color(0xFFFFDCC2),
    info = Color(0xFF9FCAFF), onInfo = Color(0xFF003259), infoContainer = Color(0xFF00497E), onInfoContainer = Color(0xFFD3E4FF),
    danger = Color(0xFFFFB3B3), onDanger = Color(0xFF680013), dangerContainer = Color(0xFF93001F), onDangerContainer = Color(0xFFFFDAD9),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Shorthand: `MaterialTheme.status.success`. */
val MaterialTheme.status: StatusColors
    @Composable get() = LocalStatusColors.current

private fun DynamicScheme.toColorScheme(): ColorScheme {
    fun c(argb: Int) = Color(argb)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        inversePrimary = c(inversePrimary),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(surfaceTint),
        inverseSurface = c(inverseSurface), inverseOnSurface = c(inverseOnSurface),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        outline = c(outline), outlineVariant = c(outlineVariant), scrim = c(scrim),
        surfaceBright = c(surfaceBright), surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest), surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
        primaryFixed = c(primaryFixed), primaryFixedDim = c(primaryFixedDim),
        onPrimaryFixed = c(onPrimaryFixed), onPrimaryFixedVariant = c(onPrimaryFixedVariant),
        secondaryFixed = c(secondaryFixed), secondaryFixedDim = c(secondaryFixedDim),
        onSecondaryFixed = c(onSecondaryFixed), onSecondaryFixedVariant = c(onSecondaryFixedVariant),
        tertiaryFixed = c(tertiaryFixed), tertiaryFixedDim = c(tertiaryFixedDim),
        onTertiaryFixed = c(onTertiaryFixed), onTertiaryFixedVariant = c(onTertiaryFixedVariant),
    )
}

/** Brand scheme generated from Hack Club red with the 2025 (Expressive) colour spec. */
fun brandColorScheme(dark: Boolean): ColorScheme {
    val cs = seededColorScheme(HackClub.Red, dark)
    // Keep the exact brand red as the light-mode primary so the app reads as Hack Club.
    return if (dark) cs else cs.copy(primary = HackClub.Red, onPrimary = Color.White)
}

/** A vibrant Expressive scheme from any [seed] colour, with calmer Tonal Spot neutrals. */
fun seededColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val scheme = SchemeVibrant(Hct.fromInt(seed.toArgb()), dark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
    // Calmer neutrals: surfaces come from a Tonal Spot scheme so dark mode isn't maroon.
    val neutral = SchemeTonalSpot(Hct.fromInt(seed.toArgb()), dark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE).toColorScheme()
    return scheme.toColorScheme().copy(
        background = neutral.background, onBackground = neutral.onBackground,
        surface = neutral.surface, onSurface = neutral.onSurface,
        surfaceVariant = neutral.surfaceVariant, onSurfaceVariant = neutral.onSurfaceVariant,
        surfaceBright = neutral.surfaceBright, surfaceDim = neutral.surfaceDim,
        surfaceContainer = neutral.surfaceContainer, surfaceContainerHigh = neutral.surfaceContainerHigh,
        surfaceContainerHighest = neutral.surfaceContainerHighest, surfaceContainerLow = neutral.surfaceContainerLow,
        surfaceContainerLowest = neutral.surfaceContainerLowest, outline = neutral.outline, outlineVariant = neutral.outlineVariant,
        inverseSurface = neutral.inverseSurface, inverseOnSurface = neutral.inverseOnSurface,
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Roboto Flex, a variable font (weight 100–1000, width 25–151), bundled as a Latin subset. Each weight
 * is an instance of the one file, every 100 units: few enough to stay in the typeface cache, while
 * animated weights (see [flexWeight]) still step through the in-betweens.
 */
private fun flexFamily(width: Float) = FontFamily(
    (100..1000 step 100).map { w ->
        Font(
            R.font.roboto_flex,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.width(width)),
        )
    },
)

val RobotoFlex = flexFamily(100f)
/** A slightly wider cut for emphasized display and headline type: big numbers read bolder and rounder. */
val RobotoFlexWide = flexFamily(116f)

/** Snaps an animated weight to the 100-unit steps [RobotoFlex] has instances for. */
fun flexWeight(weight: Float): FontWeight = FontWeight(((weight / 100f).roundToInt() * 100).coerceIn(100, 1000))

private val AppTypography: Typography = Typography().let { base ->
    // Every style on Roboto Flex; emphasized display/headline styles on the wide cut.
    fun TextStyle.flex() = copy(fontFamily = RobotoFlex)
    val t = Typography(
        displayLarge = base.displayLarge.flex(), displayMedium = base.displayMedium.flex(), displaySmall = base.displaySmall.flex(),
        headlineLarge = base.headlineLarge.flex(), headlineMedium = base.headlineMedium.flex(), headlineSmall = base.headlineSmall.flex(),
        titleLarge = base.titleLarge.flex(), titleMedium = base.titleMedium.flex(), titleSmall = base.titleSmall.flex(),
        bodyLarge = base.bodyLarge.flex(), bodyMedium = base.bodyMedium.flex(), bodySmall = base.bodySmall.flex(),
        labelLarge = base.labelLarge.flex(), labelMedium = base.labelMedium.flex(), labelSmall = base.labelSmall.flex(),
    )
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        // Expressive emphasized styles for hero numbers and key headings: heavier and tighter than
        // the regular ones above (the library defaults are lighter than this app's regular styles).
        displayLargeEmphasized = t.displayLarge.copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight.Black, letterSpacing = (-2).sp),
        displayMediumEmphasized = t.displayMedium.copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
        displaySmallEmphasized = t.displaySmall.copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineLargeEmphasized = t.headlineLarge.copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMediumEmphasized = t.headlineMedium.copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineSmallEmphasized = t.headlineSmall.copy(fontFamily = RobotoFlexWide, fontWeight = FontWeight.ExtraBold),
        titleLargeEmphasized = t.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMediumEmphasized = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmallEmphasized = t.titleSmall.copy(fontWeight = FontWeight.Bold),
        labelLargeEmphasized = t.labelLarge.copy(fontWeight = FontWeight.Bold),
        labelMediumEmphasized = t.labelMedium.copy(fontWeight = FontWeight.Bold),
        bodyLargeEmphasized = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMediumEmphasized = t.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        bodySmallEmphasized = t.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmallEmphasized = t.labelSmall.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
fun AttendTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val colorScheme = remember(dark, dynamicColor) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else brandColorScheme(dark)
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
