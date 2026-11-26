// SPDX-License-Identifier: GPL-3.0-only
// Live card layers, interpolated container geometry and ink handoff adapted from BiliPai
// c7f835b34d8323358be57be298f86f29fecb53c8, SettingsContainer{Transition,MotionPolicy}.kt.
// Here the aperture joins a group of actual queue rows to one actual frame. The workspace
// Pager's spring is the only clock; neither endpoint needs a bitmap or a second animation.
package io.github.nakanomk.stacklife.workspace

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import io.github.nakanomk.stacklife.PagerPane
import io.github.nakanomk.stacklife.planner.CommitResult
import io.github.nakanomk.stacklife.appearance.FrostedCard
import io.github.nakanomk.stacklife.planner.FrameView
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.Workspace
import io.github.nakanomk.stacklife.planner.WorkspaceEffect
import io.github.nakanomk.stacklife.transitions.LocalFrontEntry
import io.github.nakanomk.stacklife.transitions.LocalRouteOriginsEnabled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec

/** Only values/IDs survive recreation in the ViewModel. Never retain a layer or a Compose host. */
internal class WorkspaceTransfers {
    internal class FrameOrigin(val owner: String, val frameId: String, val rows: VisibleCards)
    private var opening: FrameOrigin? = null
    var frameOrigin: FrameOrigin? = null
        private set
    fun openFrame(owner: String, id: String, rows: VisibleCards) { opening = FrameOrigin(owner, id, rows) }
    fun claimFrame(id: String): String? {
        val origin = opening
        opening = null
        if (origin?.frameId != id) return null
        frameOrigin = origin
        return origin.owner
    }

    var current by mutableStateOf<WorkspaceTransfer?>(null)
        private set
    fun begin(transfer: WorkspaceTransfer, ticket: String) { transfer.submission = ticket; current = transfer }
    fun end(transfer: WorkspaceTransfer) { if (current === transfer) current = null }
    fun cancel(owner: String) { current?.takeIf { it.owner == owner }?.let(::end) }
    fun reset() { current = null; opening = null; frameOrigin = null }
}

internal class WorkspaceTransfer(val owner: String, val before: Workspace, val fromFrame: String?,
    val source: VisibleCards, val selection: List<String>? = null) {
    // The local Submission ticket precedes the transaction's distinct requestId/revision.
    lateinit var submission: String
    var receipt by mutableStateOf<CommitResult.Applied?>(null)
        private set
    // A retained workspace behind the detail must not move the detail's own return anchor.
    var returningToPager by mutableStateOf(fromFrame == null)
    var closingFrame = false
    var presented = false
        private set
    private var presenting = false
    fun claim(): Boolean {
        if (presenting) return false
        presenting = true
        presented = true
        return true
    }
    fun releaseForRecreation() { presenting = false }
    fun applied(result: CommitResult.Applied) {
        when (val effect = result.effects.singleOrNull()) {
            is WorkspaceEffect.Pushed -> if (toStack) receipt = result
            is WorkspaceEffect.Restored -> if (effect.frameId == fromFrame) receipt = result
            else -> Unit
        }
    }
    val toStack get() = fromFrame == null
}

internal class VisibleCards(val size: IntSize, val bounds: Map<String, Rect>)

/** Item offsets are list-local (before content padding). Ignore even partially occluded rows. */
internal fun LazyListState.visibleCards(gutter: Int): VisibleCards {
    val layout = layoutInfo
    val size = layout.viewportSize
    val bounds = buildMap {
        for (item in layout.visibleItemsInfo) {
            val key = item.key as? String ?: continue
            val y = item.offset - layout.viewportStartOffset
            if (y >= layout.beforeContentPadding && y + item.size <= size.height - layout.afterContentPadding && item.size > 0 && size.width > 2 * gutter)
                put(key, Rect(gutter.toFloat(), y.toFloat(), (size.width - gutter).toFloat(), (y + item.size).toFloat()))
        }
    }
    return VisibleCards(size, bounds)
}

private class FlyingTask(val task: QueueTask, val rank: Int, val bounds: Rect)

