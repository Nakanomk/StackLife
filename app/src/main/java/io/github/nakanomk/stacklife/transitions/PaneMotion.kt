package io.github.nakanomk.stacklife.transitions

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Only geometry is registered; nothing records the pixels of a page, message, or WebView. */
internal class PaneOrigins {
    internal val nodes = mutableStateMapOf<String, LayoutCoordinates>()
}

internal fun Modifier.paneOrigin(origins: PaneOrigins, key: String): Modifier = composed {
    var node by remember { mutableStateOf<LayoutCoordinates?>(null) }
    DisposableEffect(origins, key) {
        onDispose { if (origins.nodes[key] === node) origins.nodes.remove(key) }
    }
    onGloballyPositioned { node = it; origins.nodes[key] = it }
}

/**
 * Two complementary clips over the same live panel. The parent never moves; the child occupies
 * its launcher's real rectangle and grows to its own bounds. Even when the panel is translucent,
 * no pixel of its content or backdrop is drawn twice. Invalid/removed launchers switch in place.
 */
@Composable
internal fun <T : Any> PaneMotion(
    page: T, origins: PaneOrigins, depth: (T) -> Int, originKey: (T) -> String,
    modifier: Modifier = Modifier, parent: T? = null, onBack: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val state = remember { SeekableTransitionState(page) }
    val transition = rememberTransition(state, label = "pane container")
    var rewind by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(page, motion) {
        rewind?.cancelAndJoin()
        if (motion) state.animateTo(page) else state.snapTo(page)
    }
    val recovery = rememberCoroutineScope()
    val endpoints = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    PredictiveBackHandler(enabled = parent != null && onBack != null) { events ->
        val previous = parent ?: return@PredictiveBackHandler
        rewind?.cancelAndJoin()
        try {
            events.collect { if (motion) state.seekTo(it.progress, previous) }
            onBack?.invoke()
        } catch (cancel: CancellationException) {
            // Retargeting animateTo(page) makes the original page both endpoints and
            // removes the clips in mid-gesture. Retrace the same two panes instead.
            if (state.targetState == previous) rewind = recovery.launch {
                val cursor = Animatable(state.fraction)
                val sampling = launch { snapshotFlow { cursor.value }.collect { state.seekTo(it, previous) } }
                try {
                    cursor.animateTo(0f, tween((300 * cursor.value).toInt(), easing = LinearEasing))
                } finally {
                    sampling.cancelAndJoin()
                }
                state.seekTo(0f, previous)
                state.snapTo(page)
            }
            throw cancel
        }
    }
    val height = transition.animateFloat({ if (motion) tween(300) else snap() }, label = "pane depth") { depth(it).toFloat() }
    val from = transition.currentState
    val to = transition.targetState
    val deep = if (depth(from) > depth(to)) from else to
    val lo = minOf(depth(from), depth(to))
    val distance = depth(deep) - lo
    transition.AnimatedContent(modifier = modifier, contentKey = originKey, contentAlignment = Alignment.BottomStart, transitionSpec = {
        // Sheet panes can have different heights. Keep their bottom edge pinned and
        // finish sizing with the same seek, not when the taller outgoing pane is disposed.
        if (motion) (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished).using(
            SizeTransform(clip = false) { _, _ -> tween(300) })
        else (EnterTransition.None togetherWith ExitTransition.None).using(null)
    }) { shown ->
        var layout by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val moving = motion && distance > 0
        val child = originKey(shown) == originKey(deep)
        val active = shown == page && !moving
        val input = if (active) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
        }
        val mask = if (!moving) Modifier else Modifier.drawWithCache {
            val path = Path()
            onDrawWithContent {
                val node = origins.nodes[originKey(deep)]
                val here = layout
                val progress = ((height.value - lo) / distance).coerceIn(0f, 1f)
                if (node == null || !node.isAttached || here == null || !here.isAttached || node.boundsInRoot().isEmpty) {
                    if (shown == page) drawContent()
                } else {
                    val at = here.positionInRoot()
                    val source = node.boundsInRoot().translate(-at)
                    val end = endpoints[originKey(deep)]
                    val destination = if (end != null && end.isAttached) end.boundsInRoot().translate(-at)
                        else Rect(0f, 0f, size.width, size.height)
                    val bounds = lerp(source, destination, progress)
                    // Complementary clips, including the landing itself. As the moving
                    // pane reaches its control the real parent takes back the last pixels.
                    val coverage = smoothHandoff(progress, 0f, .045f)
                    val reveal = if (coverage == 1f) bounds else lerp(Rect(bounds.center, bounds.center), bounds, coverage)
                    val radius = minOf(16.dp.toPx(), reveal.minDimension / 2) * (1f - progress)
                    path.reset(); path.addRoundRect(RoundRect(reveal, CornerRadius(radius)))
                    clipPath(path, if (child) ClipOp.Intersect else ClipOp.Difference) {
                        if (child) translate(bounds.left - destination.left, bounds.top - destination.top) { this@onDrawWithContent.drawContent() }
                        else this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
        val key = originKey(shown)
        DisposableEffect(key) { onDispose { if (endpoints[key] === layout) endpoints.remove(key) } }
        Box(Modifier.onGloballyPositioned { layout = it; endpoints[key] = it }.then(mask).then(input),
            propagateMinConstraints = true) { content(shown) }
    }
}
