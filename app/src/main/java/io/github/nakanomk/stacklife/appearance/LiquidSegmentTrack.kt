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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
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
    modifier: Modifier = Modifier, enabled: Boolean = true, navigationInset: Dp? = null, indicatorInset: Dp = 0.dp,
    content: @Composable (highlighted: Int) -> Unit) {
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
    val track = if (navigationInset == null) modifier else modifier.padding(navigationInset)
    Box(track.then(input)) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val width = constraints.maxWidth.toFloat() / count
            val slotWidth = maxWidth / count
            // Fit the lens, not the hit target, when a wrapped caption makes a slot taller than wide.
            val slotHeight = if (navigationInset != null && slotWidth < maxHeight) slotWidth / 1.35f else maxHeight
            val rest = (slotHeight - indicatorInset * 2).coerceAtLeast(0.dp)
            val slotBounds = Modifier.align(Alignment.CenterStart).offset { IntOffset((width * slot.value).roundToInt(), 0) }
                .fillMaxWidth(1f / count)
            val shape = (if (indicatorInset > 0.dp) slotBounds.padding(horizontal = indicatorInset) else slotBounds).height(rest)
            if (shell == null) Spacer(shape.background(ink, CircleShape))
            else {
                // BiliPai's 64/56/78dp shell/rest/press reference, capped even when the text grows.
                val pressedScale = when {
                    navigationInset == null -> 78f / 56f
                    rest == 0.dp -> 1f
                    else -> (minOf(78.dp, (maxHeight + navigationInset * 2) * (78f / 64f)) / rest).coerceAtLeast(1f)
                }
                SelectionLens(shell, slot, count, contact, scrub, moving, navigationInset == null,
                    pressedScale, minOf(rest / 56.dp, 1f), shape)
            }
        }
        val highlighted by remember(slot) { derivedStateOf { slot.value.roundToInt() } }
        content(highlighted)
    }
}

@Composable
private fun SelectionLens(backdrop: Backdrop, slot: State<Float>, count: Int, contact: State<Float>, scrub: Scrub,
    moving: Boolean, compact: Boolean, pressedScale: Float, opticalScale: Float, modifier: Modifier) {
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
    val verticalScale = remember(pressure, speed, pressedScale) { derivedStateOf {
        (1f + (pressedScale - 1f) * pressure.value) * (1f - (.25f * speed.value).coerceIn(-.2f, .2f))
    } }
    // At rest the lens is exactly over its own shell with no refraction, stretch
    // or shadow. Tint those actual pixels instead of replaying the entire page
    // and toolbar into a second lens during every scroll and keyboard frame.
    val resting by remember(pressure, speed) { derivedStateOf { pressure.value == 0f && speed.value == 0f } }
    if (resting) {
        Spacer(modifier.background(idle.copy(alpha = .10f), CircleShape))
        return
    }
    Spacer(modifier.drawBackdrop(backdrop, shape = { CircleShape }, effects = {
        val reach = opticalScale * pressure.value
        if (reach > 0f) {
            val sy = verticalScale.value
            val gap = ((size.height * sy - (size.height + 8.dp.toPx())) / (2f * sy)).coerceAtLeast(0f)
            dockLens(10.dp.toPx() * reach, 14.dp.toPx() * reach, depth = true, dispersion = .5f, band = if (compact) gap else -1f)
        }
    }, layerBlock = {
        val p = pressure.value
        scaleX = (1f + (pressedScale - 1f) * p) / (1f - (.75f * speed.value).coerceIn(-.2f, .2f))
        scaleY = verticalScale.value
        val short = size.minDimension.coerceAtLeast(1f)
        translationX = short * tanh(.05f * scrub.drag.x / short) * contact.value
        translationY = short * tanh(.05f * scrub.drag.y / short) * contact.value
    }, onDrawSurface = {
        val p = pressure.value
        drawRect(idle, alpha = .10f * (1f - p))
        drawRect(Color.Black, alpha = .03f * p)
    }, onDrawFront = { if (pressure.value > 0f) drawEdgeStroke(CircleShape, light.value, pressure.value) })
        .innerShadow(CircleShape) { radius = 8.dp.toPx() * opticalScale * pressure.value; color = Color.Black; alpha = .15f * pressure.value })
}
