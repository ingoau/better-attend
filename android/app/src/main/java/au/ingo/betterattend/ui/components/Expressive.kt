package au.ingo.betterattend.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import au.ingo.betterattend.ui.theme.flexWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.asComposePath

/**
 * Material 3 Expressive segmented groups: related rows sit in one tinted block split by hairline
 * gaps, with big outer corners and small inner ones so the group reads as a unit.
 */
object Segmented {
    val Outer = 24.dp
    val Inner = 6.dp
    val Gap = 2.dp
    /** Every corner springs to this while pressed, so the touched row briefly stands apart. */
    val Pressed = 20.dp

    fun top(index: Int): Dp = if (index == 0) Outer else Inner
    fun bottom(index: Int, count: Int): Dp = if (index == count - 1) Outer else Inner
}

/** A rounded-rect whose top and bottom corners spring to [pressed] while [interactionSource] is pressed. */
@Composable
fun rememberPressMorphShape(interactionSource: InteractionSource, top: Dp, bottom: Dp = top, pressed: Dp): Shape {
    val isPressed by interactionSource.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val t by animateDpAsState(if (isPressed) pressed else top, spec, label = "cornerTop")
    val b by animateDpAsState(if (isPressed) pressed else bottom, spec, label = "cornerBottom")
    // The spatial spring overshoots; corners can't go negative.
    val tc = t.coerceAtLeast(0.dp)
    val bc = b.coerceAtLeast(0.dp)
    return RoundedCornerShape(topStart = tc, topEnd = tc, bottomStart = bc, bottomEnd = bc)
}

/**
 * A container that morphs its corners when pressed (rounder → squarer for cards, the reverse for
 * segmented rows), the Expressive replacement for a plain ripple on a static shape. Supports an
 * optional long-press. Non-interactive when [onClick] is null.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpressiveSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    top: Dp = 28.dp,
    bottom: Dp = top,
    pressed: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(color),
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val shape = rememberPressMorphShape(source, top, bottom, pressed)
    val interactive = onClick != null || onLongClick != null
    val m = if (!interactive) modifier else modifier
        .minimumInteractiveComponentSize()
        .clip(shape)
        .combinedClickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick ?: {},
        )
    // Disabled rows dim their content so they don't read as tappable.
    val fg = if (interactive && !enabled) contentColor.copy(alpha = 0.38f) else contentColor
    Surface(shape = shape, color = color, contentColor = fg, modifier = m, content = content)
}

/** One row of a [Segmented] group. */
@Composable
fun SegmentedItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(color),
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    ExpressiveSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(bottom = if (index == count - 1) 0.dp else Segmented.Gap),
        top = Segmented.top(index),
        bottom = Segmented.bottom(index, count),
        pressed = Segmented.Pressed,
        color = color,
        contentColor = contentColor,
        enabled = enabled,
        role = role,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        content = content,
    )
}

/** Draws [morph] at [progress], scaled from its unit bounds to the component's size. */
@Immutable
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path: Path = morph.toPath(progress).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }

    override fun equals(other: Any?) = other is MorphShape && other.morph === morph && other.progress == progress
    override fun hashCode() = 31 * morph.hashCode() + progress.hashCode()
}

/**
 * A shape that springs between [from] and [to] as [toggled] changes, e.g. an avatar that blooms
 * from a circle into a cookie when someone checks in.
 */
@Composable
fun rememberMorphShape(toggled: Boolean, from: RoundedPolygon = MaterialShapes.Circle, to: RoundedPolygon = MaterialShapes.Cookie9Sided): Shape {
    val morph = remember(from, to) { Morph(from.normalized(), to.normalized()) }
    val progress by animateFloatAsState(if (toggled) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "morph")
    return MorphShape(morph, progress.coerceIn(0f, 1f))
}

/**
 * A font weight that glides to [target] (Roboto Flex is variable, so labels can thicken on press or
 * selection instead of jumping between two cuts).
 */
@Composable
fun animatedFlexWeight(target: FontWeight): FontWeight {
    val w by animateFloatAsState(target.weight.toFloat(), MaterialTheme.motionScheme.fastEffectsSpec(), label = "weight")
    return flexWeight(w)
}

/** [pressed] while [interactionSource] is pressed, else [rest], animated. */
@Composable
fun pressFlexWeight(interactionSource: InteractionSource, rest: FontWeight, pressed: FontWeight): FontWeight {
    val isPressed by interactionSource.collectIsPressedAsState()
    return animatedFlexWeight(if (isPressed) pressed else rest)
}

/** True when the system's animator duration scale is 0 ("Remove animations" in accessibility settings). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * Lets the first screenful of a list rise into place one item after another when it first appears.
 * Items composed in the first moments after the first one claim increasing slots; anything composed
 * later (scrolled into view, a filter change) just appears.
 */
class EntranceStagger {
    private var firstClaimAt = 0L
    private var next = 0

    /** The item's slot in the cascade, or null once the first load has played. */
    internal fun claim(): Int? {
        val now = System.nanoTime()
        if (next == 0) firstClaimAt = now
        if (now - firstClaimAt > WINDOW_NANOS || next >= MAX_ITEMS) return null
        return next++
    }

    private companion object {
        const val WINDOW_NANOS = 400_000_000L
        const val MAX_ITEMS = 10
    }
}

val LocalEntranceStagger = staticCompositionLocalOf<EntranceStagger?> { null }

/** Provides a fresh [EntranceStagger] to [content]. */
@Composable
fun ProvideEntranceStagger(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEntranceStagger provides remember { EntranceStagger() }, content = content)
}

/** Fades and rises this item into place as part of the [LocalEntranceStagger] cascade, if any. */
@Composable
fun Modifier.staggeredEntrance(): Modifier {
    val stagger = LocalEntranceStagger.current ?: return this
    val reduced = rememberReducedMotion()
    val slot = remember { if (reduced) null else stagger.claim() } ?: return this
    val progress = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(progress) {
        delay(slot * 45L)
        progress.animateTo(1f, spec)
    }
    val rise = with(LocalDensity.current) { 28.dp.toPx() }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * rise
    }
}

/**
 * A slow, endless turn for decorative shapes, in degrees. Read it in a draw-phase lambda (e.g.
 * `graphicsLayer { rotationZ = spin() }`) so it never recomposes. Stays still under reduced motion.
 */
@Composable
fun rememberSlowSpin(periodMillis: Int = 60_000, clockwise: Boolean = true): () -> Float {
    if (rememberReducedMotion()) return { 0f }
    val turn = rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = if (clockwise) 360f else -360f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing)),
        label = "spinAngle",
    )
    return { turn.value }
}
