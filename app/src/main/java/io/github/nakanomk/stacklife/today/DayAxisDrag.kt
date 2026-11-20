package io.github.nakanomk.stacklife.today

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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.Instant
import kotlin.math.min

internal data class SlotSource(val id: String, val minutes: Int, val originalStart: Long? = null)
private sealed class Pickup(val source: SlotSource, val revision: Long) {
    class Move(source: SlotSource, revision: Long, val grabMillis: Long) : Pickup(source, revision)
    class End(source: SlotSource, revision: Long, val grabPixels: Float, val railX: Float) : Pickup(source, revision)
}
internal data class CheckedSlot(val slot: PlannerCommand.SlotSet, val error: String?)

internal class AxisDrag(val scale: DayAxisScale, val scroll: ScrollState) {
    var viewport by mutableStateOf(Rect.Zero)
    private var pickup by mutableStateOf<Pickup?>(null)
    var pointer by mutableStateOf(Offset.Zero)
        private set
    val active get() = pickup != null
    val resizeRailX get() = (pickup as? Pickup.End)?.railX
    var checked by mutableStateOf<CheckedSlot?>(null)
    var dropNotice by mutableStateOf<String?>(null)
    private fun contentY(point: Offset) = point.y - viewport.top + scroll.value
    private fun hit(point: Offset) = scale.instantAt(contentY(point))

    // Restrict invalidation (and domain previews) to distinct snapped instants, not pointer pixels.
    private val snapped by derivedStateOf {
        val session = pickup ?: return@derivedStateOf null
        val y = contentY(pointer)
        if (!viewport.contains(pointer) || y < 0 || y >= scale.height) null
        else {
            val step = 5 * MINUTE_MS
            when (session) {
                is Pickup.Move -> {
                    val delta = scale.instantAt(y) - session.grabMillis - scale.day.startMillis
                    val millis = scale.day.startMillis + Math.floorDiv(delta + step / 2, step) * step
                    millis.takeIf { it < scale.day.endMillis && it + session.source.minutes * MINUTE_MS > scale.day.startMillis }
                }
                is Pickup.End -> {
                    val start = requireNotNull(session.source.originalStart)
                    val end = start + session.source.minutes * MINUTE_MS
                    val endY = y + session.grabPixels
                    // Preserve an unsnapped original when the handle returns to its starting pixel.
                    if (kotlin.math.abs(scale.yAt(end) - endY) < 1f) end else {
                        // SlotSet expresses whole elapsed minutes, even for a start with seconds.
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
            val minutes = if (session is Pickup.End) (millis - start) / MINUTE_MS else session.source.minutes.toLong()
            if (minutes !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) null else
                PlannerCommand.SlotSet(session.source.id, Instant.ofEpochMilli(start).atZone(scale.day.zone).toOffsetDateTime(),
                    minutes.toInt(), session.revision)
        }
    }

    fun start(source: SlotSource, at: Offset, revision: Long) {
        dropNotice = null
        checked = null
        pointer = at
        pickup = Pickup.Move(source, revision, source.originalStart?.let { hit(at) - it } ?: 0L)
    }

    fun startEnd(source: SlotSource, at: Offset, railX: Float, revision: Long) {
        val end = requireNotNull(source.originalStart) + source.minutes * MINUTE_MS
        dropNotice = null
        checked = null
        pointer = at
        pickup = Pickup.End(source, revision, scale.yAt(end) - contentY(at), railX)
    }
    fun move(to: Offset) { pointer = to }
    fun stop() { pickup = null; checked = null }

    fun release(at: Offset, revision: Long, send: (PlannerCommand.SlotSet) -> Unit) {
        move(at)
        val command = candidate ?: return
        val session = pickup ?: return
        val original = session.source.originalStart
        when (session) {
            is Pickup.End -> if (command.durationMinutes == session.source.minutes) return
            is Pickup.Move -> if (original != null &&
                (hit(at) - session.grabMillis == original || command.start.toInstant().toEpochMilli() == original)) return
        }
        val error = checked?.takeIf { it.slot == command }?.error
        when {
            command.expectedRevision != revision -> dropNotice = "日程已更新，请重新拖动。"
            error != null -> dropNotice = error
            // If the finger lifted before the hover preview finished, submission still goes
            // through the exact same domain preview + revision-checked commit, never a blind write.
            else -> send(command)
        }
    }
}

internal fun Modifier.slotDrag(
    source: SlotSource, drag: AxisDrag, revision: Long, enabled: Boolean, send: (PlannerCommand.SlotSet) -> Unit,
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
    source: SlotSource, drag: AxisDrag, revision: Long, enabled: Boolean, send: (PlannerCommand.SlotSet) -> Unit,
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
    id: PointerId, coords: LayoutCoordinates, drag: AxisDrag, revision: () -> Long, send: (PlannerCommand.SlotSet) -> Unit,
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
internal fun DragEffects(drag: AxisDrag, checkSlot: suspend (PlannerCommand.SlotSet) -> String?) {
    val latestCheck by rememberUpdatedState(checkSlot)
    val command = drag.candidate
    LaunchedEffect(drag, command) {
        if (command != null) drag.checked = CheckedSlot(command, latestCheck(command))
        else drag.checked = null
    }
    val density = LocalDensity.current
    val edgePixels = with(density) { 40.dp.toPx() }
    val maxSpeed = with(density) { 480.dp.toPx() }
    val velocity by remember(drag, edgePixels, maxSpeed) {
        derivedStateOf {
            val box = drag.viewport
            val at = drag.pointer
            val edge = min(edgePixels, box.height / 4f)
            if (!drag.active || edge <= 0f || !box.contains(at)) 0f else when {
                at.y < box.top + edge -> -maxSpeed * (1f - (at.y - box.top) / edge)
                at.y > box.bottom - edge -> maxSpeed * (1f - (box.bottom - at.y) / edge)
                else -> 0f
            }
        }
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
