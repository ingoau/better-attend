package au.ingo.betterattend.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.ingo.betterattend.ui.components.rememberHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The tab screens live side by side in a pager: swipe between them or tap the bar.
 *
 * Every page stays composed (instant swipes, no reload flashes), but each gets its own lifecycle:
 * only the settled, visible page is RESUMED; the others are STARTED. Anything that should only run
 * while a tab is on screen (camera, NFC, polling) keys off RESUMED and so behaves like a real screen.
 */
@Composable
fun MainTabs(
    tabs: List<Tab>,
    /** Pending "go to this tab" request (from other screens, widgets, shortcuts); consumed here. */
    tabRequests: MutableStateFlow<Tab?>,
    content: @Composable (Tab) -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { tabs.size }
    val holder = rememberSaveableStateHolder()
    var programmatic by remember { mutableStateOf(false) }
    val currentTab = tabs.getOrNull(pager.targetPage) ?: tabs.first()

    fun go(tab: Tab) {
        val target = tabs.indexOf(tab).takeIf { it >= 0 } ?: return
        if (target == pager.currentPage && !pager.isScrollInProgress) return
        scope.launch {
            programmatic = true
            try { pager.animateScrollToPage(target) } finally { programmatic = false }
        }
    }

    // Keep the same tab selected when the tab list changes (e.g. switching to an event without travel).
    var lastTab by remember { mutableStateOf(currentTab) }
    LaunchedEffect(pager.settledPage, tabs) { tabs.getOrNull(pager.settledPage)?.let { lastTab = it } }
    LaunchedEffect(tabs) {
        val index = tabs.indexOf(lastTab).takeIf { it >= 0 } ?: 0
        if (pager.currentPage != index) pager.scrollToPage(index)
    }

    val request by tabRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request, tabs) {
        val t = request ?: return@LaunchedEffect
        tabRequests.value = null
        go(t)
    }

    // A light tick whenever a swipe crosses into the next tab.
    LaunchedEffect(pager) {
        snapshotFlow { pager.targetPage }.distinctUntilChanged().drop(1).collect {
            if (!programmatic && pager.isScrollInProgress) haptics.tick()
        }
    }

    // Back from any other tab returns to the first one, with a predictive "shrink" preview.
    val backProgress = remember { Animatable(0f) }
    var swipeFromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler(enabled = tabs.size > 1 && pager.currentPage != 0) { events ->
        try {
            events.collect { e ->
                swipeFromLeft = e.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT
                backProgress.snapTo(e.progress)
            }
            haptics.gestureEnd()
            scope.launch { backProgress.animateTo(0f) }
            programmatic = true
            try { pager.animateScrollToPage(0) } finally { programmatic = false }
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f) }
            throw e
        }
    }

    val pagerView: @Composable (Modifier) -> Unit = { modifier ->
        val p = backProgress.value
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = tabs.size,
            key = { tabs[it].name },
            modifier = modifier
                .graphicsLayer {
                    val scale = 1f - 0.08f * p
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin(if (swipeFromLeft) 1f else 0f, 0.5f)
                }
                .clip(RoundedCornerShape((32 * p).dp)),
        ) { page ->
            val tab = tabs[page]
            PageLifecycle(active = page == pager.settledPage) {
                holder.SaveableStateProvider(tab.name) { content(tab) }
            }
        }
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    if (tabs.size <= 1) {
        pagerView(Modifier.fillMaxSize())
    } else if (wide) {
        Row(Modifier.fillMaxSize()) {
            WideNavigationRail {
                tabs.forEach { tab ->
                    WideNavigationRailItem(
                        railExpanded = false,
                        selected = tab == currentTab,
                        onClick = { if (tab != currentTab) haptics.tick(); go(tab) },
                        icon = { Icon(if (tab == currentTab) tab.selectedIcon else tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
            pagerView(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { pagerView(Modifier.fillMaxSize()) }
            ShortNavigationBar {
                tabs.forEach { tab ->
                    ShortNavigationBarItem(
                        selected = tab == currentTab,
                        onClick = { if (tab != currentTab) haptics.tick(); go(tab) },
                        icon = { Icon(if (tab == currentTab) tab.selectedIcon else tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }
}

/** A child lifecycle capped at STARTED unless [active], and never above its parent's state. */
private class PageLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

@Composable
private fun PageLifecycle(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember { PageLifecycleOwner() }
    DisposableEffect(parent, active) {
        fun sync() {
            val cap = if (active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
            val parentState = parent.lifecycle.currentState
            val target = if (parentState < cap) parentState else cap
            // Can't move an INITIALIZED lifecycle straight to DESTROYED.
            if (target == Lifecycle.State.DESTROYED && owner.registry.currentState == Lifecycle.State.INITIALIZED) return
            owner.registry.currentState = target
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        parent.lifecycle.addObserver(observer)
        sync()
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (owner.registry.currentState.isAtLeast(Lifecycle.State.CREATED)) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

