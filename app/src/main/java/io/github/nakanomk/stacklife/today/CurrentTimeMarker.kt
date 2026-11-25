/* StackLife calendar adaptation; source mapping and licenses: assets/licenses/CurrentTimeMarker-Notices.txt.
 * Wave/gate: BiliPai (GPL-3.0). First-head/tail timing and path extraction:
 * Copyright 2022, 2024, 2025 The Android Open Source Project (Apache-2.0).
 */
package io.github.nakanomk.stacklife.today

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalAppearance
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import io.github.nakanomk.stacklife.planner.NowLineStyle
import io.github.nakanomk.stacklife.transitions.LocalFrontEntry
import kotlin.math.PI
import kotlin.math.sin

// AndroidX ProgressIndicator: linearIndeterminateFirstLine{Head,Tail}AnimationSpec.
private val segmentEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private fun segmentTiming(delay: Int) = infiniteRepeatable(animation = keyframes<Float> {
    durationMillis = 1750
    0f at delay using segmentEasing
    1f at delay + 1000
})
private val headTiming = segmentTiming(0)
private val tailTiming = segmentTiming(250)
private val waveTiming = infiniteRepeatable<Float>(animation = tween(1400, easing = LinearEasing))

@Composable
internal fun CurrentTimeMarker(drag: AxisDrag, spine: Dp, millis: Long) {
    val day = drag.scale.day
    if (millis < day.startMillis || millis >= day.endMillis) return
    val y = remember(drag.scale, millis) { drag.scale.yAt(millis) }
    val inAxis by remember(drag, y) { derivedStateOf {
        val local = y - drag.scroll.value
        drag.viewport.width > 0f && local >= 0 && local < drag.viewportHeight
    } }
    if (!inAxis) return

    val appearance = LocalAppearance.current
    val scheme = MaterialTheme.colorScheme
    val time = remember(day, millis) { clockLabel(millis, day) }
    val now = stringResource(R.string.today_now_at, time)
    var inWindow by remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Both the axis and the enclosing pagers may clip the marker. An entry can
    // also remain composed behind its detail, or in a resumed but unfocused window.
    val active = inWindow && LocalFrontEntry.current && lifecycle == Lifecycle.State.RESUMED &&
        LocalWindowInfo.current.isWindowFocused && LocalMotionEnabled.current
    val wave = appearance.nowLine == NowLineStyle.WAVE
    val clock = if (active && (wave || appearance.nowTrail)) rememberInfiniteTransition(label = "now-marker") else null
    val phase = if (wave) clock?.animateFloat(0f, 1f, waveTiming, label = "wave") else null
    val head = if (appearance.nowTrail) clock?.animateFloat(0f, 1f, headTiming, label = "head") else null
    val tail = if (appearance.nowTrail) clock?.animateFloat(0f, 1f, tailTiming, label = "tail") else null

    Box(Modifier.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        layout(child.width, child.height) { child.place(0, y - drag.scroll.value - child.height / 2) }
    }.fillMaxWidth().heightIn(min = 92.dp).onVisibilityChanged(minFractionVisible = 0f) { inWindow = it }
        .testTag("now-line")) {
        if (appearance.nowLine != NowLineStyle.OFF) {
            Spacer(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(start = spine)
                .height(18.dp).timeLine(wave, phase, scheme.primary, scheme.surface))
        }
        if (appearance.nowTrail) {
            Spacer(Modifier.align(Alignment.CenterStart).offset(x = spine + 9.dp, y = (-33).dp)
                .size(6.dp, 26.dp).timeTrail(tail, head, scheme.primary, scheme.surface))
        }
        if (appearance.nowArrow) {
            // The vector tip is at (12,20) in a 24dp viewport: keep it on the real instant.
            Icon(painterResource(R.drawable.ic_now_arrow_downward), contentDescription = null,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = spine, y = (-8).dp)
                    .size(24.dp).background(scheme.surface, CircleShape), tint = scheme.primary)
        }
        Text(time, Modifier.align(Alignment.CenterStart).widthIn(max = spine)
            .clearAndSetSemantics { contentDescription = now }
            .background(scheme.primary, MaterialTheme.shapes.small).padding(horizontal = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary)
    }
}

// MusicWavySlider's wavelength/12 samples, 6dp amplitude, 22dp wavelength and
// 3dp rounded stroke. Cache an extra period so motion only translates the path.
private fun Modifier.timeLine(wave: Boolean, phase: State<Float>?, ink: Color, surface: Color) = drawWithCache {
    val width = size.width
    val centerY = size.height / 2f
    val wavelength = 22.dp.toPx()
    val amplitude = 6.dp.toPx()
    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
    val rim = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
    val path = if (wave) Path().apply {
        val step = (wavelength / 12f).coerceAtLeast(2f)
        var x = 0f
        moveTo(0f, centerY)
        while (x < width + wavelength) {
            x += step
            lineTo(x, centerY + sin(x / wavelength * (2 * PI).toFloat()) * amplitude)
        }
    } else null
    onDrawBehind {
        if (path == null) {
            val start = Offset(0f, centerY)
            val end = Offset(width, centerY)
            drawLine(surface, start, end, rim.width)
            drawLine(ink, start, end, stroke.width)
        } else clipRect {
            translate(left = -(phase?.value ?: 0f) * wavelength) {
                drawPath(path, surface, style = rim)
                drawPath(path, ink, style = stroke)
            }
        }
    }
}

// LinearWavyProgressModifiers: clamp for caps, rewind reused destination and
// extract the interval by arc length. The local path points down (also in RTL).
private fun Modifier.timeTrail(tail: State<Float>?, head: State<Float>?, ink: Color, surface: Color) = drawWithCache {
    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
    val rim = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
    val line = Path().apply { moveTo(size.width / 2f, 0f); lineTo(size.width / 2f, size.height) }
    val measure = PathMeasure().apply { setPath(line, false) }
    val segment = Path()
    val length = measure.length
    val cap = (rim.width / 2f).coerceAtMost(length / 2f)
    onDrawBehind {
        val start = ((tail?.value ?: 0f) * length).coerceIn(cap, length - cap)
        val end = ((head?.value ?: 1f) * length).coerceIn(cap, length - cap)
        if (end > start) {
            segment.rewind()
            if (measure.getSegment(start, end, segment)) {
                drawPath(segment, surface, style = rim)
                drawPath(segment, ink, style = stroke)
            }
        }
    }
}
