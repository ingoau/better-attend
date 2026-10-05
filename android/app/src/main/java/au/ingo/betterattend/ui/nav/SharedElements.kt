package au.ingo.betterattend.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** The app-wide shared-transition scope (null in previews and screenshot tests, where there's no NavHost). */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The enclosing NavHost destination's animation scope. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element key for a participant's avatar, the same in the People list and their detail header. */
fun participantAvatarKey(participantEventId: String) = "avatar_$participantEventId"

/**
 * Marks this as a shared element that flies between screens (e.g. the People-list avatar growing into
 * the detail header), clipped to [clip] while in flight. A no-op outside a NavHost.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElement(key: String, clip: Shape): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        // sharedBounds + ScaleToBounds: both ends crossfade while scaled to the flying bounds, so a 44dp
        // avatar and a 136dp one (with their different initials sizes) morph cleanly into each other.
        this@sharedElement.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animated,
            enter = fadeIn(),
            exit = fadeOut(),
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Fit, Alignment.Center),
            clipInOverlayDuringTransition = OverlayClip(clip),
        )
    }
}
