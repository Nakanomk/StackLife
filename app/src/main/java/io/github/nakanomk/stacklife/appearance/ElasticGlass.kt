// Motion adapted from Kyant's AndroidLiquidGlass / BiliPai AppElasticPress (Apache-2.0).
package io.github.nakanomk.stacklife.appearance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.tanh

@Stable
internal class GlassTouch {
    var held by mutableStateOf(false)
    var drag by mutableStateOf(Offset.Zero)
}

@Composable
internal fun rememberGlassTouchLayer(touch: GlassTouch): GraphicsLayerScope.() -> Unit {
    val press = animateFloatAsState(if (touch.held) 1f else 0f, spring(.82f, 780f), label = "glass contact")
    return remember(touch, press) { {
        val short = size.minDimension.coerceAtLeast(1f)
        val long = size.maxDimension.coerceAtLeast(1f)
        val p = press.value
        val d = touch.drag
        translationX = short * tanh(.05f * d.x / short) * p
        translationY = short * tanh(.05f * d.y / short) * p
        val expansion = 4.dp.toPx() / long
        val length = d.getDistance().coerceAtLeast(1f)
        scaleX = 1f + expansion * p * (1f + abs(d.x / length * d.x / long).coerceAtMost(1f))
        scaleY = 1f + expansion * p * (1f + abs(d.y / length * d.y / long).coerceAtMost(1f))
    } }
}

internal fun Modifier.observeGlassTouch(touch: GlassTouch, ownsScrub: Boolean): Modifier = pointerInput(touch, ownsScrub) {
    awaitEachGesture {
        val down = awaitFirstDown(false, PointerEventPass.Initial)
        touch.drag = Offset.Zero
        touch.held = true
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.size != 1) break
                val change = event.changes[0]
                if (change.id != down.id || !change.pressed) break
                if (!ownsScrub && change.isConsumed && change.position != change.previousPosition) break
                touch.drag = change.position - down.position
            }
        } finally { touch.held = false }
    }
}
