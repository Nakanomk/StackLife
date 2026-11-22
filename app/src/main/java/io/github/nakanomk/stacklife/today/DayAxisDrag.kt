package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.i18n.uiText
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.*
import kotlin.math.min

internal data class SlotSource(val key: OccurrenceKey.Schedule, val durationMillis: Long, val originalStart: Long? = null)
internal data class SlotDrop(val interval: ExactInterval, val command: PlannerCommand)
private sealed class Pickup(val source: SlotSource, val revision: Long) {
    class Move(source: SlotSource, revision: Long, val grabMillis: Long) : Pickup(source, revision)
    class End(source: SlotSource, revision: Long, val grabPixels: Float, val railX: Float) : Pickup(source, revision)
}
internal data class CheckedSlot(val slot: SlotDrop, val error: String?)

internal class AxisDrag(val scale: DayAxisScale, val scroll: ScrollState) {
    // A held pointer need not move when the IME or tray moves across it. Recheck
    // the snap and edge-scroll regions on placement changes during a drag.
    var viewport by mutableStateOf(Rect.Zero)
        private set
    private var viewportLayout: LayoutCoordinates? = null
    private var rootLayout: LayoutCoordinates? = null
    fun placeViewport(layout: LayoutCoordinates) {
        viewportLayout = layout
        rootLayout = layout.findRootCoordinates()
        viewport = layout.boundsInRoot()
    }
    var trayBounds by mutableStateOf(Rect.Zero)
    var addBounds by mutableStateOf(Rect.Zero)
    private var pickup by mutableStateOf<Pickup?>(null)
    var pointer by mutableStateOf(Offset.Zero)
        private set
    val active get() = pickup != null
    fun isDragging(key: OccurrenceKey) = pickup?.source?.key == key
    val resizeRailX get() = (pickup as? Pickup.End)?.railX
    var checked by mutableStateOf<CheckedSlot?>(null)
    var dropNotice by mutableStateOf<String?>(null)
    // Gestures arrive in the transformed card/handle's coordinates, then localToRoot maps them
    // into the same space as tray hit tests. Only the untransformed viewport feeds instantAt.
    private fun contentY(point: Offset): Float {
        val viewportCoords = viewportLayout ?: return Float.NaN
        val root = rootLayout ?: return Float.NaN
        if (!viewportCoords.isAttached || !root.isAttached) return Float.NaN
        return viewportCoords.localPositionOf(root, point).y + scroll.value
    }

    private fun accepts(at: Offset) = viewport.contains(at) && !trayBounds.contains(at) && !addBounds.contains(at)

    private fun Rect.edgeBelow(at: Offset) =
        if (at.x >= left && at.x < right && at.y < top) top else viewport.bottom
    private fun Rect.edgeAbove(at: Offset) =
        if (at.x >= left && at.x < right && at.y >= bottom) bottom else viewport.top

    fun uncoveredBottom(at: Offset) = minOf(viewport.bottom, trayBounds.edgeBelow(at), addBounds.edgeBelow(at))

    fun edgeVelocity(edgePixels: Float, maxSpeed: Float): Float {
        if (!active || !accepts(pointer)) return 0f
        val top = maxOf(viewport.top, trayBounds.edgeAbove(pointer), addBounds.edgeAbove(pointer))
        val bottom = uncoveredBottom(pointer)
        val edge = min(edgePixels, (bottom - top) / 4f)
        return when {
            edge <= 0f -> 0f
            pointer.y < top + edge -> -maxSpeed * (1f - (pointer.y - top) / edge)
            pointer.y > bottom - edge -> maxSpeed * (1f - (bottom - pointer.y) / edge)
            else -> 0f
        }
    }

    // Restrict invalidation (and domain previews) to distinct snapped instants, not pointer pixels.
    private val snapped by derivedStateOf {
        val session = pickup ?: return@derivedStateOf null
        val y = contentY(pointer)
        if (!accepts(pointer) || !(y >= 0f && y < scale.height)) null
        else {
            val step = 5 * MINUTE_MS
            when (session) {
                is Pickup.Move -> {
                    val delta = scale.instantAt(y) - session.grabMillis - scale.day.startMillis
                    val millis = scale.day.startMillis + Math.floorDiv(delta + step / 2, step) * step
                    millis.takeIf { intersectsWindow(it, it + session.source.durationMillis, scale.day.startMillis, scale.day.endMillis) }
                }
                is Pickup.End -> {
                    val start = requireNotNull(session.source.originalStart)
                    val end = start + session.source.durationMillis
                    val endY = y + session.grabPixels
                    // Preserve an unsnapped original when the handle returns to its starting pixel.
                    if (kotlin.math.abs(scale.yAt(end) - endY) < 1f) end else {
                        // Snap relative to the original sub-minute offset.
                        val origin = scale.day.startMillis + Math.floorMod(start - scale.day.startMillis, MINUTE_MS)
                        val steps = Math.floorDiv(scale.instantAt(endY) - origin + step / 2, step)
                        origin + steps.coerceIn(0L, Math.floorDiv(scale.day.endMillis - origin, step)) * step
                    }
                }
            }
        }
    }
    val candidate by derivedStateOf {
        val session = pickup
        val millis = snapped
        if (session == null || millis == null) null else {
            val start = if (session is Pickup.End) requireNotNull(session.source.originalStart) else millis
            val end = if (session is Pickup.End) millis else start + session.source.durationMillis
            // Changing a span to a point is explicit in the editor, not an accidental resize.
            if (end < start || session is Pickup.End && end == start) null else {
                val interval = ExactInterval(start, end)
                val key = session.source.key
                SlotDrop(interval, if (key.originalDay == null) PlannerCommand.SetSchedule(key.scheduleId,
                    SchedulePattern.Once(interval), expectedRevision = session.revision)
                else PlannerCommand.SetOccurrenceOverride(key, EditScope.THIS, interval = interval, expectedRevision = session.revision))
            }
        }
    }