internal class WorkspaceFlight private constructor(private val slots: Map<String, Rect>,
    private val tasks: List<FlyingTask>, private val multiple: Boolean, private val frame: FrameView,
    private val frameBox: Rect, private val group: Rect, private val toStack: Boolean) {
    /** Keep lazy item keys and their exact occupied space, not a second copy of their content. */
    @Composable fun Reserve(stack: Boolean, id: String): Boolean {
        val rect = if (stack) frameBox.takeIf { frame.id == id } else slots[id]
        if (rect == null) return false
        Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { rect.height.toDp() }))
        return true
    }

    private fun frameInk(t: Float): Float {
        val u = if (toStack) ((t - .22f) / .28f).coerceIn(0f, 1f) else ((t - .45f) / .30f).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
    /** Scale one container; only the page clock updates its layer, not the pager's draw list. */
    @Composable fun Paint(position: State<Float>, selection: List<String>?) {
        val density = LocalDensity.current
        val bounds = frameBox
        // Absolute top-left also in RTL: these are already measured physical coordinates.
        val transform = Modifier.graphicsLayer {
            val t = position.value.coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = lerp(group.left, bounds.left, t)
            translationY = lerp(group.top, bounds.top, t)
            scaleX = lerp(1f, bounds.width / group.width, t)
            scaleY = lerp(1f, bounds.height / group.height, t)
        }
        // Draw the real bodies *here*. Measuring a Lazy/Pager page beyond the viewport does
        // not require its RenderNode to draw. Never hide its source and wait for that draw.
        Layout(modifier = transform.requiredSize(with(density) { group.width.toDp() }, with(density) { group.height.toDp() })
            .testTag("workspace-transfer").clearAndSetSemantics { hideFromAccessibility() }, content = {
            for (row in tasks) key(row.task.id) {
                FrostedCard {
                    QueueCardBody(row.task, row.rank, checked = selection?.contains(row.task.id)) {
                        if (multiple) QueueHandleGlyph()
                    }
                }
            }
            FrostedCard { FrameCardBody(frame) }
        }) { children, constraints ->
            val rows = Array(tasks.size) { index ->
                val slot = tasks[index].bounds
                children[index].measure(Constraints.fixed(slot.width.toInt(), slot.height.toInt()))
            }
            val folded = children.last().measure(Constraints.fixed(bounds.width.toInt(), bounds.height.toInt()))
            layout(constraints.maxWidth, constraints.maxHeight) {
                for (i in rows.indices) {
                    val slot = tasks[i].bounds
                    rows[i].placeWithLayer((slot.left - group.left).toInt(), (slot.top - group.top).toInt()) {
                        alpha = if (frameInk(position.value) < 1f) 1f else 0f
                    }
                }
                folded.placeWithLayer(0, 0) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = group.width / bounds.width
                    scaleY = group.height / bounds.height
                    alpha = frameInk(position.value)
                }
            }
        }
    }
    fun valid(queue: LazyListState, stack: LazyListState): Boolean {
        fun valid(list: LazyListState, id: String, bounds: Rect): Boolean {
            val info = list.layoutInfo
            val items = info.visibleItemsInfo
            for (i in items.indices) {
                val item = items[i]
                if (item.key == id) {
                    val y = item.offset - info.viewportStartOffset
                    return y.toFloat() == bounds.top && item.size.toFloat() == bounds.height &&
                        y >= info.beforeContentPadding && y + item.size <= info.viewportSize.height - info.afterContentPadding &&
                        info.viewportSize.width.toFloat() == bounds.left + bounds.right
                }
            }
            return false
        }
        if (!valid(stack, frame.id, frameBox)) return false
        for (i in tasks.indices) if (!valid(queue, tasks[i].task.id, tasks[i].bounds)) return false
        return true
    }
    companion object {
        fun build(request: WorkspaceTransfer, after: Workspace, queue: VisibleCards, stack: VisibleCards): WorkspaceFlight? {
            val effect = request.receipt?.effects?.singleOrNull()
            val frameId: String; val moved: List<String>
            when (effect) {
                is WorkspaceEffect.Pushed -> { frameId = effect.frameId; moved = effect.taskIds }
                is WorkspaceEffect.Restored -> { frameId = effect.frameId; moved = effect.taskIds }
                else -> return null
            }
            if (request.before.revision + 1 != after.revision || request.source.size != queue.size || queue.size != stack.size) return null
            val frame = stack.bounds[frameId] ?: return null
            if (!request.toStack && frameId !in request.source.bounds) return null
            val slots = LinkedHashMap<String, Rect>()
            var group = Rect.Zero
            for (id in moved) {
                // A receipt can contain hundreds of IDs. The flight represents the
                // intersection of that receipt and the actual painted queue slots.
                val r = queue.bounds[id] ?: continue
                if (request.toStack && id !in request.source.bounds) continue
                slots[id] = r
                group = if (slots.size == 1) r else Rect(minOf(r.left, group.left), minOf(r.top, group.top), maxOf(r.right, group.right), maxOf(r.bottom, group.bottom))
            }
            if (slots.isEmpty()) return null
            val projection = if (request.toStack) request.before.queue else after.queue
            val tasks = ArrayList<FlyingTask>(slots.size)
            val expected = slots.keys.iterator()
            for (i in projection.indices) {
                val task = projection[i]
                val rect = slots[task.id] ?: continue
                if (!expected.hasNext() || expected.next() != task.id) return null
                tasks.add(FlyingTask(task, i, rect))
            }
            if (expected.hasNext()) return null
            val saved = (if (request.toStack) after.frames else request.before.frames).firstOrNull { it.id == frameId } ?: return null
            return WorkspaceFlight(slots, tasks, projection.size > 1, saved, frame, group, request.toStack)
        }
    }
}

/** Only scroll a destination with no visible receipt member. APPEND need not displace
 * an already visible subset just because the first restored ID is above the viewport. */
