package au.ingo.betterattend.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Runs [block] immediately and then every [intervalMillis] while the current screen is RESUMED
 * (i.e. actually visible: nav destinations drop below RESUMED when covered or the app is backgrounded).
 * Restarts whenever [key] changes. Polling stops as soon as the screen isn't visible.
 */
@Composable
fun PollWhileVisible(key: Any?, intervalMillis: Long, runImmediately: Boolean = true, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(block)
    LaunchedEffect(key, owner, intervalMillis) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!runImmediately) delay(intervalMillis)
            while (true) {
                current()
                delay(intervalMillis)
            }
        }
    }
}
