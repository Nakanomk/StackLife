package io.github.nakanomk.stacklife.transitions

import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

// BiliPai SharedCardContainerHost.kt, 5893c2c80739e3759ac64ae5f232cf03a94da7f8 (GPL-3.0).
// Keep both pages in the existing sheet/window and seek the same transition on cancellation.
internal class PaneOrigins : ContainerOrigins()
private val LocalPane = staticCompositionLocalOf<String?> { null }

internal fun Modifier.paneOrigin(origins: PaneOrigins, key: String): Modifier = composed {
    val owner = LocalPane.current ?: return@composed this
    val density = LocalDensity.current
    val site = OriginSite(owner, key, currentCompositeKeyHashCode)
    val anchor = remember(site, density) { ContainerAnchor(site, RoundedCornerShape(16.dp), density) }
    containerSource(origins, anchor)
}

@Composable
internal fun <T : Any> PaneMotion(
    page: T, origins: PaneOrigins, depth: (T) -> Int, originKey: (T) -> String,
    modifier: Modifier = Modifier, parent: T? = null, onBack: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    if (!motion) {
        BackHandler(enabled = parent != null && onBack != null) { onBack?.invoke() }
        androidx.compose.foundation.layout.Box(modifier, propagateMinConstraints = true) { content(page) }
        return
    }
    val state = remember { SeekableTransitionState(page) }
    val hostTransition = rememberTransition(state, label = "pane container")
    var rewind by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(page, motion) {
        rewind?.cancelAndJoin()
        val from = state.currentState
        if (depth(page) > depth(from)) origins.capture(originKey(page), originKey(from))
        // Ease the host fraction, not the children's bounds/ink/size. A partially
        // completed Back continues from its held geometry, not from a new endpoint.
        val remaining = if (state.targetState == page) 1f - state.fraction else 1f
        state.animateTo(page, animationSpec = tween((ContainerDuration * remaining).toInt()))
    }
    val recovery = rememberCoroutineScope()
    PredictiveBackHandler(enabled = parent != null && onBack != null) { events ->
        val previous = parent ?: return@PredictiveBackHandler
        rewind?.cancelAndJoin()
        try {
            // PredictiveBackHandler starts this block without emitting a progress
            // event. Place the returning source at the fully open endpoint before
            // seeking: the first delivered move can already be far from the edge.
            val grabbed = if (state.targetState == previous) state.fraction else 0f
            state.seekTo(grabbed, previous)
            events.collect { state.seekTo(grabbed + (1f - grabbed) * it.progress.coerceIn(0f, 1f), previous) }
            onBack?.invoke()
        } catch (cancel: CancellationException) {
            // Retargeting animateTo(page) makes the original page both endpoints and
            // removes the clips in mid-gesture. Retrace the same two panes instead.
            if (state.targetState == previous) rewind = recovery.launch {
                val cursor = Animatable(state.fraction)
                val sampling = launch { snapshotFlow { cursor.value }.collect { state.seekTo(it, previous) } }
                try {
                    cursor.animateTo(0f, tween((ContainerDuration * cursor.value).toInt()))
                } finally {
                    sampling.cancelAndJoin()
                }
                state.seekTo(0f, previous)
                state.snapTo(page)
            }
            throw cancel
        }
    }
    val from = hostTransition.currentState
    val to = hostTransition.targetState
    val returning = depth(from) > depth(to)
    val deep = if (returning) from else to
    val moving = from != to
    val open = remember(state, moving, returning) { derivedStateOf {
        if (!moving) 1f else if (returning) 1f - state.fraction else state.fraction
    } }
    // Sheet height is itself animated; a *window* resize, not that intended sizing, invalidates a pair.
    val window = LocalWindowInfo.current.containerSize
    SharedTransitionLayout(modifier.onGloballyPositioned { origins.host = it; origins.size = window }) {
    val shared = this
    hostTransition.AnimatedContent(contentKey = originKey, contentAlignment = Alignment.BottomStart, transitionSpec = {
        (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished).apply {
            targetContentZIndex = depth(targetState).toFloat()
        }.using(SizeTransform(clip = false) { _, _ -> tween(ContainerDuration, easing = LinearEasing) })
    }) { shown ->
        val child = originKey(shown) == originKey(deep)
        val active = shown == page && !moving
        val input = if (active) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
        }
        val owner = originKey(shown)
        val link = origins.links[owner]
        // Unlike separate Nav3 entries, both panes already belong to this same
        // SeekableTransitionState. Enlist their shared pair directly: a second
        // collector/seek could initialize its bounds at a different gesture frame.
        val pair = if (link == null) null else hostTransition.createChildTransition("pane pair") {
            originKey(it) == owner || depth(it) > depth(shown)
        }.placedPair(link)
        CompositionLocalProvider(LocalPane provides owner) {
            ContainerPage(origins, link, pair?.page ?: this, shared, open, returning, child, moving && child,
                Modifier.testTag("pane:$owner").then(input)) { content(shown) }
        }
    }
    with(origins) { PlacedSources() }
    }
}
