package io.github.nakanomk.stacklife.today

import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.nakanomk.stacklife.PagerPane
import io.github.nakanomk.stacklife.appearance.LocalPlannerGlassAllowed
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The picker, arrows, device clock and restored navigation state can also select a page.
 * Publish a touch selection only once it settles; a cancelled swipe never changes scope. */
@Composable
internal fun CalendarPager(page: Int, count: Int, onSelect: (Int) -> Unit, modifier: Modifier,
    content: @Composable (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = page) { count }
    val select by rememberUpdatedState(onSelect)
    val motion = LocalMotionEnabled.current
    LaunchedEffect(page, count, pager, motion) {
        if (pager.currentPage != page || pager.currentPageOffsetFraction != 0f) {
            coroutineScope {
                launch { if (motion) pager.animateScrollToPage(page, animationSpec = tween(320)) else pager.scrollToPage(page) }
            }
        }
        val settled = snapshotFlow { pager.settledPage.takeUnless { pager.isScrollInProgress } }.first { it != null && it != page } ?: return@LaunchedEffect
        select(settled)
    }
    HorizontalPager(state = pager, modifier = modifier, key = { it },
        beyondViewportPageCount = if (LocalPlannerGlassAllowed.current) 1 else 0) { index ->
        val selected = index == page
        val inViewport by remember(pager, index) {
            derivedStateOf { kotlin.math.abs(pager.getOffsetDistanceInPages(index)) < 1f }
        }
        PagerPane(selected, selected || inViewport) { content(index) }
    }
}
