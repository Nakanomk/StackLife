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
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import io.github.nakanomk.stacklife.appearance.LocalCapsuleBackdrop
import io.github.nakanomk.stacklife.appearance.LocalPanelBack
import io.github.nakanomk.stacklife.appearance.LocalWallpaperRecording
import io.github.nakanomk.stacklife.appearance.wallpaperBackground
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.round
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

internal const val ContainerDuration = 300
internal val containerBounds = BoundsTransform { _, _ -> tween(ContainerDuration, easing = LinearEasing) }
internal fun containerInk(open: Float, returning: Boolean) = ((open - if (returning) .6f else .2f) / .2f).coerceIn(0f, 1f)

@Composable
internal fun AnimatedVisibilityScope.containerOpen(enabled: Boolean, easing: Easing): State<Float> = transition.animateFloat(
    { if (enabled) tween(ContainerDuration, easing = easing) else snap() }, label = "container openness",
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
    var pageSize by mutableStateOf(IntSize.Zero)
    var frameSize by mutableStateOf(IntSize.Zero)
    var sourcePlaced by mutableStateOf(false)
    var driver by mutableStateOf<PairVisibility?>(null)
    var open by mutableStateOf<State<Float>?>(null)
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

/** Both endpoints, corners and ink sample one already-positioned fraction. */
@Composable
internal fun Transition<Boolean>.placedPair(link: ContainerLink): PairVisibility {
    val row = side("source", false)
    val page = side("destination", true)
    val sample = animateFloat({ tween(ContainerDuration, easing = LinearEasing) }, label = "sampled frame") { if (it) 1f else 0f }
    val driver = remember(row, page, sample) { PairVisibility(row, page, sample) }
    DisposableEffect(link, driver) {
        link.driver = driver
        onDispose { if (link.driver === driver) link.driver = null }
    }
    return driver
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
    val driver = pair.placedPair(link)
    val active by rememberUpdatedState(moving)
    val progress by rememberUpdatedState(open)
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
    fun place(anchor: ContainerAnchor, node: LayoutCoordinates) {
        // Only the captured launcher needs live clipped geometry. Keep the other
        // buttons' coordinates so a later click can resolve them at that instant.
        anchor.place(node, if (links[anchor.site.target]?.site == anchor.site) host else null)
    }
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
    fun sourceBounds(link: ContainerLink) = anchors[link.site]?.bounds
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
    return onGloballyPositioned { origins.place(anchor, it) }
}

@Composable
internal fun ContainerPage(origins: ContainerOrigins, link: ContainerLink?, visibility: AnimatedVisibilityScope,
    scope: SharedTransitionScope?, open: State<Float>, sample: State<Float>, returning: Boolean, front: Boolean, moving: Boolean,
    modifier: Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val shared = if (link == null || scope == null) null else with(scope) {
        // Keep the resting destination registered while its parent is off-stack. Its
        // own measured bounds start Back; the source separately gates the live match.
        gatedContent(link) { front && origins.size == link.hostSize && link.density == density }
    }
    if (link != null) {
        SideEffect { link.open = sample }
        DisposableEffect(link) { onDispose { link.destination = null; link.open = null } }
    }
    val ink = { containerInk(sample.value, returning) }
    val paired = { moving && link != null && link.drawingMatch(origins, shared) }
    val frame = if (shared == null || scope == null || link?.sourcePlaced != true) Modifier else with(scope) {
        Modifier.sharedBounds(shared, visibility,
            enter = EnterTransition.None, exit = ExitTransition.None, boundsTransform = containerBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            // Nav3 / AnimatedContent already own the z-order. Keep the painted page with
            // its parent and its donor; sharedBounds still places it at the animated rect.
            renderInOverlayDuringTransition = false).placedPage(link)
    }
    val finalSize = if (scope == null || shared == null) Modifier else with(scope) {
        Modifier.skipToLookaheadSize { shared.isMatchFound }
    }
    androidx.compose.foundation.layout.Box(frame.then(modifier).graphicsLayer {
        val matched = paired()
        alpha = when {
            !moving -> 1f
            matched -> if (sample.value > 0f) 1f else 0f
            link != null && origins.live(link) && !returning -> sample.value
            else -> open.value
        }
        if (link != null) shape = link.shape
        clip = matched
    }.containerPaper(origins, link, moving, paired, ink).then(finalSize), propagateMinConstraints = true) { content() }
}

/** Immediately inside sharedBounds: its lookahead placeholder and the painted child's
 * approach constraints can differ. onGloballyPositioned on the wrapper isn't a measurement
 * of that child. Retain only the two currently placed sizes, never a landing position. */
internal fun Modifier.placedPage(link: ContainerLink): Modifier = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    val measured = IntSize(child.width, child.height)
    val lookahead = isLookingAhead
    layout(child.width, child.height) {
        if (lookahead) link.pageSize = measured else link.frameSize = measured
        child.place(0, 0)
    }
}.onGloballyPositioned { link.destination = it }

