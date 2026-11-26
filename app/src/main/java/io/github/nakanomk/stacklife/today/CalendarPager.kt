// Adapted from BiliPai PagerSelectionMotion / HomePagerSyncEffects (c7f835b, GPL-3.0)
// and AndroidX PagerState (4aa409d, Apache-2.0). See assets/licenses.
package io.github.nakanomk.stacklife.today

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import io.github.nakanomk.stacklife.PagerPane
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.appearance.rememberNavigationPulse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The shell owns the business selection. Only the current input session may publish to it. */
internal class CalendarSelection(
    val pager: PagerState, private val scope: CoroutineScope, private val owner: State<Int>,
    private val animation: () -> AnimationSpec<Float>?, private val publish: (Int) -> Unit,
) {
    val committed get() = owner.value
    private var requested by mutableStateOf<Int?>(null)
    private var drag by mutableStateOf<DragInteraction.Start?>(null)
    private var origin = 0
    private var echo: Int? = null
    private var generation = 0L
    private var scroll: Job? = null
    private var alignedOwner = pager.currentPage

    val previewPage = derivedStateOf {
        requested ?: if (drag == null) null else {
            // Even the first fraction beyond the snap position exposes the adjacent date.
            // Keep the integer page separate: a fraction can disappear on far-away dates.
            val page = pager.currentPage
            val offset = pager.currentPageOffsetFraction
            when {
                page >= origin && offset > 0f -> page + 1
                page <= origin && offset < 0f -> page - 1
                else -> page
            }.coerceIn(0, pager.pageCount - 1)
        }
    }
    val displayPage = derivedStateOf { previewPage.value ?: committed }

    private fun interrupt() {
        generation++
        scroll?.cancel()
        drag = null
        requested = null
    }

    private fun choose(page: Int) {
        if (page != (echo ?: committed)) {
            echo = page
            publish(page)
        }
    }

    fun request(page: Int) {
        val target = page.coerceIn(0, pager.pageCount - 1)
        val takingOverDrag = drag != null
        interrupt()
        val ticket = generation
        requested = target
        choose(target)
        scroll = scope.launch {
            try {
                if (takingOverDrag) pager.stopScroll(MutatePriority.PreventUserInput)
                val spec = animation()
                if (spec == null) pager.scrollToPage(target) else pager.animateScrollToPage(target, animationSpec = spec)
            } finally {
                if (generation == ticket) requested = null
            }
        }
    }

    /** A notification's acknowledgement removes its key; that is not another page change. */
    fun synchronize(page: Int, externalLanding: Boolean) {
        if (page == alignedOwner && !externalLanding) return
        alignedOwner = page
        align(page, force = externalLanding)
    }

    /** Restores, notifications, and parent changes never masquerade as user requests. */
    private fun align(page: Int, force: Boolean) {
        val acknowledged = echo == page
        echo = null
        if (acknowledged && !force) return
        interrupt()
        if (!force && pager.currentPage == page && pager.currentPageOffsetFraction == 0f && !pager.isScrollInProgress) return
        scroll = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Cancel the user's mutation first; scrollToPage snaps and remeasures before
            // returning, rather than leaving a retained lazy key or old fling to land later.
            if (pager.isScrollInProgress) pager.stopScroll(MutatePriority.PreventUserInput)
            pager.scrollToPage(page)
        }
    }

    suspend fun observeDrags() {
        pager.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    interrupt()
                    origin = pager.settledPage
                    drag = interaction
                }
                is DragInteraction.Cancel -> if (drag === interaction.start) align(echo ?: committed, force = true)
                is DragInteraction.Stop -> if (drag === interaction.start) {
                    scroll = scope.launch {
                        // Give the pager its release/fling frame before reading the settled page.
                        withFrameNanos { }
                        snapshotFlow { pager.isScrollInProgress }.first { !it }
                        if (drag === interaction.start) {
                            drag = null
                            choose(pager.settledPage)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberCalendarSelection(page: Int, count: Int, externalTarget: Any? = null,
    onSelect: (Int) -> Unit): CalendarSelection = key(count) {
    val initial = page.coerceIn(0, count - 1)
    // The shell restores the date, not the fractional offset of an interrupted gesture.
    val pager = remember { PagerState(currentPage = initial) { count } }
    val scope = rememberCoroutineScope()
    val pulse = rememberNavigationPulse()
    // choose() is the single accepting owner for a tap or a completed swipe; align() is silent.
    val callback by rememberUpdatedState<(Int) -> Unit> { selected -> pulse(); onSelect(selected) }
    val spec by rememberUpdatedState(if (LocalMotionEnabled.current) MaterialTheme.motionScheme.fastSpatialSpec<Float>() else null)
    val owner = rememberUpdatedState(initial)
    val selection = remember(pager) { CalendarSelection(pager, scope, owner, { spec }, { callback(it) }) }
    LaunchedEffect(selection, initial, externalTarget) { selection.synchronize(initial, externalTarget != null) }
    LaunchedEffect(selection) { selection.observeDrags() }
    selection
}

// A horizontal gesture begun over the calendar belongs to it at the first/last page too.
// Don't pass a leftover delta or release velocity to the root destination pager.
private object CalendarEdge : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
        Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

@Composable
internal fun CalendarPager(selection: CalendarSelection, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val pager = selection.pager
    // Let AndroidX prefetch in the direction of a calendar scroll. Landing a root tab
    // isn't a date/week change and must not synchronously measure two unrelated calendars.
    HorizontalPager(state = pager, modifier = modifier.nestedScroll(CalendarEdge), key = { it }) { index ->
        val selected = index == selection.committed
        val inViewport by remember(pager, index) {
            derivedStateOf { kotlin.math.abs(pager.getOffsetDistanceInPages(index)) < 1f }
        }
        PagerPane(selected, selected || inViewport) { content(index) }
    }
}
