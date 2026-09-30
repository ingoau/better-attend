package au.ingo.betterattend.ui.nav

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry

/**
 * Screen-to-screen motion, one consistent model everywhere:
 *
 * - Forward: the new screen slides in over the old one as a card; the old one drifts a quarter
 *   width the other way and dims under a scrim. Driven by a critically damped spring, which eases
 *   in (so a slow first frame while the new screen composes isn't visible as a jump) and settles
 *   without overshoot.
 * - Back button: the exact reverse.
 * - Back gesture: the *same* reverse (whichever edge it starts from), scrubbed by the gesture. The
 *   top screen slides right as you swipe and, on release, simply continues that motion to the end;
 *   on cancel it slides back. Linear timing so the screen tracks the finger 1:1.
 *
 * Only transforms (slides) and a drawn scrim are animated — no whole-screen alpha fades, which
 * force expensive offscreen layers and were a source of jank.
 */
object AppMotion {
    private const val POP_MS = 320

    private val slideSpring = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
    private val popTween = tween<IntOffset>(POP_MS, easing = LinearEasing)

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(slideSpring) { it }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(slideSpring) { -it / 4 }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(popTween) { -it / 4 }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(popTween) { it }
    }

    /** The back gesture uses exactly the back-button transition, whichever edge it starts from. */
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = { popEnter() }
    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { popExit() }

    const val SCRIM_ALPHA = 0.32f
    val CardCorner = 28.dp
}

/**
 * Wraps the tab pager: dims it while a screen is on top of it (and un-dims as that screen is
 * swiped away). Drawn as a rect over the content — no layer, so it costs nothing per frame.
 */
@Composable
fun AnimatedContentScope.UnderlayFrame(content: @Composable () -> Unit) {
    val scrim by transition.animateFloat(
        transitionSpec = { tween(AppMotionTiming.SCRIM_MS, easing = LinearEasing) },
        label = "scrim",
    ) { state -> if (state == EnterExitState.Visible) 0f else AppMotion.SCRIM_ALPHA }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (scrim > 0f) drawRect(Color.Black.copy(alpha = scrim))
            },
    ) { content() }
}

/**
 * Wraps screens pushed on top: while moving they read as a card with rounded corners, which
 * square off once the screen is fully in place.
 */
@Composable
fun AnimatedContentScope.CardFrame(content: @Composable () -> Unit) {
    val corner by transition.animateDp(
        transitionSpec = { tween(AppMotionTiming.SCRIM_MS, easing = LinearEasing) },
        label = "corner",
    ) { state -> if (state == EnterExitState.Visible) 0.dp else AppMotion.CardCorner }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val r = corner.toPx()
                if (r > 0f) {
                    shape = RoundedCornerShape(r)
                    clip = true
                } else {
                    clip = false
                }
            },
    ) { content() }
}

private object AppMotionTiming {
    const val SCRIM_MS = 320
}
