package io.github.nakanomk.stacklife.today

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
private data class Pickup(val source: SlotSource, val revision: Long, val grabMillis: Long)
internal data class CheckedSlot(val slot: PlannerCommand.SlotSet, val error: String?)

internal class AxisDrag(val scale: DayAxisScale, val scroll: ScrollState) {
    var viewport by mutableStateOf(Rect.Zero)
    private var pickup by mutableStateOf<Pickup?>(null)
    var pointer by mutableStateOf(Offset.Zero)
        private set
    val active get() = pickup != null
    var checked by mutableStateOf<CheckedSlot?>(null)
    var dropNotice by mutableStateOf<String?>(null)
    private fun hit(point: Offset) = scale.instantAt(point.y - viewport.top + scroll.value)

    // Restrict invalidation (and domain previews) to distinct snapped instants, not pointer pixels.
    private val snapped by derivedStateOf {
        val session = pickup
        if (session == null || !viewport.contains(pointer) || scale.height == 0 ||
            pointer.y - viewport.top + scroll.value >= scale.height) null
        else {
            val delta = hit(pointer) - session.grabMillis - scale.day.startMillis
            val step = 5 * MINUTE_MS
            val millis = scale.day.startMillis + Math.floorDiv(delta + step / 2, step) * step
            millis.takeIf { it < scale.day.endMillis && it + session.source.minutes * MINUTE_MS > scale.day.startMillis }
        }
    }
    val candidate by derivedStateOf {
        val session = pickup
        val millis = snapped
        if (session == null || millis == null) null else PlannerCommand.SlotSet(session.source.id,
            Instant.ofEpochMilli(millis).atZone(scale.day.zone).toOffsetDateTime(), session.source.minutes, session.revision)
    }

    fun start(source: SlotSource, at: Offset, revision: Long) {
        dropNotice = null
        checked = null
        pointer = at
        pickup = Pickup(source, revision, source.originalStart?.let { hit(at) - it } ?: 0L)
    }
    fun move(to: Offset) { pointer = to }
    fun stop() { pickup = null; checked = null }

    fun release(at: Offset, revision: Long, send: (PlannerCommand.SlotSet) -> Unit) {
        move(at)
        val command = candidate ?: return
        val session = pickup ?: return
        val original = session.source.originalStart
        if (original != null && (hit(at) - session.grabMillis == original || command.start.toInstant().toEpochMilli() == original)) return
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
            try {
                while (true) {
                    // Once picked up, own the pointer before clickable/scrollable sees the up.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val point = event.changes.singleOrNull() ?: break
                    if (point.id != held.id || point.isConsumed || !coords.isAttached) break
                    val root = coords.localToRoot(point.position)
                    point.consume()
                    if (!point.pressed) {
                        drag.release(root, latestRevision, latestSend)
                        break
                    }
                    drag.move(root)
                }
            } finally {
                // Android cancellation, navigation, source removal and disposal all discard pickup.
                drag.stop()
            }
        }
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
