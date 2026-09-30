package au.ingo.betterattend.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * Screen-to-screen motion. Forward: the new screen slides in over the old one, which drifts left
 * and dims (the Android 14+ system feel). Back: the reverse — and during a predictive back gesture
 * NavHost scrubs these transitions with the gesture, so the top screen follows your finger and the
 * previous screen is revealed underneath.
 */
object AppMotion {
    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private const val ENTER_MS = 400
    private const val POP_MS = 300

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { it } +
            fadeIn(tween(ENTER_MS / 2, easing = Standard), initialAlpha = 0.6f)
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { -it / 4 } +
            fadeOut(tween(ENTER_MS, easing = Standard), targetAlpha = 0.5f)
    }
    // Linear so that, when scrubbed by a predictive back gesture, the screen tracks the finger 1:1.
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(tween(POP_MS, easing = LinearEasing)) { -it / 4 } +
            fadeIn(tween(POP_MS, easing = LinearEasing), initialAlpha = 0.5f)
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(tween(POP_MS, easing = LinearEasing)) { it } +
            scaleOut(tween(POP_MS, easing = LinearEasing), targetScale = 0.94f)
    }
}