private suspend fun revealReceipt(effect: WorkspaceEffect, state: Workspace, queue: LazyListState,
    stack: LazyListState, gutter: Int) {
    when (effect) {
        is WorkspaceEffect.Pushed -> if (effect.frameId !in stack.visibleCards(gutter).bounds) {
            val index = state.frames.indexOfFirst { it.id == effect.frameId && it.remaining > 0 }
            if (index >= 0) stack.scrollToItem(index + 2) // summary, recent frames
        }
        is WorkspaceEffect.Restored -> {
            val visible = queue.visibleCards(gutter).bounds
            if (effect.taskIds.none { it in visible }) {
                val id = effect.taskIds.firstOrNull() ?: return
                val index = state.queue.indexOfFirst { it.id == id }
                if (index >= 0) queue.scrollToItem(index + 1) // summary
            }
        }
        else -> Unit
    }
}

@Composable
internal fun WorkspaceTransferPager(state: Workspace, transfers: WorkspaceTransfers, owner: String,
    stack: Boolean, setStack: (Boolean) -> Unit, motion: Boolean, pager: PagerState, position: State<Float>,
    queueScroll: LazyListState, stackScroll: LazyListState, gutter: Int, queueSelection: State<List<String>?>,
    page: @Composable (index: Int, shown: Workspace, selected: Boolean, flight: WorkspaceFlight?, selection: List<String>?) -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(transfers, owner, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true)
                transfers.current?.takeIf { it.owner == owner && it.toStack }?.let(transfers::end)
        }
    }
    val request = transfers.current?.takeIf { it.owner == owner }
    val receipt = request?.receipt
    val front = LocalFrontEntry.current && LocalRouteOriginsEnabled.current
    val mode = if (stack) 1 else 0
    val choose by rememberUpdatedState(setStack)
    var flight by remember(request) { mutableStateOf<WorkspaceFlight?>(null) }
    val destination = if (request?.toStack == true) 1 else 0
    // A manual segment tap clears the request. External writes, Undo and Keep cannot create one.
    LaunchedEffect(mode, motion, request) {
        if (request == null && (pager.currentPage != mode || pager.currentPageOffsetFraction != 0f)) {
            if (motion) pager.animateScrollToPage(mode, animationSpec = tween(320)) else pager.scrollToPage(mode)
        }
    }
    LaunchedEffect(request, receipt?.requestId, receipt?.revision, request?.returningToPager, state.revision, front, motion) {
        if (request == null) return@LaunchedEffect
        if (!front && request.toStack) { transfers.end(request); return@LaunchedEffect }
        if (receipt == null || !front || !request.returningToPager || state.revision < receipt.revision) return@LaunchedEffect
        if (state.revision != receipt.revision) { transfers.end(request); return@LaunchedEffect }
        val animateReceipt = motion && !request.presented
        if (!request.claim()) return@LaunchedEffect
        try {
            // The saveable parent owns the final segment as soon as the receipt is claimed. A
            // recreated host resumes the real result, never a second flight or a second write.
            choose(request.toStack)
            withFrameNanos { } // both real lists are composed/measured; don't guess heights from text
            val effect = receipt.effects.single()
            revealReceipt(effect, state, queueScroll, stackScroll, gutter)
            val plan = if (animateReceipt && pager.currentPage == 1 - destination && pager.currentPageOffsetFraction == 0f)
                WorkspaceFlight.build(request, state, queueScroll.visibleCards(gutter), stackScroll.visibleCards(gutter)) else null
            if (plan == null) {
                pager.scrollToPage(destination)
            } else {
                flight = plan
                // One owner for animation, loss of its anchors, and final selection. Starting
                // a competing scroll from a different effect could cancel this entire receipt.
                coroutineScope {
                    val travel = launch {
                        val spec = NavSettleSpec.Spring()
                        pager.animateScrollToPage(destination, animationSpec = spring(dampingRatio = spec.dampingRatio,
                            stiffness = spec.stiffness, visibilityThreshold = spec.visibilityThreshold * pager.layoutInfo.pageSize.coerceAtLeast(1)))
                    }
                    val watch = launch {
                        snapshotFlow { plan.valid(queueScroll, stackScroll) }.first { !it }
                        travel.cancel()
                    }
                    travel.join()
                    watch.cancel()
                    if (travel.isCancelled) {
                        flight = null
                        revealReceipt(effect, state, queueScroll, stackScroll, gutter)
                        pager.scrollToPage(destination)
                    }
                }
            }
        } finally {
            flight = null
            if (activity?.isChangingConfigurations == true) request.releaseForRecreation() else transfers.end(request)
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft) {
        val lock = if (request != null && front) Modifier.pointerInput(request) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
        } else Modifier
        HorizontalPager(pager, Modifier.fillMaxSize().then(lock), userScrollEnabled = false,
            beyondViewportPageCount = if (request != null) 1 else 0) { index ->
            val inViewport by remember(pager, index) { derivedStateOf { kotlin.math.abs(position.value - index) < 1f } }
            val selected = index == mode
            val visible = inViewport || request != null
            val sourcePage = request != null && index != destination
            PagerPane(selected && (request == null || !sourcePage), selected || visible) {
                page(index, if (sourcePage) request!!.before else state, selected, flight,
                    if (sourcePage) request?.selection else null)
            }
        }
        flight?.Paint(position, if (request?.toStack == true) request.selection else queueSelection.value)
    }
}
