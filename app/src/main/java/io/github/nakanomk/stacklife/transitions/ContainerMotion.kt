package io.github.nakanomk.stacklife.transitions

// Placement gates, linear bounds, overlay ordering and ink handoff adapted from BiliPai
// SettingsSharedContainerTransition.kt, SettingsContainerSource.kt and SettingsContainerMotionPolicy.kt
// (5893c2c80739e3759ac64ae5f232cf03a94da7f8, GPL-3.0). The host is the actual Nav3 or sheet layout.
import androidx.compose.animation.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.createChildTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt

internal const val ContainerDuration = 300
internal val containerBounds = BoundsTransform { _, _ -> tween(ContainerDuration, easing = LinearEasing) }
internal fun containerInk(open: Float, returning: Boolean) = ((open - if (returning) .6f else .2f) / .2f).coerceIn(0f, 1f)

@Composable
internal fun AnimatedVisibilityScope.containerOpen(enabled: Boolean): State<Float> = transition.animateFloat(
    { if (enabled) tween(ContainerDuration, easing = LinearEasing) else snap() }, label = "container openness",
) { if (it == EnterExitState.Visible) 1f else 0f }

/** A Popup/Dialog has a different coordinate root. Clipped or detached nodes have no landing. */
internal fun LayoutCoordinates.fullBoundsIn(host: LayoutCoordinates?): Rect? {
    if (host == null || !host.isAttached || !isAttached || findRootCoordinates() !== host.findRootCoordinates()) return null
    val whole = host.localBoundingBoxOf(this, clipBounds = false)
    if (whole.width <= 1f || whole.height <= 1f || whole.left < -1f || whole.top < -1f ||
        whole.right > host.size.width + 1f || whole.bottom > host.size.height + 1f) return null
    val visible = boundsInWindow()
    val uncut = boundsInWindow(clipBounds = false)
    return whole.takeIf { visible.width + 1f >= uncut.width && visible.height + 1f >= uncut.height }
}

internal val LocalContainerScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalEntryDepth = compositionLocalOf { 0 }

internal data class OriginSite(val owner: String?, val target: String, val site: Long)
internal class ContainerAnchor(val site: OriginSite, val shape: Shape, val density: Density, val hosted: Boolean = true) {
    var coordinates: LayoutCoordinates? = null
    var bounds by mutableStateOf<Rect?>(null)
        private set
    fun place(node: LayoutCoordinates, host: LayoutCoordinates?) { coordinates = node; bounds = node.fullBoundsIn(host) }
    fun radius(box: Rect) = (shape as? CornerBasedShape)?.topStart?.toPx(box.size, density)?.coerceIn(0f, box.minDimension / 2) ?: 0f
}

/** Remembers identity and host metrics, never a rectangle to animate back to. */
internal class ContainerLink(val site: OriginSite, val hostSize: IntSize, val density: Density, val radius: Float) {
    var destination by mutableStateOf<LayoutCoordinates?>(null)
    var sourcePlaced by mutableStateOf(false)
    var driver by mutableStateOf<PairVisibility?>(null)
    var open by mutableStateOf<State<Float>?>(null)
    var returning by mutableStateOf(false)
    var depth by mutableIntStateOf(0)
    val shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius * (1f - (open?.value ?: 0f)))))
    }
}

internal class PairVisibility(val row: AnimatedVisibilityScope, val page: AnimatedVisibilityScope, val open: State<Float>)

@Composable
private fun Transition<Boolean>.side(label: String, page: Boolean): AnimatedVisibilityScope {
    val child = createChildTransition(label) { if (it == page) EnterExitState.Visible else EnterExitState.PreEnter }
    return remember(child) { object : AnimatedVisibilityScope { override val transition = child } }
}

/** Back recomposes the removed parent with a *new* Nav3 entry transition. Keep the two
 * shared endpoints on the destination's seek instead; a newly placed row must not start
 * its own clock or replace the fully open start rectangle with its own bounds. */