    fun start(source: SlotSource, at: Offset, revision: Long) {
        val y = contentY(at)
        if (!y.isFinite()) return
        dropNotice = null
        checked = null
        pointer = at
        pickup = Pickup.Move(source, revision, source.originalStart?.let { scale.instantAt(y) - it } ?: 0L)
    }

    fun startEnd(source: SlotSource, at: Offset, railX: Float, revision: Long) {
        val y = contentY(at)
        if (!y.isFinite()) return
        val end = requireNotNull(source.originalStart) + source.durationMillis
        dropNotice = null
        checked = null
        pointer = at
        pickup = Pickup.End(source, revision, scale.yAt(end) - y, railX)
    }
    fun move(to: Offset) { pointer = to }
    fun stop() { pickup = null; checked = null }

    fun release(at: Offset, revision: Long, send: (PlannerCommand) -> Unit) {
        move(at)
        val y = contentY(at)
        if (!y.isFinite()) return
        val session = pickup ?: return
        if (session.revision != revision) {
            dropNotice = uiText("The schedule changed. Drag again.", "日程已更新，请重新拖动。")
            return
        }
        val command = candidate ?: return
        val original = session.source.originalStart
        when (session) {
            is Pickup.End -> if (command.interval.endMillis - command.interval.startMillis == session.source.durationMillis) return
            is Pickup.Move -> if (original != null &&
                (scale.instantAt(y) - session.grabMillis == original || command.interval.startMillis == original)) return
        }
        val error = checked?.takeIf { it.slot == command }?.error
        // If the finger lifted before the hover preview finished, submission still goes
        // through the exact same domain preview + revision-checked commit, never a blind write.
        if (error != null) dropNotice = error else send(command.command)
    }
}

internal fun Modifier.slotDrag(
    source: SlotSource, drag: AxisDrag, revision: Long, enabled: Boolean, send: (PlannerCommand) -> Unit,
): Modifier = composed {
    var layout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestRevision by rememberUpdatedState(revision)
    val latestSend by rememberUpdatedState(send)
    onPlaced { layout = it }.pointerInput(source, drag, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val coords = layout?.takeIf { it.isAttached } ?: return@awaitEachGesture
            drag.start(source, coords.localToRoot(held.position), latestRevision)
            trackSlot(held.id, coords, drag, { latestRevision }, { latestSend(it) })
        }
    }
}

/** Already selected: grab the endpoint on vertical slop, not a second long press.
 * Until then the surrounding day pager, scroll and accessible click remain available. */
internal fun Modifier.endDrag(
    source: SlotSource, drag: AxisDrag, revision: Long, enabled: Boolean, send: (PlannerCommand) -> Unit,
): Modifier = composed {
    var layout by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestRevision by rememberUpdatedState(revision)
    val latestSend by rememberUpdatedState(send)
    onPlaced { layout = it }.pointerInput(source, drag, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val coords = layout?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val start = coords.localToRoot(down.position)
            val base = latestRevision
            val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            if (slop.id != down.id || !coords.isAttached) return@awaitEachGesture
            drag.startEnd(source, start, coords.localToRoot(Offset(size.width / 2f, 0f)).x, base)
            drag.move(coords.localToRoot(slop.position))
            trackSlot(slop.id, coords, drag, { latestRevision }, { latestSend(it) })
        }
    }
}

private suspend fun AwaitPointerEventScope.trackSlot(
    id: PointerId, coords: LayoutCoordinates, drag: AxisDrag, revision: () -> Long, send: (PlannerCommand) -> Unit,
) {
    try {
        while (true) {
            val point = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull() ?: break
            if (point.id != id || point.isConsumed || !coords.isAttached) break
            val root = coords.localToRoot(point.position)
            point.consume()
            if (!point.pressed) {
                drag.release(root, revision(), send)
                break
            }
            drag.move(root)
        }
    } finally {
        drag.stop()
    }
}

@Composable
internal fun DragEffects(drag: AxisDrag, checkSlot: suspend (PlannerCommand) -> String?) {
    val latestCheck by rememberUpdatedState(checkSlot)
    val command = drag.candidate
    LaunchedEffect(drag, command) {
        if (command != null) drag.checked = CheckedSlot(command, latestCheck(command.command))
        else drag.checked = null
    }
    val density = LocalDensity.current
    val edgePixels = with(density) { 40.dp.toPx() }
    val maxSpeed = with(density) { 480.dp.toPx() }
    val velocity by remember(drag, edgePixels, maxSpeed) {
        derivedStateOf { drag.edgeVelocity(edgePixels, maxSpeed) }
    }
    val direction = velocity.compareTo(0f)
    LaunchedEffect(drag, direction) {
        if (direction == 0) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (if (direction < 0) drag.scroll.canScrollBackward else drag.scroll.canScrollForward) {
            val now = withFrameNanos { it }
            drag.scroll.scrollBy(velocity * ((now - last) / 1_000_000_000f).coerceAtMost(0.05f))
            last = now
        }
    }
}
