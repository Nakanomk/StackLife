package io.github.nakanomk.stacklife

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.workspace.Page
import kotlin.math.abs

/** A swipe follows the configured order. A tab tap is a direct trip between two destinations,
 * not a scroll through everything between them. Both use the same keyed AndroidX pager. */
@Composable
internal fun RootPages(order: List<Destination>, active: Destination, reveal: Long, onSelect: (Destination) -> Unit,
    assistantPage: AssistantPage, content: @Composable (Destination, Boolean) -> Unit) {
    // The saved Destination, never a temporary slot index, restores the root after recreation.
    val pager = remember(order, reveal) { PagerState(order.indexOf(active)) { order.size } }
    var slots by remember(pager) { mutableStateOf(order) }
    var request by remember(pager) { mutableStateOf<Destination?>(null) }
    val publish by rememberUpdatedState(onSelect)
    val motion = LocalMotionEnabled.current

    fun landed(page: Destination) {
        if (slots != order) {
            slots = order
            // Same visible key, new slot. Apply both at the next measure, never paint the old slot's tab.
            pager.requestScrollToPage(order.indexOf(page))
        }
        publish(page)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && request == null) slots.getOrNull(pager.settledPage)?.let(::landed)
        }
    }
    LaunchedEffect(pager, request) {
        val target = request ?: return@LaunchedEffect
        try {
            val end = slots.indexOf(target)
            if (motion) pager.animateScrollToPage(end, animationSpec = tween(320)) else pager.scrollToPage(end)
            if (request == target) landed(target)
        } finally {
            // A finger may take over mid-trip. Keep its actual two pages until that drag settles.
            if (request == target) request = null
        }
    }
    val position = remember(pager) { derivedStateOf {
        val here = pager.currentPage.coerceIn(order.indices)
        val offset = pager.currentPageOffsetFraction
        if (slots == order) here + offset else {
            val neighbor = (here + offset.compareTo(0f)).coerceIn(order.indices)
            val start = order.indexOf(slots[here])
            start + (order.indexOf(slots[neighbor]) - start) * abs(offset)
        }
    } }
    val bar = rememberDestinationCapsule(active.key, position) { key ->
        val end = slots.indexOfFirst { it.key == key }
        if (end >= 0) {
            val target = slots[end]
            val here = pager.currentPage.coerceIn(slots.indices)
            if (motion && abs(end - here) > 1) {
                val next = here + end.compareTo(here)
                slots = slots.toMutableList().also { it[end] = it[next]; it[next] = target }
            }
            request = target
            publish(target)
        }
    }
    CompositionLocalProvider(LocalPlannerGlassAllowed provides true) {
        Page { DockOverlay(bar, assistantPage) { bottom ->
            CompositionLocalProvider(LocalRootBottom provides bottom) {
                // Reset the lazy key map when the order changes, but retain the same source-site
                // identity when Home is recomposed on Back (a new PagerState instance is not a site).
                key(order, reveal) {
                    // Let Pager compose the visible pair. SaveableStateProvider owns tab state;
                    // Back to one tab must not lay out the other four on its first frame.
                    HorizontalPager(pager, Modifier.fillMaxSize(), key = { slots[it].key }) { index ->
                        val page = slots[index]
                        val selected = page == active
                        val inViewport by remember(index) { derivedStateOf { abs(pager.getOffsetDistanceInPages(index)) < 1f } }
                        PagerPane(selected, selected || inViewport) { content(page, selected) }
                    }
                }
            }
        } }
    }
}
