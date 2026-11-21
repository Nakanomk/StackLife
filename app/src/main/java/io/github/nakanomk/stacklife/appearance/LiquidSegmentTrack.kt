// Optical layering and motion adapted from compose-miuix-ui's LiquidGlassNavigationBar
// and Kyant0/AndroidLiquidGlass (Apache-2.0). BiliPai reference: FloatingDockChrome @ 5893c2c8.
package io.github.nakanomk.stacklife.appearance

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.material.drawEdgeStroke
import kotlin.math.*

@Stable
private class Scrub {
    var held by mutableStateOf(false)
    var preview by mutableFloatStateOf(Float.NaN)
    var releaseTarget by mutableStateOf<Int?>(null)
    var drag by mutableStateOf(Offset.Zero)

    fun slot(page: Float, contact: Float): Float {
        if (preview.isNaN()) return page
        if (held) return preview
        val end = releaseTarget?.toFloat() ?: page
        return end + (preview - end) * contact
    }
}

@Composable
internal fun SegmentTrack(position: State<Float>, count: Int, ink: Color, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = true, content: @Composable (highlighted: Int) -> Unit) {
    val scrub = remember(count) { Scrub() }
    val select = rememberUpdatedState(onSelect)
    val page = rememberUpdatedState(position)
    val shell = LocalCapsuleBackdrop.current
    val moving = LocalMotionEnabled.current
    val contact = animateFloatAsState(if (moving && scrub.held) 1f else 0f,
        spring(.85f, 800f, .001f), label = "lens contact")
    val slot = remember(scrub, page, contact, count) { derivedStateOf {
        scrub.slot(page.value.value, contact.value.coerceIn(0f, 1f)).coerceIn(0f, count - 1f)
    } }
    val target = scrub.releaseTarget
    LaunchedEffect(target) {
        if (target != null) {
            var previous = abs(page.value.value - target)
            snapshotFlow { page.value.value }.first { current ->
                val distance = abs(current - target)
                val settledOrReversed = distance <= .002f || distance > previous + .01f
                previous = distance
                settledOrReversed
            }
            scrub.releaseTarget = null
        }
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val input = if (!enabled) Modifier else Modifier.pointerInput(scrub, count, direction) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            if (size.width == 0) return@awaitEachGesture
            val width = size.width.toFloat() / count
            val startX = if (direction > 0) down.position.x else size.width - down.position.x
            val origin = (startX / width).toInt().coerceIn(0, count - 1)
            val slop = viewConfiguration.touchSlop
            scrub.releaseTarget = null
            scrub.preview = Float.NaN
            scrub.drag = Offset.Zero
            scrub.held = true
            var horizontal = false
            var vertical = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) break
                    val point = event.changes[0]
                    if (point.id != down.id || point.isConsumed) break
                    val delta = point.position - down.position
                    scrub.drag = delta
                    if (!horizontal && !vertical) {
                        horizontal = abs(delta.x) > slop && abs(delta.x) >= abs(delta.y) * 1.25f
                        vertical = !horizontal && abs(delta.y) > slop
                    }
                    if (horizontal) {
                        val x = (origin + direction * delta.x / width).coerceIn(0f, count - 1f)
                        scrub.preview = x
                        point.consume()
                        if (!point.pressed && point.position.y in -slop..(size.height + slop)) {
                            val index = x.roundToInt()
                            scrub.releaseTarget = index
                            select.value(index)
                        }
                    } else if (!point.pressed && vertical) point.consume()
                    if (!point.pressed) break
                }
            } finally { scrub.held = false }
        }
    }
    Box(modifier.then(input)) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val width = constraints.maxWidth.toFloat() / count
            val shape = Modifier.offset { IntOffset((width * slot.value).roundToInt(), 0) }.fillMaxWidth(1f / count).fillMaxHeight()
            if (shell == null) Spacer(shape.background(ink, CircleShape))
            else SelectionLens(shell, slot, count, contact, scrub, moving, compact, shape)
        }
        val highlighted by remember(slot) { derivedStateOf { slot.value.roundToInt() } }
        content(highlighted)
    }
}

@Composable
private fun SelectionLens(backdrop: Backdrop, slot: State<Float>, count: Int, contact: State<Float>, scrub: Scrub,
    moving: Boolean, compact: Boolean, modifier: Modifier) {
    val light = rememberGlassEdge(90)
    val idle = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color.White else Color.Black
    val speed = remember { Animatable(0f) }
    LaunchedEffect(slot, moving, count) {
        if (!moving) { speed.snapTo(0f); return@LaunchedEffect }
        val tracker = VelocityTracker()
        snapshotFlow { slot.value }.collectLatest { x ->
            tracker.addPosition(SystemClock.uptimeMillis(), Offset(x / (count - 1).coerceAtLeast(1), 0f))
            speed.snapTo((tracker.calculateVelocity().x / 10f).coerceIn(-.8f, .8f))
            speed.animateTo(0f, spring(.5f, 300f))
        }
    }
    val pressure = remember(contact, slot, moving) { derivedStateOf {
        if (!moving) 0f else max(contact.value, sin(PI * (slot.value % 1f)).toFloat()).coerceIn(0f, 1f)
    } }
    val verticalScale = remember(pressure, speed) { derivedStateOf {
        (1f + (78f / 56f - 1f) * pressure.value) * (1f - (.25f * speed.value).coerceIn(-.2f, .2f))
    } }
    Spacer(modifier.drawBackdrop(backdrop, shape = { CircleShape }, effects = {
        val reach = (size.height / 56.dp.toPx()).coerceAtMost(1f) * pressure.value
        if (reach > 0f) {
            val sy = verticalScale.value
            val gap = ((size.height * sy - (size.height + 8.dp.toPx())) / (2f * sy)).coerceAtLeast(0f)
            dockLens(10.dp.toPx() * reach, 14.dp.toPx() * reach, depth = true, dispersion = .5f, band = if (compact) gap else -1f)
        }
    }, layerBlock = {
        val p = pressure.value
        scaleX = (1f + (78f / 56f - 1f) * p) / (1f - (.75f * speed.value).coerceIn(-.2f, .2f))
        scaleY = verticalScale.value
        val short = size.minDimension.coerceAtLeast(1f)
        translationX = short * tanh(.05f * scrub.drag.x / short) * contact.value
        translationY = short * tanh(.05f * scrub.drag.y / short) * contact.value
    }, onDrawSurface = {
        val p = pressure.value
        drawRect(idle, alpha = .10f * (1f - p))
        drawRect(Color.Black, alpha = .03f * p)
    }, onDrawFront = { if (pressure.value > 0f) drawEdgeStroke(CircleShape, light.value, pressure.value) })
        .innerShadow(CircleShape) { radius = 8.dp.toPx() * pressure.value; color = Color.Black; alpha = .15f * pressure.value })
}
