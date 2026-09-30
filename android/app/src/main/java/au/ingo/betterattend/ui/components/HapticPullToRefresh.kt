package au.ingo.betterattend.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * [PullToRefreshBox] that ticks when the pull crosses the refresh threshold (and again if the user
 * backs off below it), so people can feel when letting go will refresh.
 */
@Composable
fun HapticPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val haptics = rememberHaptics()
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }.distinctUntilChanged().drop(1).collect { armed ->
            if (armed) haptics.threshold() else haptics.frequentTick()
        }
    }
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier, state = state, content = content)
}
