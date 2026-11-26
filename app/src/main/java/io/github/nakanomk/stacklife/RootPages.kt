package io.github.nakanomk.stacklife

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.LocalWallpaperRecording
import io.github.nakanomk.stacklife.appearance.glassUnavailableReason
import io.github.nakanomk.stacklife.appearance.rememberNavigationPulse
import io.github.nakanomk.stacklife.assistant.AssistantPage
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.workspace.Page
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** A swipe follows the configured order. A tab tap is a direct trip between two destinations,
 * not a scroll through everything between them. Both use the same keyed AndroidX pager. */
@Composable
internal fun RootPages(order: List<Destination>, active: Destination, reveal: Long, onSelect: (Destination) -> Unit,
    assistantPage: AssistantPage, onReselectToday: () -> Unit = {}, content: @Composable (Destination, Boolean) -> Unit) {
    // The saved Destination, never a temporary slot index, restores the root after recreation.
    val pager = remember(order, reveal) { PagerState(order.indexOf(active)) { order.size } }
    var slots by remember(pager) { mutableStateOf(order) }
    var request by remember(pager) { mutableStateOf<Destination?>(null) }
    val motion = LocalMotionEnabled.current
    val appearance = LocalAppearance.current
    val capable = glassUnavailableReason(appearance) == null
    val keepVisited = motion && capable
    // This is a working set of keys, not another owner of page content or saveable state.
    // Dropping the capability also drops the history; enabling it never resurrects a tab.
    var recent by remember(pager, keepVisited) { mutableStateOf(listOf(active)) }
    val publish by rememberUpdatedState<(Destination) -> Unit> { page ->
        if (keepVisited && recent.first() != page) {
            recent = buildList(3) {
                add(page)
                for (previous in recent) if (previous != page && size < 3) add(previous)
            }
        }
        onSelect(page)
    }
    // At most five cheap pager slots. Keep their keys stable across a far tap;
    // only the three selected business trees below can occupy those slots.
    val beyond = if (keepVisited) order.lastIndex else 0
    // Keep the Pager item-provider lambda stable as the dock scope and selection change.
    val activePage by rememberUpdatedState(active)
    val showPage by rememberUpdatedState(content)
    val pulse by rememberUpdatedState(rememberNavigationPulse())
    var swipe by remember(pager) { mutableStateOf<DragInteraction.Start?>(null) }
    LaunchedEffect(pager) {
        var origin = activePage
        pager.interactionSource.interactions.collectLatest { input ->
            when (input) {
                is DragInteraction.Start -> { swipe = input; origin = activePage }
                is DragInteraction.Cancel -> if (swipe === input.start) swipe = null
                is DragInteraction.Stop -> if (swipe === input.start) {
                    withFrameNanos { }
                    snapshotFlow { pager.isScrollInProgress }.first { !it }
                    // The direct-trip pager can reorder slots on landing; compare the measured
                    // destination key, not a slot number. An intervening dock click owns its pulse.
                    val key = pager.layoutInfo.visiblePagesInfo.firstOrNull { it.index == pager.settledPage }?.key
                    if (swipe === input.start && key != null && key != origin.key) pulse()
                    swipe = null
                }
            }
        }
    }
    // A screen-anchored photo changes the samples under a sliding pane every frame.
    // In that case a full-page texture would need repainting instead of being reused.
    val cacheMovingGlass = appearance.glass && capable && LocalWallpaperRecording.current == null

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
    val bar = rememberDestinationCapsule(active.key, position, onReselect = {
        if (it == Destination.TODAY.key) onReselectToday()
    }) { key ->
        val end = slots.indexOfFirst { it.key == key }
        if (end >= 0) {
            swipe = null
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
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = beyond, key = { slots[it].key }) { index ->
                        // requestScrollToPage and the key lookup must see the new order in
                        // the same measure. An existing keyed item, however, is still its
                        // original tab even if that index now belongs to a different key.
                        val page = remember { slots[index] }
                        val selected = page == activePage
                        val inViewport = remember(pager, page, index) { derivedStateOf {
                            val info = pager.layoutInfo
                            if (info.visiblePagesInfo.isEmpty()) index == pager.currentPage else
                                info.visiblePagesInfo.any { it.key == page.key && it.offset < info.viewportEndOffset &&
                                    it.offset + info.pageSize > info.viewportStartOffset }
                        } }
                        // Beyond-bounds slots and directional prefetch are not visits. Match the
                        // *measured key*, not an index that can denote another tab during a remap.
                        if (selected || inViewport.value || (keepVisited && page in recent)) {
                            // The fixed dock stays outside the moving layer and samples live pixels.
                            val plane = if (cacheMovingGlass) remember(pager, inViewport) {
                                Modifier.graphicsLayer {
                                    // A visited page that just slid offscreen needn't throw
                                    // its raster away only to rebuild it on the next tap.
                                    // The resting visible page scrolls without this pass.
                                    compositingStrategy = if (pager.isScrollInProgress || !inViewport.value)
                                        CompositingStrategy.Offscreen else CompositingStrategy.Auto
                                }
                            } else Modifier
                            PagerPane(selected, selected || inViewport.value, plane, retainedViewport = if (keepVisited) inViewport else null) {
                                showPage(page, selected)
                            }
                        }
                    }
                }
            }
        } }
    }
}