@Composable
internal fun pairVisibility(origins: ContainerOrigins, link: ContainerLink?, open: State<Float>, moving: Boolean,
    returning: Boolean): PairVisibility? {
    if (link == null) return null
    val state = remember(link) { SeekableTransitionState(returning || open.value == 1f) }
    val pair = rememberTransition(state, label = "placed container")
    val row = pair.side("source", false)
    val page = pair.side("destination", true)
    val sample = pair.animateFloat({ tween(ContainerDuration, easing = LinearEasing) }, label = "sampled frame") { if (it) 1f else 0f }
    val driver = remember(row, page, sample) { PairVisibility(row, page, sample) }
    val active by rememberUpdatedState(moving)
    val progress by rememberUpdatedState(open)
    DisposableEffect(link, driver) {
        link.driver = driver
        onDispose { if (link.driver === driver) link.driver = null }
    }
    LaunchedEffect(link, state) {
        snapshotFlow { Triple(active, progress.value, origins.live(link) && link.destination?.isAttached == true) }
            .collect { (movingNow, fraction, placed) ->
                if (!movingNow && fraction == 1f) state.snapTo(true)
                else if (movingNow && placed) state.seekTo(if (state.currentState) 1f - fraction else fraction, !state.currentState)
            }
    }
    return driver
}

internal open class ContainerOrigins {
    private val anchors = mutableStateMapOf<OriginSite, ContainerAnchor>()
    val links = mutableStateMapOf<String, ContainerLink>()
    var host: LayoutCoordinates? = null
    var size by mutableStateOf(IntSize.Zero)
    fun attach(anchor: ContainerAnchor) { anchors[anchor.site] = anchor }
    fun detach(anchor: ContainerAnchor) { anchors.remove(anchor.site, anchor) }
    fun capture(target: String, owner: String?, chosen: OriginSite? = null) {
        val a = if (chosen != null) anchors[chosen]?.takeIf { chosen.owner == owner && chosen.target == target }
            else anchors.values.singleOrNull { it.site.owner == owner && it.site.target == target && it.coordinates?.fullBoundsIn(host) != null }
        a?.coordinates?.let { a.place(it, host) }
        val bounds = a?.bounds
        if (bounds == null) links.remove(target)
        else links[target] = ContainerLink(a.site, size, a.density, a.radius(bounds))
    }
    /** The endpoint is geometry only. Moving the painted control through an overlay
     * and counter-translating it still filters its text at fractional display pixels. */
    @Composable
    fun SharedTransitionScope.PlacedSources() {
        for ((_, link) in links) key(link) {
            val anchor = anchors[link.site]
            val bounds = anchor?.bounds
            val side = link.driver?.row
            if (anchor?.hosted == true && bounds != null && side != null) {
                val state = gatedContent(link) { live(link) }
                Spacer(Modifier.layout { measurable, constraints ->
                    val source = measurable.measure(Constraints.fixed(bounds.width.roundToInt(), bounds.height.roundToInt()))
                    // Do not contribute to the real host's size (especially a wrap-content sheet).
                    layout(constraints.minWidth, constraints.minHeight) {
                        val plane = host
                        val here = coordinates
                        if (plane?.isAttached == true && here != null) {
                            val at = here.localPositionOf(plane, bounds.topLeft).round()
                            source.place(at.x, at.y)
                        }
                    }
                }.onGloballyPositioned { link.sourcePlaced = true }
                    .sharedBounds(state, side, boundsTransform = containerBounds, enter = EnterTransition.None,
                        exit = ExitTransition.None, renderInOverlayDuringTransition = false)
                    .skipToLookaheadSize { state.isMatchFound })
            }
        }
    }

    fun sourceCoordinates(link: ContainerLink) = anchors[link.site]?.coordinates
    fun live(link: ContainerLink): Boolean {
        val a = anchors[link.site] ?: return false
        return a.bounds != null && a.coordinates?.isAttached == true && a.density == link.density && size == link.hostSize
    }
}