internal fun ContainerLink.drawingMatch(origins: ContainerOrigins, shared: SharedTransitionScope.SharedContentState?): Boolean {
    if (destination?.isAttached != true || !sourcePlaced || !origins.live(this) ||
        pageSize == IntSize.Zero || frameSize == IntSize.Zero) return false
    // A held seek can place a real intermediate frame without running the overlay.
    // Conversely, match-found alone must not cover the whole parent with empty material
    // while Compose is still acquiring the source (or releasing it at the end of Back).
    return frameSize != pageSize || shared?.isMatchFound == true &&
        (frameSize == origins.sourceCoordinates(this)?.size || open?.value == 1f)
}

/** Mask the actual material within the real source footprint. The parent, not a captured
 * copy, supplies the text underneath. Sheet donors contain the shell only, never chat text. */
@Composable
internal fun Modifier.containerPaper(origins: ContainerOrigins?, link: ContainerLink?, moving: Boolean,
    paired: () -> Boolean, ink: () -> Float): Modifier {
    val pane = origins is PaneOrigins
    val sheet = pane && LocalPanelBack.current != null
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val paper = if (sheet) colors.surfaceContainerHigh else colors.surface
    if (!moving) return if (pane) this else wallpaperBackground(paper)
    val donor = if (sheet) LocalCapsuleBackdrop.current else LocalWallpaperRecording.current
    if (link == null) return wallpaperBackground(paper, donor)
    var position by remember { mutableStateOf(Offset.Unspecified) }
    return onGloballyPositioned {
        position = origins?.host?.takeIf { host -> host.isAttached }?.localPositionOf(it, Offset.Zero) ?: Offset.Unspecified
    }.sourceHandoff({ if (paired()) ink() else 1f }) {
        if (position.isSpecified) origins?.sourceBounds(link)?.translate(-position) else null
    }.wallpaperBackground(paper, donor)
}

/** The last layer holds live drawing commands, not a bitmap of the page. Keep group
 * opacity (including overlapping ink and glass) but isolate only the launcher's tile. */
internal fun Modifier.sourceHandoff(coverage: () -> Float, slot: () -> Rect?): Modifier = drawWithContent {
    val a = coverage()
    val overlap = if (a < 1f) slot()?.intersect(Rect(Offset.Zero, size)) else null
    when {
        a >= 1f -> drawContent()
        a <= 0f -> if (overlap == null || overlap.isEmpty) drawContent() else
            clipRect(overlap.left, overlap.top, overlap.right, overlap.bottom, ClipOp.Difference) { this@drawWithContent.drawContent() }
        // A fractional group previously had an implicit clip at the layer's own edges.
        overlap == null || overlap.isEmpty -> clipRect { this@drawWithContent.drawContent() }
        else -> clipRect {
            // Split on pixel edges; the original DstOut still supplies the fractional
            // edge inside this tile. Both clips replay the same live child display list.
            val l = floor(overlap.left)
            val t = floor(overlap.top)
            val r = ceil(overlap.right)
            val b = ceil(overlap.bottom)
            if (l > 0f || t > 0f || r < size.width || b < size.height)
                clipRect(l, t, r, b, ClipOp.Difference) { this@drawWithContent.drawContent() }
            clipRect(l, t, r, b) {
                val canvas = drawContext.canvas.nativeCanvas
                val save = canvas.saveLayer(l, t, r, b, null)
                try {
                    this@drawWithContent.drawContent()
                    drawRect(Color.White.copy(alpha = 1f - a), overlap.topLeft, overlap.size, blendMode = BlendMode.DstOut)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }
    }
}.graphicsLayer()

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
