package io.github.nakanomk.stacklife.today

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.nakanomk.stacklife.PagerPane
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Only a real drag may publish a settled page. A superseded scroll must never publish anything. */
internal class CalendarSelection(
    val pager: PagerState, initial: Int, private val count: Int, private val scope: CoroutineScope,
    private val animation: () -> AnimationSpec<Float>?, private val publish: (Int) -> Unit,
) {
    var committed by mutableIntStateOf(initial)
        private set
    private var requested by mutableStateOf<Int?>(null)
    private var dragStart by mutableStateOf<DragInteraction.Start?>(null)
    private var origin = initial
    private var generation = 0L
    private var scroll: Job? = null

    val displayPage = derivedStateOf {
        requested ?: if (dragStart == null) committed else {
            val travel = pager.currentPage - origin + pager.currentPageOffsetFraction
            (origin + travel.compareTo(0f)).coerceIn(0, count - 1)
        }
    }

    fun select(page: Int) {
        val target = page.coerceIn(0, count - 1)
        val request = ++generation
        scroll?.cancel()
        dragStart = null
        requested = target
        if (committed != target) { committed = target; publish(target) }
        scroll = scope.launch {
            try {
                val spec = animation()
                if (spec == null) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = spec)
            } finally {
                if (generation == request) requested = null
            }
        }
    }

    suspend fun observeDrags() {
        pager.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    ++generation
                    scroll?.cancel()
                    requested = null
                    origin = pager.settledPage
                    dragStart = interaction
                }
                is DragInteraction.Cancel -> if (dragStart === interaction.start) select(committed)
                is DragInteraction.Stop -> if (dragStart === interaction.start) {
                    val request = generation
                    scroll = scope.launch {
                        withFrameNanos { }
                        snapshotFlow { !pager.isScrollInProgress }.first { it }
                        if (generation == request) {
                            val settled = pager.settledPage
                            dragStart = null
                            if (committed != settled) { committed = settled; publish(settled) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberCalendarSelection(page: Int, count: Int, onSelect: (Int) -> Unit): CalendarSelection = key(count) {
    val initial = page.coerceIn(0, count - 1)
    val pager = rememberPagerState(initialPage = initial) { count }
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onSelect)
    val spec by rememberUpdatedState(if (LocalMotionEnabled.current) MaterialTheme.motionScheme.fastSpatialSpec<Float>() else null)
    val selection = remember(pager) { CalendarSelection(pager, initial, count, scope, { spec }, { callback(it) }) }
    LaunchedEffect(initial, selection) {
        if (selection.committed != initial || (!pager.isScrollInProgress && pager.currentPage != initial)) selection.select(initial)
    }
    LaunchedEffect(selection) { selection.observeDrags() }
    selection
}

@Composable
internal fun CalendarPager(selection: CalendarSelection, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val pager = selection.pager
    // Let AndroidX prefetch in the direction of a calendar scroll. Landing a root tab
    // isn't a date/week change and must not synchronously measure two unrelated calendars.
    HorizontalPager(state = pager, modifier = modifier, key = { it }) { index ->
        val selected = index == selection.committed
        val inViewport by remember(pager, index) {
            derivedStateOf { kotlin.math.abs(pager.getOffsetDistanceInPages(index)) < 1f }
        }
        PagerPane(selected, selected || inViewport) { content(index) }
    }
}