@Composable
internal fun SharedTransitionScope.gatedContent(key: Any, enabled: () -> Boolean): SharedTransitionScope.SharedContentState {
    val current by rememberUpdatedState(enabled)
    val config = remember { object : SharedTransitionScope.SharedContentConfig {
        override val SharedTransitionScope.SharedContentState.isEnabled get() = current()
        override val shouldKeepEnabledForOngoingAnimation get() = false
    } }
    return rememberSharedContentState(key, config)
}

@Composable
internal fun Modifier.containerSource(origins: ContainerOrigins, anchor: ContainerAnchor): Modifier {
    DisposableEffect(origins, anchor) { origins.attach(anchor); onDispose { origins.detach(anchor) } }
    return onGloballyPositioned { anchor.place(it, origins.host) }
}

@Composable
internal fun ContainerPage(origins: ContainerOrigins, link: ContainerLink?, visibility: AnimatedVisibilityScope,
    scope: SharedTransitionScope?, open: State<Float>, returning: Boolean, depth: Int, moving: Boolean,
    modifier: Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val driver = pairVisibility(origins, link, open, moving, returning)
    val side = driver?.page ?: visibility
    val shared = if (link == null || scope == null) null else with(scope) {
        // Keep the resting destination registered while its parent is off-stack. Its
        // own measured bounds start Back; the source separately gates the live match.
        gatedContent(link) { origins.size == link.hostSize && link.density == density }
    }
    if (link != null) {
        SideEffect { link.open = driver?.open ?: open; link.returning = returning; link.depth = depth }
        DisposableEffect(link) { onDispose { link.destination = null; link.open = null } }
    }
    val ink = { containerInk(driver?.open?.value ?: open.value, returning) }
    val frame = if (shared == null || scope == null || link?.sourcePlaced != true) Modifier else with(scope) {
        Modifier.sharedBounds(shared, side,
            enter = EnterTransition.None, exit = ExitTransition.None, boundsTransform = containerBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(link.shape), zIndexInOverlay = depth.toFloat(),
            renderInOverlayDuringTransition = moving).onGloballyPositioned { link.destination = it }
    }
    val paper = androidx.compose.material3.MaterialTheme.colorScheme.surface
    androidx.compose.foundation.layout.Box(frame.then(modifier).graphicsLayer {
        alpha = if (moving && shared?.isMatchFound != true) open.value else 1f
        if (link != null) shape = link.shape
        clip = moving && shared?.isMatchFound == true
    }.drawWithCache { onDrawWithContent {
        if (moving && shared?.isMatchFound == true && link != null) {
            drawContainerPaper(origins, link, paper, ink())
        }
        drawContent()
    } }, propagateMinConstraints = true) {
        val finalSize = if (scope == null || shared == null) Modifier else with(scope) { Modifier.skipToLookaheadSize { shared.isMatchFound } }
        androidx.compose.foundation.layout.Box(finalSize.graphicsLayer {
            alpha = if (moving && shared?.isMatchFound == true) ink() else 1f
        }, propagateMinConstraints = true) { content() }
    }
}

/** An opaque page outside the source; within its real footprint, reveal the actual parent
 * material/ink. No screenshot donor or cached rectangle can outlive its originating row. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawContainerPaper(origins: ContainerOrigins,
    link: ContainerLink, paper: Color, coverage: Float) {
    val source = origins.sourceCoordinates(link)
    val destination = link.destination
    if (coverage >= 1f || destination?.isAttached != true || source?.isAttached != true) { drawRect(paper); return }
    val slot = destination.localBoundingBoxOf(source, clipBounds = false)
    clipRect(slot.left, slot.top, slot.right, slot.bottom, clipOp = ClipOp.Difference) { drawRect(paper) }
    if (coverage > 0f) drawRect(paper.copy(alpha = coverage), slot.topLeft, slot.size)
}

internal fun Modifier.containerClip(bounds: () -> Rect, radius: () -> Float, enabled: () -> Boolean): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        if (!enabled()) drawContent() else {
            val rect = bounds()
            path.reset(); path.addRoundRect(RoundRect(rect, CornerRadius(radius().coerceIn(0f, rect.minDimension / 2f))))
            clipPath(path) { this@onDrawWithContent.drawContent() }
        }
    }
}
